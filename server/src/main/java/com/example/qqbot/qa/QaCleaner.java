package com.example.qqbot.qa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期原文的清理任务。
 *
 * <p><b>只删 qa_raw（问题/引用/回答原文），qa_stat 和 qa_keyword 一行不动</b> ——
 * 这是「原文定期删、加工结果永久留」这条策略的执行者。
 *
 * <p>删除不可逆，所以：支持 dry-run（见 QaStore#purgeRaw）、每次执行都记日志、重复执行无副作用。
 */
@Component
public class QaCleaner {

    private static final Logger log = LoggerFactory.getLogger(QaCleaner.class);

    private final QaPolicy props;
    private final QaStore store;

    public QaCleaner(QaPolicy props, QaStore store) {
        this.props = props;
        this.store = store;
    }

    @Scheduled(cron = "${app.qa.cleanup-cron:0 30 4 * * *}")
    public void scheduledCleanup() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            if (props.getRetentionDays() <= 0) {
                log.debug("[QA] 原文保留期为 0（永不删除），跳过清理");
                return;
            }
            int removed = store.purgeRaw(props.getRetentionDays(), false);
            if (removed == 0) {
                log.info("[QA] 没有过期原文需要清理（保留 {} 天）", props.getRetentionDays());
            }
        } catch (Exception e) {
            log.warn("[QA] 清理任务异常（不影响机器人）：{}", e.getMessage());
        }
    }
}
