package com.example.qqbot.kb.map;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 地图侧的两个**长任务**：wiki 同步、地点语料派生。
 *
 * <h2>为什么不在请求线程里跑</h2>
 * 首次同步要拉 32 页约 548 KB；语料派生要给上百个块各调一次向量接口。
 * 这些在 HTTP 请求里跑，短则十几秒、长则几分钟 —— 中间任何一层超时
 * （浏览器、Nginx、Tomcat）都会让管理员看到"失败"，而任务其实还在跑，
 * 于是他会再点一次。**要做的事和能不能同步做完是两件事**，必须分开。
 *
 * <h2>为什么串行、且立即返回 409</h2>
 * 同一个任务并发跑两遍没有任何好处（同步是幂等的但会重复下载；
 * 语料派生的 id 是确定的，重复写只是浪费向量额度）。
 * 所以用单线程池 + {@code running} 标记：正在跑就明确告诉调用方"已经在跑了"，
 * 而不是排队或并发。
 */
@Service
public class MapJobs {

    private static final Logger log = LoggerFactory.getLogger(MapJobs.class);

    public static final String SYNC = "sync";
    public static final String CORPUS = "corpus";
    public static final String WARM = "warm";

    /** 一次任务的结果快照 */
    public record JobState(String name, String label, boolean running, boolean ok,
                           String startedAt, String finishedAt, String summary) {
    }

