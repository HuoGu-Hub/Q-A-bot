package com.example.qqbot.eval;

import com.example.qqbot.persistence.EvalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 评测明细清理。
 *
 * <p>只删过程类明细：span、检索快照、工具调用。判定、失败标签、批次统计都留着 ——
 * 趋势与报告靠它们，删了历史就断了。
 *
 * <p>执行记录本身也保留：一条 run 的答案与判定是"当时到底发生了什么"的唯一证据。
 */
@Component
public class EvalCleaner {

    private static final Logger log = LoggerFactory.getLogger(EvalCleaner.class);

    /** 与数据保留策略一致：明细 90 天，结论永久 */
    static final int DETAIL_RETENTION_DAYS = 90;

    private final EvalRepository repo;

    public EvalCleaner(EvalRepository repo) {
        this.repo = repo;
    }

    @Scheduled(cron = "${app.eval.cleanup-cron:0 10 4 * * *}")
    public void purge() {
        if (!repo.isAvailable()) {
            return;
        }
        String cutoff = Instant.now().minus(DETAIL_RETENTION_DAYS, ChronoUnit.DAYS).toString();
        try {
            int removed = repo.purgeDetailBefore(cutoff);
            if (removed > 0) {
                log.info("[EVAL] 清理 {} 条过期明细（截止 {}）", removed, cutoff);
            }
        } catch (RuntimeException e) {
            log.warn("[EVAL] 清理过期明细失败：{}", e.getMessage());
        }
    }
}
