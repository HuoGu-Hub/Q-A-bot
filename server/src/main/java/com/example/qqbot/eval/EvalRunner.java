package com.example.qqbot.eval;

import com.example.qqbot.persistence.EvalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批次执行器 —— 把一批用例放到**独立的低优先级线程池**里跑。
 *
 * <h2>为什么要独立线程池</h2>
 * 问答池只有 6 个线程。评测直接把批次塞进去，一批 300 条就是一个持续的占满，
 * 群友那一刻问什么都得排队。独立池 + 默认 2 并发是"能跑完，但绝不挤占线上"的折中。
 *
 * <p>代价是批次之间也只并行 2 个。想更快就开第二个进程指向库副本，
 * 而不是把并发调大 —— SQLite 单连接会让所有人一起变慢。
 */
@Component
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final EvalRepository repo;
    private final EvalCaseExecutor executor;
    private final EvalAggregator aggregator;
    private final ObjectMapper mapper;
    private final ExecutorService pool;

    public EvalRunner(EvalRepository repo, EvalCaseExecutor executor, EvalAggregator aggregator,
                      ObjectMapper mapper) {
        this.repo = repo;
        this.executor = executor;
        this.aggregator = aggregator;
        this.mapper = mapper;
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "eval-runner-" + seq.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    public void submit(long batchId) {
        pool.submit(() -> runBatch(batchId));
    }

    void runBatch(long batchId) {
        List<EvalRepository.PendingItem> items = repo.itemsOfBatch(batchId);
        repo.markBatchRunning(batchId, Instant.now().toString());
        log.info("[EVAL] 批次 {} 开始执行，共 {} 条", batchId, items.size());
        int failed = 0;
        for (EvalRepository.PendingItem item : items) {
            try {
                CaseSpec spec = mapper.readValue(item.snapshot(), CaseSpec.class);
                executor.execute(batchId, item.id(), spec);
            } catch (Exception e) {
                failed++;
                repo.updateItemStatus(item.id(), "failed");
                log.warn("[EVAL] 用例项 {} 执行异常：{}", item.id(), e.getMessage());
            }
        }
        try {
            EvalViews.Summary summary = aggregator.summarise(batchId);
            String gate = aggregator.gateOf(summary);
            repo.finishBatch(batchId, "completed", mapper.writeValueAsString(summary), gate,
                    Instant.now().toString());
            log.info("[EVAL] 批次 {} 完成：判定 {} 条，通过率 {}，门禁 {}",
                    batchId, summary.judged(), summary.passRate(), gate);
        } catch (Exception e) {
            repo.finishBatch(batchId, "failed", null, "pending", Instant.now().toString());
            log.warn("[EVAL] 批次 {} 聚合失败：{}（已完成的结果保留）", batchId, e.getMessage());
        }
        if (failed > 0) {
            log.warn("[EVAL] 批次 {} 有 {} 条用例执行异常", batchId, failed);
        }
    }
}
