package com.example.qqbot.voice;

import java.util.List;

/**
 * 把「模型安排了什么」与「这条回复是否必须有语音」合成最终的待发清单。
 *
 * <h2>兜底为什么是 TTS 回答正文</h2>
 * 关键词命中的语义是"这条回复要用语音"。模型可能忘了调工具 ——
 * 那就把它的回答截一段念出来，而不是静默退回纯文字。
 *
 * <h2>为什么截到 80 字</h2>
 * 一段语音超过 20 秒在群里已经很难听完。80 字大约是 15 秒，
 * 既保留了回答的主干，又不至于变成念课文。
 */
public final class VoiceReplyPlanner {

    /** 兜底合成时最多念多少字 */
    public static final int FALLBACK_MAX_CHARS = 80;

    private VoiceReplyPlanner() {
    }

    /**
     * @param voiceRequired 关键词是否要求这条回复必须有语音
     * @param staged        模型通过工具安排的清单，可能为空
     * @param replyText     实际要发出去的文字
     */
    public static List<MediaOutbox.Intent> complete(boolean voiceRequired,
                                                    List<MediaOutbox.Intent> staged,
                                                    String replyText) {
        if (staged != null && !staged.isEmpty()) {
            return staged;
        }
        if (!voiceRequired) {
            return List.of();
        }
        String text = replyText == null ? "" : replyText.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        if (text.length() > FALLBACK_MAX_CHARS) {
            text = text.substring(0, FALLBACK_MAX_CHARS);
        }
        return List.of(MediaOutbox.Intent.speech(text));
    }
}
