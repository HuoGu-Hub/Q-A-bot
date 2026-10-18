package com.example.qqbot.command;

import java.util.Map;

/**
 * 渲染上下文 —— 模板取值只认它，不认事件的形状。
 *
 * <h2>为什么要有它</h2>
 * 变量渲染原先只接受 {@code OneBotEvent}。但定时发送没有入站事件，
 * 它只有任务行里的群号、创建者与参数。若为此再写一个模板引擎，
 * 「同名变量在两处含义不同」只是时间问题。
 *
 * <p>收成这个记录之后：即时命令从事件构造它，扫描器从任务行构造它，
 * 渲染实现仍然只有 {@link VariableRenderer} 一份。
 *
 * @param userId    触发者 QQ 号
 * @param groupId   群号；私聊为 0
 * @param group     是不是群聊
 * @param nickname  触发者昵称
 * @param argsText  命令尾部的原文，供老的 {@code {args}} / {@code {args.N}}
 * @param namedArgs 解析出来的具名参数，供 {@code {arg.名称}}
 * @param text      摘掉参数段之后的自由文本，供 {@code {text}}
 * @param preview   预览模式：不查真实统计，用占位值
 */
public record RenderContext(
        Long userId,
        Long groupId,
        boolean group,
        String nickname,
        String argsText,
        Map<String, String> namedArgs,
        String text,
        boolean preview) {

    public RenderContext {
        namedArgs = namedArgs == null ? Map.of() : Map.copyOf(namedArgs);
    }

    /** 配置界面的预览：走占位值，不依赖任何真实会话 */
    public static RenderContext sample() {
        return new RenderContext(100000005L, 100000004L, true, "示例用户",
                "张三 18", Map.of(), "示例文本", true);
    }

    /**
     * 没有入站事件时用 —— 定时任务按创建者与会话重建。
     *
     * <p>{@code {user}} 在定时发送里是<b>创建者</b>，不是收消息的人；
     * 这一点必须写在帮助里，否则群友会以为它会变成每个看到的人。
     */
    public static RenderContext scheduled(long userId, long groupId, boolean group, String nickname,
                                          String argsText, Map<String, String> namedArgs, String text) {
        return new RenderContext(userId, groupId, group, nickname, argsText, namedArgs, text, false);
    }
}
