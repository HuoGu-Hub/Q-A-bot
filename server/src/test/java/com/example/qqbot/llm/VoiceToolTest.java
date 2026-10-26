package com.example.qqbot.llm;

import com.example.qqbot.kb.ToolBudget;
import com.example.qqbot.voice.MediaOutbox;
import com.example.qqbot.voice.SpeechSynthesizer;
import com.example.qqbot.voice.VoiceAsset;
import com.example.qqbot.voice.VoiceLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 语音工具的验收：只往清单里记，不直接发；找不到素材要给模型一个可执行的下一步。
 */
class VoiceToolTest {

    private VoiceLibrary library;
    private SpeechSynthesizer synthesizer;
    private VoiceTool tool;
    private MediaOutbox outbox;

    @BeforeEach
    void setUp() {
        library = mock(VoiceLibrary.class);
        synthesizer = mock(SpeechSynthesizer.class);
        tool = new VoiceTool(library, synthesizer);
        outbox = new MediaOutbox();
        when(synthesizer.isAvailable()).thenReturn(true);
    }

    @Test
    @DisplayName("play_voice 命中：记一条素材意图，提示不要说不能发")
    void playVoiceStagesAsset() {
        VoiceAsset asset = new VoiceAsset(7, "晴天", "周杰伦", "歌曲", "a.mp3",
                "audio/mpeg", 1024, 12500, true, "");
        when(library.pick("晴天")).thenReturn(Optional.of(asset));

        String answer = tool.playVoice("晴天", outbox, null);

        assertThat(answer).contains("晴天").contains("已安排");
        List<MediaOutbox.Intent> staged = outbox.drain();
        assertThat(staged).hasSize(1);
        assertThat(staged.get(0).kind()).isEqualTo(MediaOutbox.Kind.ASSET);
        assertThat(staged.get(0).assetId()).isEqualTo(7);
    }

    @Test
    @DisplayName("play_voice 不命中：告诉模型可以改用 say，而不是反复重试")
    void playVoiceMissSuggestsSay() {
        when(library.pick(anyString())).thenReturn(Optional.empty());

        String answer = tool.playVoice("不存在的歌", outbox, null);

        assertThat(answer).contains("没有").contains("say");
        assertThat(outbox.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("say：超过 80 字截断后记一条合成意图")
    void sayTruncates() {
        String longText = "啊".repeat(120);

        String answer = tool.say(longText, outbox, null);

        assertThat(answer).contains("已安排");
        assertThat(outbox.drain().get(0).text()).hasSize(VoiceTool.MAX_SPEECH_CHARS);
    }

    @Test
    @DisplayName("TTS 不可用时明确拒绝，不排一条注定失败的语音")
    void sayRefusesWhenTtsUnavailable() {
        when(synthesizer.isAvailable()).thenReturn(false);

        String answer = tool.say("晚安", outbox, null);

        assertThat(answer).contains("不可用");
        assertThat(outbox.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("没有清单（非聊天场景）：直接说自己不能发")
    void withoutOutboxRefuses() {
        assertThat(tool.playVoice("晴天", null, null)).contains("不能发语音");
        assertThat(tool.say("晚安", null, null)).contains("不能发语音");
    }

    @Test
    @DisplayName("预算用完：提示收尾，不记语音")
    void budgetExhausted() {
        ToolBudget budget = new ToolBudget(30, 1);
        budget.tryAcquire();

        String answer = tool.say("晚安", outbox, budget);

        assertThat(answer).contains("预算");
        assertThat(outbox.isEmpty()).isTrue();
    }
}
