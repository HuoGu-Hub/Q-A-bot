package com.example.qqbot.voice;

import com.example.qqbot.onebot.outbound.OutboundSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 发语音条的**业务入口**：读文件 → 交给 {@link VoiceProcessor} 加工 → 交给 {@link OutboundSender} 发出。
 *
 * <h2>它为什么不自己碰协议</h2>
 * 全项目只有 {@code onebot/} 认识消息段、只有 {@link OutboundSender} 能发消息。
 * 这个类存在的价值是把"音频从哪来、要怎么裁"与"怎么发"分开：
 * 将来接 TTS 时，TTS 只是换个音频来源，发送那一半一行都不用改。
 *
 * <h2>和图片那套的对应关系</h2>
 * <pre>
 *   ImageLibrary（解析 key）→ OutboundImage → OutboundSender
 *   VoiceSender（读文件/加工）→ mp3 字节      → OutboundSender
 * </pre>
 * 区别是语音多了一道 ffmpeg 加工，因为"发哪一段、什么调"是语音特有的需求。
 *
 * <h2>失败策略</h2>
 * 一律抛 {@link VoiceException}：语音是用户主动要听的内容，
 * 发不出去却返回成功，比直接报错更难查。
 */
@Component
public class VoiceSender {

    private static final Logger log = LoggerFactory.getLogger(VoiceSender.class);

    /**
     * 源文件大小上限（字节）。32MB 足够放一首无损歌，
     * 再大基本可以断定是拿错文件了 —— 读进内存之前就该拦住。
     */
    private static final long MAX_SOURCE_BYTES = 32L * 1024 * 1024;

    private final VoicePolicy policy;
    private final VoiceProcessor processor;
    private final OutboundSender outbound;

    public VoiceSender(VoicePolicy policy, VoiceProcessor processor, OutboundSender outbound) {
        this.policy = policy;
        this.processor = processor;
        this.outbound = outbound;
    }

    /** 发送结果。调用方拿它记日志、回执，或者在前端显示"已发 29 秒" */
    public record Sent(long messageId, int bytes, double seconds) {
    }

    /** 把本地音频文件原样发到群里（只转码，不裁剪） */
    public Sent sendToGroup(long groupId, Path file) {
        return sendToGroup(groupId, file, VoiceOptions.NONE);
    }

    /** 把本地音频文件发到群里，可以裁一段、变速、变调 */
    public Sent sendToGroup(long groupId, Path file, VoiceOptions options) {
        requireGroup(groupId);
        return send(true, groupId, read(file), extensionOf(file), options);
    }

    /** 把内存里的音频字节发到群里。{@code format} 是扩展名提示，例如 mp3 / wav */
    public Sent sendToGroup(long groupId, byte[] audio, String format, VoiceOptions options) {
        requireGroup(groupId);
        return send(true, groupId, audio, format, options);
    }

    /** 私聊版，参数与群聊完全一致 */
    public Sent sendToPrivate(long userId, Path file) {
        return sendToPrivate(userId, file, VoiceOptions.NONE);
    }

    public Sent sendToPrivate(long userId, Path file, VoiceOptions options) {
        requireUser(userId);
        return send(false, userId, read(file), extensionOf(file), options);
    }

    public Sent sendToPrivate(long userId, byte[] audio, String format, VoiceOptions options) {
        requireUser(userId);
        return send(false, userId, audio, format, options);
    }

    private Sent send(boolean group, long targetId, byte[] audio, String format, VoiceOptions options) {
        if (!policy.isEnabled()) {
            throw new VoiceException("语音发送已关闭（app.voice.enabled=false）");
        }
        if (audio == null || audio.length == 0) {
            throw new VoiceException("音频内容为空");
        }
        VoiceProcessor.Encoded encoded = processor.encode(audio, format, options);

        int maxBytes = policy.getMaxKb() * 1024;
        if (maxBytes > 0 && encoded.audio().length > maxBytes) {
            throw new VoiceException(String.format(Locale.ROOT,
                    "加工后的语音 %.1f KB，超过上限 %d KB（app.voice.max-kb）",
                    encoded.audio().length / 1024.0, policy.getMaxKb()));
        }

        long messageId = group
                ? outbound.sendVoiceToGroup(targetId, encoded.audio())
                : outbound.sendVoiceToPrivate(targetId, encoded.audio());
        log.info("[VOICE] 已发送语音给{} {}：{} 字节，{} 秒，message_id={}",
                group ? "群" : "QQ", targetId, encoded.audio().length,
                String.format(Locale.ROOT, "%.1f", encoded.seconds()), messageId);
        return new Sent(messageId, encoded.audio().length, encoded.seconds());
    }

    private static byte[] read(Path file) {
        if (file == null) {
            throw new VoiceException("音频文件为空");
        }
        try {
            if (!Files.isRegularFile(file)) {
                throw new VoiceException("找不到音频文件：" + file);
            }
            long size = Files.size(file);
            if (size > MAX_SOURCE_BYTES) {
                throw new VoiceException(String.format(Locale.ROOT,
                        "音频文件太大：%.1f MB，上限 %d MB", size / 1024.0 / 1024.0,
                        MAX_SOURCE_BYTES / 1024 / 1024));
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new VoiceException("读取音频文件失败：" + e.getMessage(), e);
        }
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static void requireGroup(long groupId) {
        if (groupId <= 0) {
            throw new VoiceException("群号不合法：" + groupId);
        }
    }

    private static void requireUser(long userId) {
        if (userId <= 0) {
            throw new VoiceException("QQ 号不合法：" + userId);
        }
    }
}