    private final KbMapSyncService syncService;
    private final LocationCorpusBuilder corpusBuilder;
    private final MapImageCache images;
    private final MapViewService view;

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "map-jobs");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, JobState> states = new ConcurrentHashMap<>();
    private final AtomicBoolean syncRunning = new AtomicBoolean();
    private final AtomicBoolean corpusRunning = new AtomicBoolean();
    private final AtomicBoolean warmRunning = new AtomicBoolean();

    public MapJobs(KbMapSyncService syncService, LocationCorpusBuilder corpusBuilder,
                   MapImageCache images, MapViewService view) {
        this.syncService = syncService;
        this.corpusBuilder = corpusBuilder;
        this.images = images;
        this.view = view;
    }

    /** 当前两个任务的状态（没跑过也返回一条"从未运行"） */
    public Map<String, JobState> states() {
        Map<String, JobState> out = new LinkedHashMap<>();
        out.put(SYNC, states.getOrDefault(SYNC, idle(SYNC, "wiki 地图同步")));
        out.put(CORPUS, states.getOrDefault(CORPUS, idle(CORPUS, "地点语料派生")));
        out.put(WARM, states.getOrDefault(WARM, idle(WARM, "底图缓存预热")));
        return out;
    }

    /**
     * 触发一次 wiki 地图同步。
     *
     * @return false 表示已在运行（调用方返回 409）
     */
    public boolean startSync(boolean dryRun) {
        if (!syncRunning.compareAndSet(false, true)) {
            return false;
        }
        mark(SYNC, "wiki 地图同步", true, true, "已开始" + (dryRun ? "（dryRun）" : ""));
        pool.submit(() -> {
            try {
                KbMapSyncService.SyncReport r = syncService.sync(dryRun);
                String summary = "页 " + r.pages() + " / 变更 " + r.changed() + " / 写入 marker "
                        + r.markers() + " / 失败 " + r.failed() + " / " + r.elapsedMs() + " ms";
                if (!r.errors().isEmpty()) {
                    summary = summary + "；" + String.join("；", r.errors());
                }
                mark(SYNC, "wiki 地图同步", false, r.failed() == 0, summary);
            } catch (Exception e) {
                log.warn("[MAP-JOB] 同步失败：{}", e.getMessage());
                mark(SYNC, "wiki 地图同步", false, false, "失败：" + e.getMessage());
            } finally {
                syncRunning.set(false);
            }
        });
        return true;
    }

    /**
     * 触发地点语料派生（或清除）。
     *
     * @return false 表示已在运行
     */
    public boolean startCorpus(boolean purge, boolean dryRun) {
        if (!corpusRunning.compareAndSet(false, true)) {
            return false;
        }
        mark(CORPUS, "地点语料派生", true, true,
                purge ? "已开始清除" : "已开始派生" + (dryRun ? "（dryRun）" : ""));
        pool.submit(() -> {
            try {
                if (purge) {
                    int n = corpusBuilder.purge();
                    mark(CORPUS, "地点语料派生", false, true, "已清除 " + n + " 条");
                } else {
                    LocationCorpusBuilder.BuildReport r =
                            dryRun ? corpusBuilder.planOnly() : corpusBuilder.build(false);
                    mark(CORPUS, "地点语料派生", false, true,
                            "计划 " + r.entries() + " / 已写入 " + r.written()
                                    + "（区域 " + r.regions() + "，POI " + r.pois()
                                    + "，具名 " + r.places() + "，NPC " + r.npcs() + "）");
                }
            } catch (Exception e) {
                log.warn("[MAP-JOB] 语料派生失败：{}", e.getMessage());
                mark(CORPUS, "地点语料派生", false, false, "失败：" + e.getMessage());
            } finally {
                corpusRunning.set(false);
            }
        });
        return true;
    }

    /**
     * 预热底图缓存。
     *
     * <h2>为什么必须有它，而不是让第一个访客去承担</h2>
     * 出站请求是**串行节流**的（wiki 会对并发回 429），拉满一张图的瓦片要二十来秒。
     * 没有预热的话，第一个打开地图的人看到的是二三十秒里一块块慢慢补齐的地图 ——
     * 而这件事只需要做一次，之后所有访客都命中本地缓存、一个字节都不再打扰 wiki。
     *
     * <p>只预热**查看器真正会用到的画布**：wiki 的 {@code Tabs/*} 定义页也带底图，
     * 但界面里一个点位都没挂上去，预它们等于白拉几百个请求。
     *
     * @return false 表示已在运行
     */
    public boolean startWarm() {
        if (!warmRunning.compareAndSet(false, true)) {
            return false;
        }
        mark(WARM, "底图缓存预热", true, true, "已开始");
        pool.submit(() -> {
            try {
                int n = warm();
                mark(WARM, "底图缓存预热", false, true, "已缓存 " + n + " 张底图");
            } catch (Exception e) {
                log.warn("[MAP-JOB] 缓存预热失败：{}", e.getMessage());
                mark(WARM, "底图缓存预热", false, false, "失败：" + e.getMessage());
            } finally {
                warmRunning.set(false);
            }
        });
        return true;
    }

    /**
     * 预热的档位：**只拉低倍两档**。
     *
     * <p>这个取舍算过一次账：打开地图时视野里有多少瓦片，取决于当前缩放 ——
     * 缩到最小时整个画布 64 张全在视野里（低倍档），而放大到 1 倍以上时视野里只剩个位数张（高倍档）。
     * 也就是说<b>要"多"的只有低倍档，要"清楚"的只有高倍档</b>。
     *
     * <p>把四档全拉过一次：原图每张约 363 KB，整轮要十几分钟，
     * 一个"点一下就等十几分钟"的按钮没人会用。而高倍档按需取只有几张，
     * 节流下也就几秒，期间低清图还在，不会白屏。
     */
    private static final int[] WARM_WIDTHS = {160, 320};

    /**
     * 把"查看器会用到的图"全部取进本地缓存。
     *
     * <p><b>分组图标也算</b>：它们只有几十个、每个几 KB，但**不预热的话首屏就是一片占位圆点**
     * —— 图标没到之前只能画圆点，而"分不出这是资源还是敌寨"正是用户最直接的抱怨。
     * 图标在缓存里的键是 {@code (map, 空 page, 文件名)}，与浏览器发的请求一致。
     *
     * <p>数据一次从 {@code catalog()} 拿全：它内部已经算好了每张地图真正用得到的画布，
     * 再单独遍历一遍地图名只会多一次全量扫描。
     */
    private int warm() {
        int done = 0;
        for (MapViewService.MapView m : view.catalog()) {
            for (MapViewService.GroupView g : m.groups()) {
                if (g.icon() != null && !g.icon().isBlank()
                        && images.get(m.map(), null, g.icon(), 0).isPresent()) {
                    done++;
                }
            }
            for (MapViewService.CanvasView c : m.canvases()) {
                for (MapViewService.TileView t : c.tiles()) {
                    for (int w : WARM_WIDTHS) {
                        if (images.get(m.map(), c.page(), t.image(), w).isPresent()) {
                            done++;
                        }
                    }
                }
                if (c.background() != null && !c.background().isBlank()) {
                    for (int w : WARM_WIDTHS) {
                        if (images.get(m.map(), c.page(), c.background(), w).isPresent()) {
                            done++;
                        }
                    }
                }
            }
        }
        return done;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    private void mark(String name, String label, boolean running, boolean ok, String summary) {
        long now = System.currentTimeMillis();
        JobState prev = states.get(name);
        String stamp = java.time.Instant.ofEpochMilli(now).toString();
        String started = running || prev == null ? stamp : prev.startedAt();
        states.put(name, new JobState(name, label, running, ok, started,
                running ? "" : stamp, summary));
    }

    private static JobState idle(String name, String label) {
        return new JobState(name, label, false, true, "", "", "尚未运行过");
    }
}
