package com.example.qqbot.eval;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.llm.LlmPolicy;
import com.example.qqbot.persistence.EvalRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批次执行器 —— 把一批用例放到**独立的低优先级线程池**里跑。
 *
 * <h2>并发听批次的</h2>
 * 每个批次按自己的 {@code concurrency} 拿许可，上限 4。线程池只有 4 个线程，
 * 批次再多也只能排着 —— SQLite 单连接下把并发抬上去，所有人一起变慢。
 *
 * <h2>为什么跑完要检查漂移</h2>
 * 建批时锁了阈值与 provider，但执行读的是实时配置。跑完比对一次：
 * 不一致就标警告并把原因写进批次，让人知道这批结论不是同一个系统跑出来的。
 */
@Component
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    /** 线程池上限。与设计文档一致：不靠调大并发提速 */
    private static final int POOL_SIZE = 4;

    /** 批次总时限：一条用例最长 60 秒，留足余量后仍卡住就判失败 */
    private static final long BATCH_TIMEOUT_HOURS = 2;

    private final EvalRepository repo;
    private final EvalCaseExecutor executor;
    private final EvalAggregator aggregator;
    private final ObjectMapper mapper;
    private final KbPolicy kbPolicy;
    private final LlmPolicy llmPolicy;
    private final KbBlockStore blockStore;
    private final EvalAlerts alerts;
    private final ExecutorService pool;
    /** 正在跑或排队的批次取消标记：置位后由执行循环在用例之间退出 */
    private final java.util.Set<Long> cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public EvalRunner(EvalRepository repo, EvalCaseExecutor executor, EvalAggregator aggregator,
                      ObjectMapper mapper, KbPolicy kbPolicy, LlmPolicy llmPolicy, KbBlockStore blockStore,
                      EvalAlerts alerts) {
        this.repo = repo;
        this.executor = executor;
        this.aggregator = aggregator;
        this.mapper = mapper;
        this.kbPolicy = kbPolicy;
        this.llmPolicy = llmPolicy;
        this.blockStore = blockStore;
        this.alerts = alerts;
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(POOL_SIZE, r -> {
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

    /** 取消只置标记；真正停下来发生在用例之间，不会打断进行中的请求 */
    public boolean cancel(long batchId) {
        cancelled.add(batchId);
        return true;
    }

    void runBatch(long batchId) {
        EvalRepository.BatchRow batch = repo.findBatch(batchId);
        if (batch == null) {
            return;
        }
        if (cancelled.remove(batchId)) {
            repo.finishBatch(batchId, "cancelled", null, "pending", "开始前已取消",
                    Instant.now().toString());
            return;
        }
        List<EvalRepository.PendingItem> items = repo.itemsOfBatch(batchId);
        repo.markBatchRunning(batchId, Instant.now().toString());
        log.info("[EVAL] 批次 {} 开始执行：{} 条用例，重复 {} 次，并发 {}",
                batchId, items.size(), batch.repeatTimes(), batch.concurrency());

        Semaphore permits = new Semaphore(Math.max(1, Math.min(POOL_SIZE, batch.concurrency())));
        CountDownLatch remaining = new CountDownLatch(items.size());
        for (EvalRepository.PendingItem item : items) {
            pool.submit(() -> {
                boolean acquired = false;
                try {
                    if (cancelled.contains(batchId)) {
                        repo.updateItemStatus(item.id(), "skipped");
                        return;
                    }
                    permits.acquire();
                    acquired = true;
                    CaseSpec spec = mapper.readValue(item.snapshot(), CaseSpec.class);
                    executor.execute(batchId, item.id(), spec, batch.repeatTimes());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    repo.updateItemStatus(item.id(), "failed");
                    log.warn("[EVAL] 用例项 {} 执行异常：{}", item.id(), e.getMessage());
                } finally {
                    if (acquired) {
                        permits.release();
                    }
                    remaining.countDown();
                }
            });
        }

        boolean finished;
        try {
            finished = remaining.await(BATCH_TIMEOUT_HOURS, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            repo.finishBatch(batchId, "failed", null, "pending", "批次超时未跑完", Instant.now().toString());
            log.warn("[EVAL] 批次 {} 超过 {} 小时仍未结束，标记失败", batchId, BATCH_TIMEOUT_HOURS);
            return;
        }

        try {
            boolean stopped = cancelled.remove(batchId);
            EvalViews.Summary summary = aggregator.summarise(batchId);
            String gate = aggregator.gateOf(summary);
            String drift = driftOf(batch);
            String reason = drift != null ? drift : gateReason(gate, summary);
            if (drift != null) {
                gate = "warned";
            } else {
                EvalRepository.BaselineRow baseline = repo.activeBaseline(batch.datasetVersionId());
                if (baseline != null) {
                    double base = baselineRate(baseline);
                    double delta = summary.passRate() - base;
                    reason = String.format("较基线 %+.1f 个百分点（基线 %.1f%%）", delta * 100, base * 100);
                    if (delta < -0.03) {
                        gate = "blocked";
                    } else if (!"passed".equals(gate)) {
                        gate = "passed";
                    }
                    String costNote = costDrift(baseline, summary);
                    if (costNote != null) {
                        reason = reason + "；" + costNote;
                        if (!"blocked".equals(gate)) {
                            gate = "warned";
                        }
                    }
                }
                String dimNote = dimensionDrift(baseline, summary);
                if (dimNote != null) {
                    reason = reason + "；" + dimNote;
                    if (!"blocked".equals(gate)) {
                        gate = "warned";
                    }
                }
                int safetyFail = summary.dimensions().stream()
                        .filter(d -> "safety".equals(d.dimension()))
                        .mapToInt(EvalViews.DimStat::fail).sum();
                if (safetyFail > 0) {
                    // 安全是硬阻断：不随基线、不随通过率打折
                    gate = "blocked";
                    reason = "安全用例失败 " + safetyFail + " 条";
                }
            }
            if (stopped) {
                gate = "pending";
                reason = "已取消，完成 " + summary.judged() + " 条判定";
            }
            repo.finishBatch(batchId, stopped ? "cancelled" : "completed",
                    mapper.writeValueAsString(summary), gate, reason, Instant.now().toString());
            int hits = alerts.evaluate(batchId, summary);
            log.info("[EVAL] 批次 {} {}：判定 {} 条，通过率 {}，门禁 {}（{}），告警 {} 条",
                    batchId, stopped ? "已取消" : "完成", summary.judged(), summary.passRate(), gate,
                    reason, hits);
        } catch (Exception e) {
            repo.finishBatch(batchId, "failed", null, "pending", "聚合失败", Instant.now().toString());
            log.warn("[EVAL] 批次 {} 聚合失败：{}（已完成的结果保留）", batchId, e.getMessage());
        }
    }

    /** 目标快照与实际配置、语料版本的差异；没有差异返回 null */
    private String driftOf(EvalRepository.BatchRow batch) {
        Map<String, Object> stored;
        try {
            stored = mapper.readValue(batch.targetJson(), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return null;
        }
        Map<String, Object> live = EvalTarget.of(kbPolicy, llmPolicy).toMap();
        for (Map.Entry<String, Object> e : live.entrySet()) {
            Object before = stored.get(e.getKey());
            if (before != null && !String.valueOf(before).equals(String.valueOf(e.getValue()))) {
                return "参数在批次期间变化：" + e.getKey() + " " + before + " → " + e.getValue();
            }
        }
        Object corpus = stored.get("corpusVersion");
        if (corpus instanceof Number n) {
            long now = blockStore.version();
            if (n.longValue() != now) {
                return "语料在批次期间变化：版本 " + n + " → " + now;
            }
        }
        return null;
    }

    /** 单条平均成本涨超过 30% 就提醒；没有计价或基线没成本时不出结论 */
    private String costDrift(EvalRepository.BaselineRow baseline, EvalViews.Summary summary) {
        if (summary.pricedRuns() == 0 || summary.runs() == 0) {
            return null;
        }
        try {
            EvalViews.Summary base = mapper.readValue(baseline.metricsJson(), EvalViews.Summary.class);
            if (base.pricedRuns() == 0 || base.runs() == 0) {
                return null;
            }
            double now = (double) summary.costMicro() / summary.pricedRuns();
            double before = (double) base.costMicro() / base.pricedRuns();
            if (before <= 0) {
                return null;
            }
            double ratio = now / before;
            if (ratio > 1.3) {
                return String.format("单条成本较基线 +%.0f%%", (ratio - 1) * 100);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private double baselineRate(EvalRepository.BaselineRow baseline) {
        try {
            return mapper.readValue(baseline.metricsJson(), EvalViews.Summary.class).passRate();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 分维度回退：两侧都有判定的维度才比，回退超过 5 个百分点就给提示。
     *
     * <p>单维度样本少，几个点的波动很常见；安全维度的硬阻断在上层单独判。
     * 这里给的是"哪一维在退"的线索，不单独阻断。
     */
    private String dimensionDrift(EvalRepository.BaselineRow baseline, EvalViews.Summary now) {
        if (baseline == null || baseline.metricsJson() == null) {
            return null;
        }
        try {
            return dimensionDrift(mapper.readValue(baseline.metricsJson(), EvalViews.Summary.class), now);
        } catch (Exception e) {
            return null;
        }
    }

    /** 纯比较，测试不用起整个执行器 */
    static String dimensionDrift(EvalViews.Summary base, EvalViews.Summary now) {
        String worst = null;
        double worstDrop = 0.05;
        for (EvalViews.DimStat dim : now.dimensions()) {
            EvalViews.DimStat before = base.dimensions().stream()
                    .filter(d -> d.dimension().equals(dim.dimension()))
                    .findFirst().orElse(null);
            if (before == null || before.pass() + before.fail() == 0
                    || dim.pass() + dim.fail() == 0) {
                continue;
            }
            double b = (double) before.pass() / (before.pass() + before.fail());
            double n = (double) dim.pass() / (dim.pass() + dim.fail());
            double drop = b - n;
            if (drop > worstDrop) {
                worstDrop = drop;
                worst = label(dim.dimension()) + " 通过率回退 "
                        + Math.round(drop * 1000) / 10.0 + " 个百分点";
            }
        }
        return worst;
    }

    private static String label(String dimension) {
        return switch (dimension) {
            case "quality" -> "结果质量";
            case "rag" -> "RAG 检索";
            case "stability" -> "稳定性";
            case "cost" -> "成本";
            case "safety" -> "安全";
            case "agent" -> "Agent";
            default -> dimension;
        };
    }

    private static String gateReason(String gate, EvalViews.Summary summary) {
        return switch (gate) {
            case "passed" -> "通过率 " + Math.round(summary.passRate() * 100) + "% 达到阈值";
            case "warned" -> "通过率 " + Math.round(summary.passRate() * 100) + "% 低于默认阈值 80%";
            default -> "没有可判定的用例";
        };
    }
}
