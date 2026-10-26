package com.example.qqbot.voice;

import com.example.qqbot.onebot.outbound.OutboundSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语音业务入口的验收：闸门（开关/大小）必须在**碰协议端之前**生效。
 *
 * <p>ffmpeg 换成 mock，这里验的是"什么时候该发、发的是不是加工后的字节"；
 * 真实转码在 {@link VoiceProcessorTest} 里跑。
 */
class VoiceSenderTest {

    private static final byte[] PROCESSED = "fake-mp3-bytes".getBytes();
    private static final byte[] SOURCE = "fake-source".getBytes();

    private VoiceProcessor processor;
    private OutboundSender outbound;
    private VoiceSender sender;

    @BeforeEach
    void setUp() {
        processor = mock(VoiceProcessor.class);
        outbound = mock(OutboundSender.class);
        when(processor.encode(any(byte[].class), anyString(), any(VoiceOptions.class)))
                .thenReturn(new VoiceProcessor.Encoded(PROCESSED, 12.5));
        sender = new VoiceSender(policy(true, 1024), processor, outbound);
    }

    @Test
    @DisplayName("群语音：发的是加工后的字节，不是原始文件")
    void sendsProcessedBytesToGroup() {
        when(outbound.sendVoiceToGroup(100L, PROCESSED)).thenReturn(42L);

        VoiceSender.Sent sent = sender.sendToGroup(100L, SOURCE, "wav", VoiceOptions.NONE);

        assertThat(sent.messageId()).isEqualTo(42L);
        assertThat(sent.bytes()).isEqualTo(PROCESSED.length);
        assertThat(sent.seconds()).isEqualTo(12.5);

        ArgumentCaptor<byte[]> sourceCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(processor).encode(sourceCaptor.capture(), anyString(), any(VoiceOptions.class));
        assertThat(sourceCaptor.getValue()).isEqualTo(SOURCE);
        verify(outbound).sendVoiceToGroup(100L, PROCESSED);
    }

    @Test
    @DisplayName("私聊语音走私聊出口，不会误发到群里")
    void sendsPrivateVoice() {
        when(outbound.sendVoiceToPrivate(200L, PROCESSED)).thenReturn(7L);

        sender.sendToPrivate(200L, SOURCE, "mp3", VoiceOptions.NONE);

        verify(outbound).sendVoiceToPrivate(200L, PROCESSED);
        verify(outbound, never()).sendVoiceToGroup(anyLong(), any());
    }

    @Test
    @DisplayName("总开关关掉时连 ffmpeg 都不该启动")
    void disabledPolicyStopsBeforeProcessing() {
        VoiceSender off = new VoiceSender(policy(false, 1024), processor, outbound);

        assertThatThrownBy(() -> off.sendToGroup(100L, SOURCE, "mp3", VoiceOptions.NONE))
                .isInstanceOf(VoiceException.class)
                .hasMessageContaining("已关闭");
        verify(processor, never()).encode(any(), anyString(), any());
        verify(outbound, never()).sendVoiceToGroup(anyLong(), any());
    }

    @Test
    @DisplayName("加工后超过上限：明确报错，不把半首歌塞进消息")
    void oversizedOutputRejected() {
        byte[] tooBig = new byte[2048];
        when(processor.encode(any(byte[].class), anyString(), any(VoiceOptions.class)))
                .thenReturn(new VoiceProcessor.Encoded(tooBig, 200));
        VoiceSender small = new VoiceSender(policy(true, 1), processor, outbound);

        assertThatThrownBy(() -> small.sendToGroup(100L, SOURCE, "mp3", VoiceOptions.NONE))
                .isInstanceOf(VoiceException.class)
                .hasMessageContaining("超过上限");
        verify(outbound, never()).sendVoiceToGroup(anyLong(), any());
    }

    @Test
    @DisplayName("空音频与非法群号在入口处就拒绝")
    void rejectsEmptyAudioAndBadTarget() {
        assertThatThrownBy(() -> sender.sendToGroup(100L, new byte[0], "mp3", VoiceOptions.NONE))
                .isInstanceOf(VoiceException.class)
                .hasMessageContaining("为空");
        assertThatThrownBy(() -> sender.sendToGroup(0L, SOURCE, "mp3", VoiceOptions.NONE))
                .isInstanceOf(VoiceException.class)
                .hasMessageContaining("群号不合法");
    }

    private static VoicePolicy policy(boolean enabled, int maxKb) {
        return new VoicePolicy() {
            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public int getMaxKb() {
                return maxKb;
            }

            @Override
            public int getMaxSeconds() {
                return 300;
            }

            @Override
            public String getFfmpeg() {
                return "ffmpeg";
            }

            @Override
            public String getFfprobe() {
                return "ffprobe";
            }

            @Override
            public int getTimeoutSeconds() {
                return 30;
            }
        };
    }
}
