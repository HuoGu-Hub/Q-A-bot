package com.example.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 问答记录与统计的配置，对应 application.yml 里的 app.qa.*
 *
 * <p><b>核心策略（已定）：</b>
 * <ul>
 *   <li><b>原始层</b>（问题原文 / 回答原文）—— 有保留期，到期整行删除</li>
 *   <li><b>派生层</b>（时间、检索指标、关键词、标注结论）—— <b>永久保留</b></li>
 * </ul>
 * 所以"删原文"和"留统计"不冲突：统计需要的字段本来就在另一张表里，
 * 不需要先跑一遍归档聚合。
 */
@ConfigurationProperties(prefix = "app.qa")
public class QaProperties {

    /** 记录总开关。关掉后完全不写库（回答流程也完全不受影响） */
    private boolean enabled = true;

    /** SQLite 文件路径（相对路径以程序工作目录为基准） */
    private String db = "./data/qa/qa.sqlite";

    /**
     * 原文保留天数。<b>0 = 永不删除</b>。
     *
     * <p>只影响 qa_raw（问题原文/引用原文/回答原文）；
     * qa_stat 和 qa_keyword 永远不删。
     */
    private int retentionDays = 180;

    /** 异步队列容量。满了就丢弃并计数 —— 宁可丢记录，也不能积压内存 */
    private int queueCapacity = 2000;

    /** 单次批量落盘的条数 */
    private int flushBatchSize = 50;

    /** 清理任务的 cron（默认每天凌晨 4:30） */
    private String cleanupCron = "0 30 4 * * *";

    private Report report = new Report();

    /**
     * 报表配置（S2）。和语料构建一样**默认关闭**，只有显式打开才跑：
     *
     * <pre>
     * mvn spring-boot:run -Dspring-boot.run.arguments="--app.qa.report.enabled=true --spring.main.web-application-type=none"
     * </pre>
     */
    public static class Report {

        private boolean enabled = false;

        /** 统计最近多少天。0 = 全部 */
        private int days = 30;

        /** 看哪一段：all / overview / keywords / misses / cosine / sources */
        private String section = "all";

        /** 排行榜取前几名 */
        private int topN = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDays() {
            return days;
        }

        public void setDays(int days) {
            this.days = days;
        }

        public String getSection() {
            return section;
        }

        public void setSection(String section) {
            this.section = section;
        }

        public int getTopN() {
            return topN;
        }

        public void setTopN(int topN) {
            this.topN = topN;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDb() {
        return db;
    }

    public void setDb(String db) {
        this.db = db;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getFlushBatchSize() {
        return flushBatchSize;
    }

    public void setFlushBatchSize(int flushBatchSize) {
        this.flushBatchSize = flushBatchSize;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }

    public Report getReport() {
        return report;
    }

    public void setReport(Report report) {
        this.report = report;
    }
}
