package com.example.qqbot.command;

import java.util.List;

/**
 * 一条指令配置。
 *
 * <p><b>命令名就是路径</b>：{@code trigger} 里用 {@code /} 分层，{@code set/output}
 * 对应群里写的 {@code /set /output}。匹配时按最长路径吃词，见 {@link CommandMatcher}。
 *
 * <p><b>三类指令</b>（{@link #kind}）：
 * <ul>
 *   <li>{@code template} —— <b>话术</b>：渲染 {@code reply} 直接发出去，
 *       <b>不调模型、不计费</b>，所以跑在 Guard 之前秒回。</li>
 *   <li>{@code agent} —— <b>智能问答</b>：把自由文本当成"问题"交给大模型，
 *       会走 {@code Guard} 与<b>成本预算</b>。</li>
 *   <li>{@code action} —— <b>动作</b>：交给注册的执行器写库或查库，不调模型。</li>
 * </ul>
 *
 * <p><b>参数</b>：{@link #params} 是这条命令的参数表，群里用 {@code #名字-值#} 传值，
 * 模板里用 {@code {arg.名字}} 取值。表为空表示这条命令不吃参数。
 *
 * @param id          主键
 * @param trigger     命令路径，用 {@code /} 分层，不含开头的斜杠
 * @param reply       回复模板；action 类时它是"没带参数时的用法提示"
 * @param description 用途说明 —— {cmd.list} 会用它渲染列表
 * @param scope       作用范围 all / group / private
 * @param groupIds    scope=group 时限定的群
 * @param minRole     最低权限 member / admin / owner
 * @param enabled     是否启用
 * @param sortOrder   排序
 * @param builtin     内置命令不可删除
 * @param kind        指令类型 template / agent / action
 * @param mode        agent 类的回答模式：kb = 走知识库，none = 不用知识库
 * @param params      参数表
 * @param help        手写的单命令帮助；为空则由参数表自动生成
 * @param action      action 类绑定的执行器 id；其余类型为空
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
        String mode,
        List<ParamSpec> params,
        String help,
        String action) {

    public static final String KIND_TEMPLATE = "template";
    public static final String KIND_AGENT = "agent";
    public static final String KIND_ACTION = "action";

    /** 走知识库（默认） */
    public static final String MODE_KB = "kb";
    /** 不用知识库，纯模型回答 */
    public static final String MODE_NONE = "none";

    public BotCommand {
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
        params = params == null ? List.of() : List.copyOf(params);
    }

    /**
     * 归一化 kind。
     *
     * <p>老数据的 {@code kind} 可能是 null 或空串，一律当话术 —— 与加 kind 之前的
     * 行为一致；认不出来的值也回落成话术，而不是当成会调模型的那一类。
     */
    public String kindName() {
        String k = kind == null ? "" : kind.trim();
        if (KIND_AGENT.equalsIgnoreCase(k)) {
            return KIND_AGENT;
        }
        return KIND_ACTION.equalsIgnoreCase(k) ? KIND_ACTION : KIND_TEMPLATE;
    }

    /** 是不是"要调模型"的那一类 */
    public boolean isAgent() {
        return KIND_AGENT.equals(kindName());
    }

    /** 是不是"交给执行器"的那一类 */
    public boolean isAction() {
        return KIND_ACTION.equals(kindName());
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
     * 完整命令形式，如 {@code /help}、{@code /set /output #time-21:30#}。
     *
     * <p>有参数表的命令要把参数摆出来 —— 否则群友看到 {@code /set /output} 直接发出去，
     * 只会收到一句用法提示。路径里每一层都补上斜杠，与群里的写法一致。
     */
    public String display() {
        String pathText = "/" + trigger.replace("/", " /");
        if (!params.isEmpty()) {
            StringBuilder sb = new StringBuilder(pathText);
            for (ParamSpec p : params) {
                String sample = p.example() == null || p.example().isBlank() ? p.name() : p.example();
                sb.append(" #").append(p.name()).append('-').append(sample).append('#');
            }
            return sb.toString();
        }
        return isAgent() ? pathText + " <问题>" : pathText;
    }
}
