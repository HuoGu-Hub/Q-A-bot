package com.example.qqbot.command;

import java.util.List;

/**
 * 一条指令配置。
 *
 * <p><b>两类指令</b>（{@link #kind}）：
 * <ul>
 *   <li>{@code template} —— <b>话术</b>：渲染 {@code reply} 模板直接发出去，
 *       <b>不调模型、不计费</b>，所以跑在 Guard 之前秒回（{@code /help} 不该等 3 秒）。</li>
 *   <li>{@code agent} —— <b>智能问答</b>：把参数当成"问题"交给大模型，
 *       会走 {@code Guard} 与<b>成本预算</b>。{@code /联网 今天什么版本} 就是这一类。</li>
 * </ul>
 *
 * <p><b>参数</b>：两种指令都支持 {@code /触发词 参数…}。模板里用 {@code {args}}
 * 取全部参数、{@code {args.1}} 取第 1 个 —— 小活动（{@code /报名 张三}）靠它。
 *
 * @param id          主键
 * @param trigger     触发词（**不含** "/" 前缀）。首词完全相等才触发，后面可跟参数
 * @param reply       回复模板（agent 类时它是"没带参数时的用法提示"）
 * @param description 用途说明 —— {cmd.list} 会用它渲染列表
 * @param scope       作用范围 all / group / private
 * @param groupIds    scope=group 时限定的群
 * @param minRole     最低权限 member / admin / owner
 * @param enabled     是否启用
 * @param sortOrder   排序（列表展示与 {cmd.list} 的顺序）
 * @param builtin     内置命令不可删除
 * @param kind        指令类型 template / agent（空 = template）
 * @param mode        agent 类的回答模式：kb = 走知识库，none = 不用知识库（空 = kb）
 */
public record BotCommand(
        long id,
        String trigger,
        String reply,
        String description,
        String scope,
        List<Long> groupIds,
        String minRole,
        boolean enabled,
        int sortOrder,
        boolean builtin,
        String kind,
        String mode) {

    public static final String KIND_TEMPLATE = "template";
    public static final String KIND_AGENT = "agent";

    /** 走知识库（默认） */
    public static final String MODE_KB = "kb";
    /** 不用知识库，纯模型回答 */
    public static final String MODE_NONE = "none";

    /** 是不是"要调模型"的那一类 */
    public boolean isAgent() {
        return KIND_AGENT.equalsIgnoreCase(kind == null ? "" : kind.trim());
    }

    /** 归一化 kind —— 数据库里可能是 null / 空串（老数据） */
    public String kindName() {
        return isAgent() ? KIND_AGENT : KIND_TEMPLATE;
    }

    /** 归一化 mode —— 只有 agent 类才有意义 */
    public String modeName() {
        return MODE_NONE.equalsIgnoreCase(mode == null ? "" : mode.trim()) ? MODE_NONE : MODE_KB;
    }

    /** 在某个群里能不能用 */
    public boolean appliesToGroup(long groupId) {
        return switch (scope) {
            case "private" -> false;
            case "group" -> groupIds == null || groupIds.isEmpty() || groupIds.contains(groupId);
            default -> true;
        };
    }

    /** 私聊能不能用 */
    public boolean appliesToPrivate() {
        return !"group".equals(scope);
    }

    /**
     * 完整命令形式，如 {@code "/help"}。
     *
     * <p>agent 类要带参数才有意义，所以列表里显示成 {@code "/联网 <问题>"} ——
     * 否则群友看到 {@code /联网} 会直接发出去，得到一句用法提示。
     */
    public String display() {
        return isAgent() ? "/" + trigger + " <问题>" : "/" + trigger;
    }
}
