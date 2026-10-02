package com.example.qqbot.logs;

/**
 * 日志界面的配置 —— **业务侧自己声明的只读视图**，由 {@code config.LogProperties} 实现。
 *
 * <h2>为什么业务包不直接注入 LogProperties</h2>
 * 那个类在 {@code config} 包里。业务包依赖 config 的后果：
 * <ul>
 *   <li>{@code config} 变成"上帝包" —— 谁都依赖它，它谁都动不了；</li>
 *   <li>构造签名看不出这个类关心哪几项（{@code LogProperties} 有 5 项，
 *       而 {@code LogMasker} 只用其中 1 项）；</li>
 *   <li>测试要为一个开关造出整个配置对象。</li>
 * </ul>
 * 收成「业务侧声明接口、config 侧实现它」之后，业务包对 config 的 import 就没有了，
 * 依赖方向也变成 {@code config → logs}（装配层去满足业务声明）。
 *
 * <h2>⚠️ 为什么是接口而不是 record 快照</h2>
 * 配置是**热生效**的：{@code SettingsService} 改值时是**就地改字段**，
 * 各个 Bean 长期持有引用、调用时实时读。
 * 写成 {@code record LogPolicy(boolean enabled, ...)} 就是在装配时<b>拷了一份值</b> ——
 * 后台把日志关了却还在推流，而且不报错。
 *
 * <p><b>方法名保留 {@code isXxx}/{@code getXxx}</b>：这样 {@code LogProperties}
 * 一行都不用改就能实现它，也就不存在"适配器写漏一项"的可能。
 * 想反向转回可变对象必须 import {@code config} —— 那会被 ArchUnit 的
 * 「业务包不得直接依赖 config」当场抓住。
 */
public interface LogPolicy {

    /** 日志功能总开关 */
    boolean isEnabled();

    /** 内存里保留多少条日志 */
    int getBufferSize();

    /** SSE 连接最长存活（分钟），防止连接泄漏 */
    int getStreamTimeoutMinutes();

    /** 同时最多几个日志流连接 */
    int getMaxStreams();

    /** 是否对日志做脱敏（日志页可能被截图外发，所以默认开） */
    boolean isMaskSensitive();
}
