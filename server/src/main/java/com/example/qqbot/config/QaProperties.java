package com.example.qqbot.config;

import com.example.qqbot.qa.QaPolicy;
import com.example.qqbot.qa.Report;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 问答记录与统计的配置绑定 —— 对应 application.yml 里的 {@code app.qa.*}
 *
 * <p><b>核心策略（已定）：</b>
 * <ul>
 *   <li><b>原始层</b>（问题原文 / 回答原文）—— 有保留期，到期整行删除</li>
 *   <li><b>派生层</b>（时间、检索指标、关键词、标注结论）—— <b>永久保留</b></li>
 * </ul>
 * 所以"删原文"和"留统计"不冲突：统计需要的字段本来就在另一张表里，
 * 不需要先跑一遍归档聚合。
 *
 * <h2>2026-10-02：{@code Report} 搬去了 {@code qa} 包</h2>
 * 它是<b>问答统计自己的领域词汇</b>，只是碰巧从 yml 绑定过来。
 * 搬迁是**纯搬运**：yml 的键一个都没变，绑定关系也没变。
 *
 * <p>业务侧只读视图见 {@link QaPolicy} —— 业务包只依赖它，不依赖本类。
 *
 * <h2>⚠️ {@code db} / {@code enabled} 是「借来的」键</h2>
 * 键名是 {@code app.qa.*}，但真正的使用者是 {@code persistence.SqliteDatabase}。
 * 它们该叫 {@code app.persistence.*} —— 改名会动到现网配置，单独一步做。
 */
@ConfigurationProperties(prefix = "app.qa")
public class QaProperties implements QaPolicy {

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

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String getDb() {
        return db;
    }

    public void setDb(String db) {
        this.db = db;
    }

    @Override
    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    @Override
    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    @Override
    public int getFlushBatchSize() {
        return flushBatchSize;
    }

    public void setFlushBatchSize(int flushBatchSize) {
        this.flushBatchSize = flushBatchSize;
    }

    @Override
    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }

    @Override
    public Report getReport() {
        return report;
    }

    public void setReport(Report report) {
        this.report = report;
    }
}
