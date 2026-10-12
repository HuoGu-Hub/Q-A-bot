package com.example.qqbot.kb.wiki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 页面触发的 wiki 拉取 + 清洗 —— **异步任务**。
 *
 * <h2>为什么要有这一层</h2>
 * {@link WikiArticleImporter#importAll} 是同步的、一次跑几分钟（635 页，每页还要多取一次
 * 渲染 HTML）。HTTP 请求不能挂在那儿等，所以：
 * <ul>
 *   <li>导入跑在单线程后台线程上，接口立刻返回；</li>
 *   <li>进度与结果放在 {@link Status} 里，页面轮询；</li>
 *   <li>{@link AtomicBoolean} 保证同一时刻只有一次导入 —— 两个人同时点按钮，
 *       第二个直接拿到正在跑的那次状态，而不是并发写库。</li>
 * </ul>
 *
 * <p>状态只留在内存里：进程重启后回到"空闲"。导入本身是幂等的（增量判据是 revid），
 * 所以丢状态不影响正确性。
 */
@Service
public class WikiImportService {

    private static final Logger log = LoggerFactory.getLogger(WikiImportService.class);

    /** 页面上的三种触发方式 */
    public enum Mode {
        /** 只看数量，不写库 */
        DRY_RUN,
        /** 增量：只处理 revid 变过的页 */
        INCREMENTAL,
        /** 忽略 revid，全部重洗（清洗规则变了用这条） */
        FORCE,
        /** 回滚：清掉全部 wiki 文章块与导入状态（可重新导入） */
        PURGE
    }

    /**
     * 一次导入的状态快照。
     *
     * @param state  idle / running / done / failed
     * @param phase  当前阶段（如"清洗 · Wildlife"）
     * @param done   已处理页数
     * @param total  本次要处理的页数
     * @param report 跑完后的报告（idle / running 时为空）
     */
    public record Status(String state, String phase, int done, int total, String startedAt, String finishedAt,
                         Map<String, Object> report, List<String> errors) {
    }

    private final WikiArticleImporter importer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wiki-import");
        t.setDaemon(true);
        return t;
    });

    private volatile Status status = idle();

    public WikiImportService(WikiArticleImporter importer) {
        this.importer = importer;
    }

    private static Status idle() {
        return new Status("idle", "", 0, 0, "", "", Map.of(), List.of());
    }

    /** 开始一次导入；已经在跑就原样返回当前状态（不排队、不并发） */
    public Status start(Mode mode) {
        if (!running.compareAndSet(false, true)) {
            return status;
        }
        String startedAt = Instant.now().toString();
        status = new Status("running", "准备中", 0, 0, startedAt, "", Map.of(), List.of());
        log.info("[KB-WIKI] 页面触发导入：{}", mode);
        exec.submit(() -> run(mode, startedAt));
        return status;
    }

    public Status status() {
        return status;
    }

    public boolean isRunning() {
        return running.get();
    }

    private void run(Mode mode, String startedAt) {
        try {
            if (mode == Mode.PURGE) {
                int n = importer.purge();
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("purged", n);
                status = new Status("done", "回滚完成", 0, 0, startedAt, Instant.now().toString(),
                        report, List.of());
                log.info("[KB-WIKI] 页面触发的回滚：清除 {} 个块", n);
                return;
            }
            boolean dry = mode == Mode.DRY_RUN;
            boolean force = mode == Mode.FORCE;
            WikiArticleImporter.ImportReport r = importer.importAll(dry, force, (phase, done, total) ->
                    status = new Status("running", phase, done, total, startedAt, "", Map.of(), List.of()));

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("sources", r.sources());
            report.put("pages", r.pages());
            report.put("changed", r.changed());
            report.put("imported", r.imported());
            report.put("chars", r.chars());
            report.put("rendered", r.rendered());
            report.put("failed", r.failed());
            report.put("dryRun", r.dryRun());
            report.put("bySource", r.bySource());

            List<String> errors = new ArrayList<>(r.errors());
            status = new Status("done", dry ? "空跑完成" : "导入完成", r.pages(), r.pages(),
                    startedAt, Instant.now().toString(), report, errors);
            log.info("[KB-WIKI] 页面触发的导入结束：页面 {} 篇 / 写入 {} 块 / 渲染兜底 {} 页 / 失败 {}",
                    r.pages(), r.imported(), r.rendered(), r.failed());
        } catch (Throwable t) {
            // 任务异常必须落进状态里：页面只轮询状态，异常不抛给任何人
            log.warn("[KB-WIKI] 页面触发的导入失败：{}", t.toString());
            status = new Status("failed", "失败", status.done(), status.total(), startedAt,
                    Instant.now().toString(), Map.of(), List.of(String.valueOf(t.getMessage())));
        } finally {
            running.set(false);
        }
    }
}
