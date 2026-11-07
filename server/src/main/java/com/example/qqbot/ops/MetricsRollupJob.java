package com.example.qqbot.ops;

import com.example.qqbot.persistence.LlmMetricRepository;
import com.example.qqbot.persistence.LlmUsageRepository;
import com.example.qqbot.persistence.TrafficRepository;
import com.example.qqbot.time.AppTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 每分钟把进程内的读数落库 —— 流量、模型调用、按日 token 用量一起。
 *
 * <h2>为什么是 5 秒轮询、按分钟边界落盘</h2>
 * 直接 {@code fixedDelay = 60s} 也能跑，但那一分钟是"从启动时刻算起的 60 秒"，
 * 跨着整分钟边界，图上每个点都不是整分钟，看趋势会别扭。
 * 这里 5 秒看一次表，跨分钟才落 —— 落的是**刚刚过去的那一分钟**，
 * 时间戳与数据对齐。代价是每分钟多 11 次空转，可以忽略。
 *
 * <h2>为什么三类读数共用一个调度</h2>
 * 它们的时间轴必须对齐：同一分钟里的"请求多"和"模型调用多"要能放在一起读；
 * 日用量也只在这里落，免得再开一条会和它抢锁的写路径。
 * 两个调度各跑各的，落盘时刻会差几毫秒到几秒，图上的相关性就成了假的。
 *
 * <h2>失败怎么办</h2>
 * 采集本身不能影响业务，落盘失败也只记日志、不重试：
 * 丢一分钟的读数，远好过在调度线程里抛异常。
 */
@Component
public class MetricsRollupJob {

    private static final Logger log = LoggerFactory.getLogger(MetricsRollupJob.class);

    /** 分钟表保留 7 天。再久没人翻，而且表会开始拖慢全量查询 */
    private static final int KEEP_DAYS = 7;

    /**
     * 日用量表保留 180 天。
     *
     * <p>成本回顾是按月看的，7 天清掉等于回答不了"上个月用了多少"。
     * 180 天 × 每天的模型数量，行数仍然很小。
     */
    private static final int KEEP_USAGE_DAYS = 180;

    /** 每落 60 行（约一小时）清一次过期行，不必每分钟都删 */
    private static final int PRUNE_EVERY = 60;

    private final TrafficMetrics traffic;
    private final LlmMetrics llm;
    private final TrafficRepository trafficRepo;
    private final LlmMetricRepository llmRepo;

    /** 按日 token 账本：进程内攒着，这里统一落库 */
    private final LlmUsageLedger usageLedger;
    private final LlmUsageRepository usageRepo;

    /** 上一分钟（epoch 分钟）。-1 表示还没对齐过，第一次只对表不落盘 */
    private long lastMinute = -1L;
    private int flushed;

    public MetricsRollupJob(TrafficMetrics traffic, LlmMetrics llm,
                            TrafficRepository trafficRepo, LlmMetricRepository llmRepo,
                            LlmUsageLedger usageLedger, LlmUsageRepository usageRepo) {
        this.traffic = traffic;
        this.llm = llm;
        this.trafficRepo = trafficRepo;
        this.llmRepo = llmRepo;
        this.usageLedger = usageLedger;
        this.usageRepo = usageRepo;
    }

    @Scheduled(fixedDelay = 5_000)
    public void tick() {
        long minute = System.currentTimeMillis() / 60_000L;
        if (lastMinute < 0) {
            // 启动后的第一次：只记下当前分钟，避免把"半个分钟"当成一整分钟写进去
            lastMinute = minute;
            return;
        }
        if (minute == lastMinute) {
            return;
        }
        flushTraffic(lastMinute);
        flushLlm(lastMinute);
        flushUsage();
        lastMinute = minute;
        if (++flushed % PRUNE_EVERY == 0) {
            prune(minute);
        }
    }

    private void flushTraffic(long minute) {
        TrafficMetrics.Snapshot snapshot = traffic.drain();
        if (snapshot == null) {
            return;
        }
        try {
            trafficRepo.save(new TrafficRepository.Row(minute, snapshot.requests(), snapshot.api(),
                    snapshot.errors(), snapshot.p50(), snapshot.p95(), snapshot.max()));
        } catch (Exception e) {
            log.warn("[METRICS] 流量落盘失败（这一分钟的数据丢了）：{}", e.getMessage());
        }
    }

    private void flushLlm(long minute) {
        LlmMetrics.Snapshot snapshot = llm.drain();
        if (snapshot == null) {
            return;
        }
        try {
            llmRepo.save(new LlmMetricRepository.Row(minute, snapshot.calls(), snapshot.failures(),
                    snapshot.tokensIn(), snapshot.tokensOut(),
                    snapshot.p50(), snapshot.p95(), snapshot.max()));
        } catch (Exception e) {
            log.warn("[METRICS] 模型调用落盘失败（这一分钟的数据丢了）：{}", e.getMessage());
        }
    }

    /**
     * 落每日 token 账。
     *
     * <p>和分钟表不同，这里一次可能写多行（多个模型/供应商）。
     * 失败只记日志：丢的是一分钟的账，不能让调度线程抛异常。
     */
    private void flushUsage() {
        List<LlmUsageLedger.Bucket> buckets = usageLedger.drain();
        if (buckets.isEmpty()) {
            return;
        }
        List<LlmUsageRepository.Row> rows = new ArrayList<>(buckets.size());
        for (LlmUsageLedger.Bucket b : buckets) {
            rows.add(new LlmUsageRepository.Row(b.day(), b.provider(), b.model(), b.estimated(),
                    b.calls(), b.tokensIn(), b.tokensOut()));
        }
        try {
            usageRepo.addAll(rows);
        } catch (Exception e) {
            log.warn("[METRICS] 日用量落盘失败（这一分钟的账丢了）：{}", e.getMessage());
        }
    }

    private void prune(long minute) {
        long before = minute - KEEP_DAYS * 24L * 60L;
        try {
            trafficRepo.prune(before);
        } catch (Exception e) {
            log.warn("[METRICS] 过期流量清理失败：{}", e.getMessage());
        }
        try {
            llmRepo.prune(before);
        } catch (Exception e) {
            log.warn("[METRICS] 过期模型调用清理失败：{}", e.getMessage());
        }
        try {
            usageRepo.prune(LocalDate.now(AppTime.ZONE).minusDays(KEEP_USAGE_DAYS).toString());
        } catch (Exception e) {
            log.warn("[METRICS] 过期 token 用量清理失败：{}", e.getMessage());
        }
    }
}
