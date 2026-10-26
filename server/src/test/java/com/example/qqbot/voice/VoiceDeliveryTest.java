package com.example.qqbot.voice;

import com.example.qqbot.onebot.model.OneBotEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 待发清单到真实发送的翻译层。
 *
 * <p>重点是**失败隔离**：一段素材读不出来，不能让后面那段合成语音也发不出去，
 * 更不能把异常抛回正在发回复的路由。
 */
class VoiceDeliveryTest {

    private VoiceLibrary library;
    private SpeechSynthesizer synthesizer;
    private VoiceSender sender;
    private VoiceDelivery delivery;

    @BeforeEach
    void setUp() {
        library = mock(VoiceLibrary.class);
        synthesizer = mock(SpeechSynthesizer.class);
        sender = mock(VoiceSender.class);
        delivery = new VoiceDelivery(library, synthesizer, sender);
        when(synthesizer.isAvailable()).thenReturn(true);
        when(synthesizer.synthesize(anyString())).thenReturn(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("群聊：素材走文件、合成走字节，都发给同一个群")
    void deliversBothKinds() {
        VoiceAsset asset = new VoiceAsset(1, "晴天", "", "", "a.mp3", "audio/mpeg", 1, 1000, true, "");
        when(library.get(1L)).thenReturn(asset);
        when(library.pathOf(asset)).thenReturn(Path.of("a.mp3"));

        delivery.deliver(group(), List.of(
                MediaOutbox.Intent.asset(1, "晴天"),
                MediaOutbox.Intent.speech("晚安")));

        verify(sender).sendToGroup(eq(100L), eq(Path.of("a.mp3")));
        verify(sender).sendToGroup(eq(100L), any(byte[].class), eq("mp3"), any(VoiceOptions.class));
    }

    @Test
    @DisplayName("第一段坏掉不影响第二段")
    void failureIsIsolated() {
        when(library.get(1L)).thenThrow(new VoiceException("磁盘坏了"));

        delivery.deliver(group(), List.of(
                MediaOutbox.Intent.asset(1, "晴天"),
                MediaOutbox.Intent.speech("晚安")));

        verify(sender).sendToGroup(eq(100L), any(byte[].class), eq("mp3"), any(VoiceOptions.class));
    }

    @Test
    @DisplayName("TTS 关掉时跳过合成，不抛异常")
    void skipsWhenTtsUnavailable() {
        when(synthesizer.isAvailable()).thenReturn(false);

        delivery.deliver(group(), List.of(MediaOutbox.Intent.speech("晚安")));

        verify(sender, never()).sendToGroup(anyLong(), any(byte[].class), anyString(), any());
    }

    @Test
    @DisplayName("私聊走私聊出口")
    void privateGoesToPrivate() {
        VoiceAsset asset = new VoiceAsset(2, "猫叫", "", "", "b.mp3", "audio/mpeg", 1, 1000, true, "");
        when(library.get(2L)).thenReturn(asset);
        when(library.pathOf(asset)).thenReturn(Path.of("b.mp3"));

        delivery.deliver(privateEvent(), List.of(MediaOutbox.Intent.asset(2, "猫叫")));

        verify(sender).sendToPrivate(200L, Path.of("b.mp3"));
    }

    private static OneBotEvent group() {
        OneBotEvent e = new OneBotEvent();
        e.setPostType("message");
        e.setMessageType("group");
        e.setGroupId(100L);
        e.setUserId(200L);
        return e;
    }

    private static OneBotEvent privateEvent() {
        OneBotEvent e = new OneBotEvent();
        e.setPostType("message");
        e.setMessageType("private");
        e.setUserId(200L);
        return e;
    }
}
