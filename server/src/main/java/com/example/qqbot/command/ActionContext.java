package com.example.qqbot.command;

/**
 * 动作的执行上下文 —— 动作能看到的全部外部信息。
 *
 * <p><b>刻意不含发送器</b>：动作只负责算出一句话，发出去由 {@link
 * com.example.qqbot.router.CommandHandler} 统一做。动作一旦自己能发消息，
 * 出站过滤、分片、节流就会有第二条路径绕过，那是铁律②要挡的事。
 *
 * @param userId  触发者 QQ 号
 * @param groupId 群号；私聊为 0
 * @param group   是不是群聊
 * @param role    归一化之后的角色
 * @param trigger 命中的命令路径
 * @param text    摘掉参数段之后的自由文本
 */
public record ActionContext(
        long userId,
        long groupId,
        boolean group,
        CommandRole role,
        String trigger,
        String text) {

    public ActionContext {
        role = role == null ? CommandRole.MEMBER : role;
        text = text == null ? "" : text;
    }
}
