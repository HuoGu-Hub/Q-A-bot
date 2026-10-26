package com.example.qqbot.voice;

import com.example.qqbot.onebot.model.OneBotEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把待发清单变成真正的语音条。
 *
 * <p>路由只管"什么时候发"，这里管"发什么、发不出去怎么办"。
 * 一条失败不影响后面的，也不影响已经发出去的文字 —— 语音是锦上添花。
 */
@Component
public class VoiceDelivery {

    private static final Logger log = LoggerFactory.getLogger(VoiceDelivery.class);

    private final VoiceLibrary library;
    private final SpeechSynthesizer synthesizer;
    private final VoiceSender sender;

    public VoiceDelivery(VoiceLibrary library, SpeechSynthesizer synthesizer, VoiceSender sender) {
        this.library = library;
        this.synthesizer = synthesizer;
        this.sender = sender;
    }

    /**
     * 依次发出清单里的语音。**不抛异常**：调用方正在发回复，不能因为语音失败把回复带崩。
     */
    public void deliver(OneBotEvent event, List<MediaOutbox.Intent> intents) {
        if (event == null || intents == null || intents.isEmpty()) {
            return;
        }
        for (MediaOutbox.Intent intent : intents) {
            try {
                switch (intent.kind()) {
                    case ASSET -> deliverAsset(event, intent);
                    case SPEECH -> deliverSpeech(event, intent);
                    default -> log.warn("[VOICE] 未知的待发类型：{}", intent.kind());
                }
            } catch (Exception e) {
                log.warn("[VOICE] 发送语音失败（{}）：{}", intent.label(), e.getMessage());
            }
        }
    }

    private void deliverAsset(OneBotEvent event, MediaOutbox.Intent intent) {
        VoiceAsset asset = library.get(intent.assetId());
        if (asset == null) {
            log.warn("[VOICE] 素材 #{} 已经不存在，跳过", intent.assetId());
            return;
        }
        send(event, library.pathOf(asset).toString(), asset.title());
    }

    private void deliverSpeech(OneBotEvent event, MediaOutbox.Intent intent) {
        if (!synthesizer.isAvailable()) {
            log.warn("[VOICE] TTS 不可用，跳过合成：{}", intent.text());
            return;
        }
        byte[] audio = synthesizer.synthesize(intent.text());
        if (event.isGroupMessage()) {
            sender.sendToGroup(event.getGroupId(), audio, "mp3", VoiceOptions.NONE);
        } else {
            sender.sendToPrivate(event.getUserId(), audio, "mp3", VoiceOptions.NONE);
        }
        log.info("[VOICE] 已发送合成语音给 {}：{} 字", target(event), intent.text().length());
    }

    private void send(OneBotEvent event, String path, String title) {
        if (event.isGroupMessage()) {
            sender.sendToGroup(event.getGroupId(), java.nio.file.Path.of(path));
        } else {
            sender.sendToPrivate(event.getUserId(), java.nio.file.Path.of(path));
        }
        log.info("[VOICE] 已发送素材「{}」给 {}", title, target(event));
    }

    private static String target(OneBotEvent event) {
        return event.isGroupMessage() ? "群 " + event.getGroupId() : "QQ " + event.getUserId();
    }
}
