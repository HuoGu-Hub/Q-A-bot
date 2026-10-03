package com.example.qqbot.persistence;

/**
 * 数据库本身的配置 —— **持久层自己声明的只读视图**，由 {@code config.PersistenceProperties} 实现。
 *
 * <h2>为什么持久层也走这套</h2>
 * 2026-10-02 之前这两个键是 {@code app.qa.db} / {@code app.qa.enabled}，而读它们的
 * {@code SqliteDatabase} 在 {@code persistence} 包 —— 一个通用基础设施包直接 import
 * 了 {@code config.QaProperties}。改名到 {@code app.persistence.*} 之后顺手把依赖方向
 * 也摆正：<b>持久层声明它要什么（库路径 + 开关），装配层去满足</b>。
 *
 * <p>于是护栏「业务包不得直接依赖 config」可以把 {@code persistence} 也纳入 ——
 * 至此除了 {@code config} 自己和组合根 {@code QqbotServerApplication}，
 * <b>没有任何包读 config</b>。
 *
 * <p>⚠️ 接口而不是 record：配置是热生效的（{@code SettingsService} 就地改字段、
 * 各 Bean 长期持引用实时读），写成快照就再也看不到后台的修改，而且不报错。
 *
 * <p><b>方法名保留 {@code isXxx}/{@code getXxx}</b>：这样 {@code PersistenceProperties}
 * 一行都不用改就能实现它，也就不存在"适配器写漏一项"的可能。
 *
 * <p>完整理由与粒度说明见 {@code command.CommandPolicy}（债务⑥ 的模板样例）。
 */
public interface PersistencePolicy {

    /**
     * 数据库总开关。关掉后**不开库**，所有依赖库的功能都会降级（不是报错，是"没有数据"）。
     *
     * <p>只用于调试/排障。
     */
    boolean isEnabled();

    /** SQLite 文件路径（相对路径以程序工作目录为基准） */
    String getDb();
}
