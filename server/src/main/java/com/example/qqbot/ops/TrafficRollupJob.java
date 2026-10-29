package com.example.qqbot.ops;

import com.example.qqbot.persistence.TrafficRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟把 {@link TrafficMetrics} 的读数落一行到库。
 *
 * <h2>为什么是 5 秒轮询、按分钟边界落盘</h2>
 * 直接 {@code fixedDelay = 60s} 也能跑，但那一分钟是"从启动时刻算起的 60 秒"，
 * 跨着整分钟边界，图上每个点都不是整分钟，看趋势会别扭。
 * 这里 5 秒看一次表，跨分钟才落 —— 落的是**刚刚过去的那一分钟**，
 * 时间戳与数据对齐。代价是每分钟多 11 次空转，可以忽略。
 *
 * <h2>失败怎么办</h2>
 * 采集本身不能影响业务，落盘失败也只记日志、不重试：
 * 丢一分钟的流量数据，远好过在调度线程里抛异常。
 */
@Component
public class TrafficRollupJob {

    private static final Logger log = LoggerFactory.getLogger(TrafficRollupJob.class);

    /** 保留 7 天。再久没人翻，而且表会开始拖慢全量查询 */
    private static final int KEEP_DAYS = 7;

    /** 每落 60 行（约一小时）清一次过期行，不必每分钟都删 */
    private static final int PRUNE_EVERY = 60;

    private final TrafficMetrics metrics;
    private final TrafficRepository repo;

    /** 上一分钟（epoch 分钟）。-1 表示还没对齐过，第一次只对表不落盘 */
    private long lastMinute = -1L;
    private int flushed;

    public TrafficRollupJob(TrafficMetrics metrics, TrafficRepository repo) {
        this.metrics = metrics;
        this.repo = repo;
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
        TrafficMetrics.Snapshot snapshot = metrics.drain();
        if (snapshot != null) {
            save(lastMinute, snapshot);
        }
        lastMinute = minute;
    }

    private void save(long minute, TrafficMetrics.Snapshot snapshot) {
        try {
            repo.save(new TrafficRepository.Row(minute, snapshot.requests(), snapshot.api(),
                    snapshot.errors(), snapshot.p50(), snapshot.p95(), snapshot.max()));
        } catch (Exception e) {
            log.warn("[METRICS] 流量落盘失败（这一分钟的数据丢了）：{}", e.getMessage());
            return;
        }
        if (++flushed % PRUNE_EVERY == 0) {
            try {
                repo.prune(minute - KEEP_DAYS * 24L * 60L);
            } catch (Exception e) {
                log.warn("[METRICS] 过期流量清理失败：{}", e.getMessage());
            }
        }
    }
}
