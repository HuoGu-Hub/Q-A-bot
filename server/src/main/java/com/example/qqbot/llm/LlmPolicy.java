package com.example.qqbot.llm;

import java.util.List;
import java.util.Map;

/**
 * 大模型配置的**根字段** —— **业务侧自己声明的只读视图**，由 {@code config.LlmProperties} 实现。
 *
 * <h2>为什么只有根字段，而 {@link Provider} / {@link ReplyStyle} 是「搬过来的类」</h2>
 * 判据是**这个东西是不是完整的领域词汇**：
 * <ul>
 *   <li>{@link Provider}（一个厂商的接入参数，11 个字段）与 {@link ReplyStyle}（回复风格）
 *       是完整的领域对象 —— 所以**整个类挪进 {@code llm} 包**，零语义变化。</li>
 *   <li>根上这 5 项（默认 provider / 降级链 / 系统提示词文件 / provider 表 / 回复风格）
 *       是零散开关，搬不动（yml 的键就在根上）—— 所以只**声明一个只读接口**。</li>
 * </ul>
 * 一句话：**能整体搬走就搬走，搬不动就声明只读视图。**
 *
 * <p>⚠️ 接口而不是 record：配置是热生效的（{@code SettingsService} 就地改字段、
 * 各 Bean 长期持引用实时读），写成快照就再也看不到后台的修改，而且不报错。
 *
 * <p><b>方法名保留 {@code getXxx}</b>：这样 {@code LlmProperties} 一行都不用改就能实现它，
 * 也就不存在"适配器写漏一项"的可能。想反向转回可变对象必须 import {@code config} ——
 * 那会被 ArchUnit 的「业务包不得直接依赖 config」当场抓住。
 */
public interface LlmPolicy {

    /** 默认用哪个 provider（没配或配错时会自动退到 fallback） */
    String getDefaultProvider();

    /** 降级链：默认 provider 失败后按顺序试 */
    List<String> getFallbackProviders();

    /** 系统提示词文件（默认 AGENTS.md） */
    String getSystemPromptFile();

    /** provider 名 → 该厂商的接入参数 */
    Map<String, Provider> getProviders();

    /** 回复风格（表现层约束，和 AGENTS.md 那份「安全底座」刻意分开） */
    ReplyStyle getReplyStyle();
}
