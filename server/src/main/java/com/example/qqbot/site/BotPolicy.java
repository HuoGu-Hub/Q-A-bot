package com.example.qqbot.site;

/**
 * 机器人的身份配置 —— **业务侧自己声明的只读视图**，由 {@code config.BotProperties} 实现。
 *
 * <h2>为什么放在 {@code site} 包（这里值得解释一句）</h2>
 * 它和 {@link SitePolicy} 是<b>同一类东西</b>：都是"对外展示的文本"。
 * {@code SitePolicy} 是群的信息（群名/群号/群说明/加群提示），本接口是机器人的信息
 * （目前只有昵称一个字段）。两个消费者也印证了这一点：
 * <ul>
 *   <li>{@code publicapi.PublicController} —— 公开站同时发布这两组信息；</li>
 *   <li>{@code command.VariableRenderer} —— 渲染 {@code {bot}} 这个<b>展示</b>变量。</li>
 * </ul>
 * 放在 {@code site} 而不是 {@code command}，是因为"机器人的展示身份"比"指令系统"更宽，
 * 而 {@code site} 已经在管"对外展示什么"。
 *
 * <p>⚠️ 注意：{@code AGENTS.md} 里的名字是**写死在提示词里的** ——
 * 那是模型人格的一部分，不适合运行时替换（而且提示词是纯文本文件）。
 * 改提示词里的名字需要手动改 AGENTS.md。
 *
 * <p>⚠️ 接口而不是 record：配置是热生效的（{@code SettingsService} 就地改字段、
 * 各 Bean 长期持引用实时读），写成快照就再也看不到后台的修改，而且不报错。
 *
 * <p>完整理由与粒度说明见 {@code command.CommandPolicy}（债务⑥ 的模板样例）。
 */
public interface BotPolicy {

    /** 机器人昵称 —— 用于 {bot} 变量、限流话术等 */
    String getName();

    /** 运营方 / 组织名 —— 公开站「关于」页展示（空则不显示那一行） */
    String getOrg();

    /** 技术支持署名 —— 公开站「关于」页展示（空则不显示那一行） */
    String getAuthor();
}
