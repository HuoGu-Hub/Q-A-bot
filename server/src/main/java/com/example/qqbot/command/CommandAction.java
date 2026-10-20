package com.example.qqbot.command;

import java.util.List;

/**
 * 一个动作 —— {@code kind=action} 的命令最终落到这里。
 *
 * <h2>为什么是注册表而不是 switch</h2>
 * 「动作」是这个系统里唯一会长出新分支的地方。写成 {@code switch (cmd.action())}
 * 的话，每加一个功能都要改分派代码，而分派代码是两个功能共用的 ——
 * 于是每加一个功能就有机会碰坏另一个。收成接口之后，新增功能 = 新增一个类。
 *
 * <h2>实现要守的两条</h2>
 * <ol>
 *   <li><b>必须在毫秒级返回</b>。动作跑在指令路径上，群友在等回执。
 *       要发送的定时任务在这里只落库，「到点发」是另一个组件的事。</li>
 *   <li><b>返回的就是最终文本</b>。它不会被再次渲染 —— 动作拿到的参数
 *       已经展开并校验过，再渲染一遍会让群友内容里的 {@code {...}} 被二次展开。</li>
 * </ol>
 */
public interface CommandAction {

    /** 稳定标识，库里 {@code action} 列存的就是它，例：{@code schedule.create} */
    String id();

    /**
     * 给人看的名字，后台下拉里显示这个。
     *
     * <p>没有它的话，管理员看到的是一串英文标识 —— 他不知道该选哪个。
     */
    default String label() {
        return id();
    }

    /** 一句话说清这个动作干了什么，只有真选中时才显示 */
    default String description() {
        return "";
    }

    /**
     * 这个动作最自然的命令路径。
     *
     * <p>新建命令时选了这个动作，路径就自动填上 —— 新手不用先搞懂"路径怎么分层"
     * 才能用上一个功能。填空是可改的，懂的人照样能改成别的。
     */
    default String defaultPath() {
        return "";
    }

    /**
     * 这个动作期望的参数表。
     *
     * <p>只用于<b>内置命令的种子</b>与后台的「载入该动作参数」按钮；
     * 运行时以库里那条命令自己的参数表为准 —— 管理员有权增删可选参数。
     */
    List<ParamSpec> params();

    /**
     * 执行。
     *
     * @param ctx  执行上下文
     * @param args 已经校验与转换过的参数
     * @return 要发回群里的最终文本；返回空串表示不回话
     * @throws ActionException 可预期的失败，消息会原样回给群友
     */
    String execute(ActionContext ctx, CommandArgs args) throws ActionException;
}
