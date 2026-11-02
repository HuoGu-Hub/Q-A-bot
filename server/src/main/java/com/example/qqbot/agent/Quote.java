package com.example.qqbot.agent;

import org.springframework.util.StringUtils;

/**
 * 被引用消息在提示词信封里的样子：正文 + 谁说的。
 *
 * <p>只带正文不行：引用里的「我」是那条消息的作者，当前这句里的「我」是提问的人。
 * 少了作者，模型会把两者当成同一个人 —— 2026-11-02 线上「我是你的谁」
 * 就被答成了引用里的「火沽」。
 *
 * @param text       被引用消息的正文，可能为空
 * @param senderName 被引用消息的作者昵称/群名片，取不到时为空
 * @param fromSelf   被引用的是不是机器人自己之前发的
 */
public record Quote(String text, String senderName, boolean fromSelf) {

    /** 没有引用 */
    public static final Quote NONE = new Quote(null, null, false);

    public boolean hasText() {
        return StringUtils.hasText(text);
    }

    public boolean hasSender() {
        return StringUtils.hasText(senderName);
    }
}
