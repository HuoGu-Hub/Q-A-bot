package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 地图侧的三个**长任务**：wiki 同步、地点语料派生、底图缓存预热。
 *
 * <h2>为什么不在请求线程里跑</h2>
 * 首次同步要拉 32 页约 548 KB；语料派生要给上百个块各调一次向量接口；
 * 预热要拉几百张瓦片。这些在 HTTP 请求里跑，短则十几秒、长则几十分钟 ——
 * 中间任何一层超时（浏览器、Nginx、Tomcat）都会让管理员看到"失败"，
 * 而任务其实还在跑，于是他会再点一次。**要做的事和能不能同步做完是两件事**，必须分开。
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

    /**
     * 轻量预热的档位 —— 首屏与常用缩放的细节层。
     *
     * <p>查看器的底座层固定 160，细节层按缩放选档（见 {@code MapCanvas.vue}）。
     * 大图首屏落在 640，所以 640 必须在轻量预热里 —— 否则首屏每次都由访客现取。
     */
    static final int[] LITE_WARM_WIDTHS = {160, 320, 640};

    /**
     * 全量预热的档位 —— <b>必须与查看器实际会请求的档位完全一致</b>。
     *
     * <p>⚠️ 这里**故意不包含 1280**。配置白名单 {@code app.kb.map.widths} 里有 1280，
     * 但查看器在 {@code want >= 1280} 时会把档位换成 {@code 0}（原图）并**不带 w 参数** ——
     * 也就是说 {@code ?width=1280} 这个文件前端一辈子不会请求。
     * 按配置白名单循环去预热，会白拉一百多张大图，还吃掉被限流的出站额度。
     *
     * <p>0 = 原图，只在放大到 1 倍以上时用到，而那时视野里只有个位数张 —— 所以它是
     * 全量档里最贵的一份，只在非高峰或首次部署后值得拉。
     */
    static final int[] FULL_WARM_WIDTHS = {160, 320, 640, 0};

    /** 一次任务的结果快照 */
    public record JobState(String name, String label, boolean running, boolean ok,
                           String startedAt, String finishedAt, String summary) {
    }

    /**
     * 一次预热的缺口报告。
     *
     * <h2>为什么要有它</h2>
     * 旧实现只数"成功了几张"，任务状态**硬编码为成功** —— 拉失败、上游 404 都不进任何计数，
     * 于是"已缓存 111 张"既可能是"齐了"，也可能是"缺了一半"。
     * 定时全量预热是没有人在旁边盯着的，这种盲区不能留：任务必须自己说清楚缺了什么。
     */
    public record WarmReport(int total, int cached, int fetched, int missing, long elapsedMs) {

        /** 有缺口即不算成功 —— 宁可显示"失败"，也不要让人以为预热过就没事了 */
        public boolean ok() {
            return missing == 0;
        }

        public String describe() {
            return "命中 " + cached + " / 新下载 " + fetched + " / 失败 " + missing
                    + "（共 " + total + "，" + elapsedMs + " ms）";
        }
    }

    private final KbMapSyncService syncService;
    private final LocationCorpusBuilder corpusBuilder;
    private final MapImageCache images;
    private final MapViewService view;
    private final MapOptions options;

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
                   MapImageCache images, MapViewService view, MapOptions options) {
        this.syncService = syncService;
        this.corpusBuilder = corpusBuilder;
        this.images = images;
        this.view = view;
        this.options = options;
    }

    /** 当前三个任务的状态（没跑过也返回一条"从未运行"） */
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
                autoWarmAfterSync(r, dryRun);
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
     * 内容变了就自动补一次**首屏档**预热。
     *
     * <h2>为什么是轻量而不是全量</h2>
     * 同步是管理员随时可能点的（白天也可能），而全量要拉原图档、跑十几分钟，
     * 还会把出站间隔顶高、拖慢同时在线的访客 —— 那是非高峰才做的事（见
     * {@link MapWarmScheduler}）。这里只补"没人预热过就一定会被访客撞上"的那几档。
     *
     * <p>失败的页不触发：内容还在半路上，补出来的缓存也没意义。
     */
    private void autoWarmAfterSync(KbMapSyncService.SyncReport r, boolean dryRun) {
        if (dryRun || r.changed() <= 0 || r.failed() > 0 || !options.isWarmAfterSync()) {
            return;
        }
        log.info("[MAP-JOB] 同步发现 {} 页有变化，自动补一次首屏档预热", r.changed());
        if (!startWarm()) {
            log.info("[MAP-JOB] 已有预热在跑，本次自动预热跳过");
        }
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
     * 预热底图缓存的**首屏档**（轻量）。
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
        return submitWarm("底图缓存预热", LITE_WARM_WIDTHS);
    }

    /**
     * 全量预热：在首屏档之外**再拉原图档**。
     *
     * <p>原图只在放大到 1 倍以上时用到，那时视野里只有个位数张 —— 按需取只要几秒。
     * 所以它值不值得拉，取决于"有没有人在看"：非高峰自动跑一次（{@link MapWarmScheduler}）、
     * 首次部署后手动点一次，都划算；高峰期点它则是拿访客的首屏去换一张没人立刻要的图。
     *
     * @return false 表示已在运行
     */
    public boolean startFullWarm() {
        return submitWarm("底图缓存预热（全量）", FULL_WARM_WIDTHS);
    }

    /** 两种预热走同一套标记与线程 —— 并发跑两遍只会重复下载，没有好处 */
    private boolean submitWarm(String label, int[] widths) {
        if (!warmRunning.compareAndSet(false, true)) {
            return false;
        }
        mark(WARM, label, true, true, "已开始（" + widths.length + " 档）");
        pool.submit(() -> {
            try {
                WarmReport r = warm(widths);
                if (!r.ok()) {
                    log.warn("[MAP-JOB] 预热有缺口：{}", r.describe());
                }
                mark(WARM, label, false, r.ok(), r.describe());
            } catch (Exception e) {
                log.warn("[MAP-JOB] 缓存预热失败：{}", e.getMessage());
                mark(WARM, label, false, false, "失败：" + e.getMessage());
            } finally {
                warmRunning.set(false);
            }
        });
        return true;
    }

    /**
     * 把"查看器会用到的图"逐个取进本地缓存，并如实汇报缺口。
     *
     * <p><b>分组图标也算</b>：它们只有几十个、每个几 KB，但**不预热的话首屏就是一片占位圆点**
     * —— 图标没到之前只能画圆点，而"分不出这是资源还是敌寨"正是用户最直接的抱怨。
     * 图标在缓存里的键是 {@code (map, 空 page, 文件名)}，与浏览器发的请求一致。
     *
     * <p>数据一次从 {@code catalog()} 拿全：它内部已经算好了每张地图真正用得到的画布，
     * 再单独遍历一遍地图名只会多一次全量扫描。
     *
     * <p>已经落盘的图会直接命中磁盘、一个字节都不再请求上游 —— 所以这个方法是**幂等**的，
     * 重复跑只补缺口。
     */
    WarmReport warm(int[] widths) {
        long t0 = System.currentTimeMillis();
        int[] counts = new int[3];
        for (MapViewService.MapView m : view.catalog()) {
            for (MapViewService.GroupView g : m.groups()) {
                if (g.icon() != null && !g.icon().isBlank()) {
                    tally(counts, images.get(m.map(), null, g.icon(), 0));
                }
            }
            for (MapViewService.CanvasView c : m.canvases()) {
                for (MapViewService.TileView t : c.tiles()) {
                    for (int w : widths) {
                        tally(counts, images.get(m.map(), c.page(), t.image(), w));
                    }
                }
                if (c.background() != null && !c.background().isBlank()) {
                    for (int w : widths) {
                        tally(counts, images.get(m.map(), c.page(), c.background(), w));
                    }
                }
            }
        }
        return new WarmReport(counts[0] + counts[1] + counts[2], counts[0], counts[1], counts[2],
                System.currentTimeMillis() - t0);
    }

    /** 计数：0 = 命中缓存，1 = 本次新下载，2 = 拿不到（失败或上游没有） */
    private static void tally(int[] c, Optional<MapImageCache.Image> got) {
        if (got.isEmpty()) {
            c[2]++;
        } else if (got.get().fromCache()) {
            c[0]++;
        } else {
            c[1]++;
        }
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
