package com.example.qqbot.qa;

/**
 * 问答记录与统计的配置 —— **业务侧自己声明的只读视图**，由 {@code config.QaProperties} 实现。
 *
 * <h2>核心策略（已定）</h2>
 * <ul>
 *   <li><b>原始层</b>（问题原文 / 回答原文）—— 有保留期，到期整行删除</li>
 *   <li><b>派生层</b>（时间、检索指标、关键词、标注结论）—— <b>永久保留</b></li>
 * </ul>
 * 所以"删原文"和"留统计"不冲突：统计需要的字段本来就在另一张表里。
 *
 * <p>⚠️ 接口而不是 record：配置是热生效的（{@code SettingsService} 就地改字段、
 * 各 Bean 长期持引用实时读），写成快照就再也看不到后台的修改，而且不报错。
 *
 * <p><b>方法名保留 {@code isXxx}/{@code getXxx}</b>：这样 {@code QaProperties}
 * 一行都不用改就能实现它。想反向转回可变对象必须 import {@code config} ——
 * 那会被 ArchUnit 的「业务包不得直接依赖 config」当场抓住。
 *
 * <p>{@link Report} 本身也**搬到了本包**。
 *
 * <h2>⚠️ {@code getDb()} / {@code isEnabled()} 是「借来的」键</h2>
 * 键名是 {@code app.qa.db} / {@code app.qa.enabled}，但真正的使用者是
 * {@code persistence.SqliteDatabase}（它是全库唯一的连接持有者）。
 * 它们该叫 {@code app.persistence.*} —— 改名会动到现网配置，单独一步做。
 * 在那之前 {@code persistence} 不在护栏名单里（见交付说明的债务⑥ 一节）。
 *
 * <p>完整理由与粒度说明见 {@code command.CommandPolicy}（债务⑥ 的模板样例）。
 */
public interface QaPolicy {

    /** 记录总开关。关掉后完全不写库（回答流程也完全不受影响） */
    boolean isEnabled();

    /** SQLite 文件路径（相对路径以程序工作目录为基准） */
    String getDb();

    /** 原文保留天数。<b>0 = 永不删除</b>（只影响 qa_raw） */
    int getRetentionDays();

    /** 异步队列容量。满了就丢弃并计数 —— 宁可丢记录，也不能积压内存 */
    int getQueueCapacity();

    /** 单次批量落盘的条数 */
    int getFlushBatchSize();

    /** 清理任务的 cron（默认每天凌晨 4:30） */
    String getCleanupCron();

    /** 报表配置（默认关闭，只有显式打开才跑） */
    Report getReport();
}
