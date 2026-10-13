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

    /** 一次任务的结果快照 */
    public record JobState(String name, String label, boolean running, boolean ok,
                           String startedAt, String finishedAt, String summary) {
    }

    private final KbMapSyncService syncService;
    private final LocationCorpusBuilder corpusBuilder;

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "map-jobs");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, JobState> states = new ConcurrentHashMap<>();
    private final AtomicBoolean syncRunning = new AtomicBoolean();
    private final AtomicBoolean corpusRunning = new AtomicBoolean();

    public MapJobs(KbMapSyncService syncService, LocationCorpusBuilder corpusBuilder) {
        this.syncService = syncService;
        this.corpusBuilder = corpusBuilder;
    }

    /** 当前两个任务的状态（没跑过也返回一条"从未运行"） */
    public Map<String, JobState> states() {
        Map<String, JobState> out = new LinkedHashMap<>();
        out.put(SYNC, states.getOrDefault(SYNC, idle(SYNC, "wiki 地图同步")));
        out.put(CORPUS, states.getOrDefault(CORPUS, idle(CORPUS, "地点语料派生")));
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
