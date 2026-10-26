package com.example.qqbot.voice;

/**
 * 语音加工或发送失败。
 *
 * <p>用运行时异常而不是返回 null：调用方（命令、后台、将来的 TTS）
 * 需要知道"这条语音没发出去"，而不是安静地少一条。
 */
public class VoiceException extends RuntimeException {

    public VoiceException(String message) {
        super(message);
    }

    public VoiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
