package com.example.qqbot.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「必须有语音」时的兜底规则。
 *
 * <p>这里锁住的是行为契约：模型安排了就照它的；没安排但关键词要求，就念回答的前 80 字；
 * 没要求就什么都不加。三种情况都不能静默变成另一种。
 */
class VoiceReplyPlannerTest {

    @Test
    @DisplayName("模型安排了语音：原样采用，不再兜底")
    void stagedWins() {
        List<MediaOutbox.Intent> staged = List.of(MediaOutbox.Intent.asset(3, "晴天"));

        assertThat(VoiceReplyPlanner.complete(true, staged, "随便说说")).isEqualTo(staged);
    }

    @Test
    @DisplayName("关键词要求语音但模型没安排：把回答截到 80 字合成")
    void fallsBackToSpeech() {
        String reply = "晚上八点集合。".repeat(30);

        List<MediaOutbox.Intent> media = VoiceReplyPlanner.complete(true, List.of(), reply);

        assertThat(media).hasSize(1);
        assertThat(media.get(0).kind()).isEqualTo(MediaOutbox.Kind.SPEECH);
        assertThat(media.get(0).text()).hasSize(VoiceReplyPlanner.FALLBACK_MAX_CHARS);
    }

    @Test
    @DisplayName("没要求语音且模型没安排：什么都不发")
    void noVoiceWhenNotRequired() {
        assertThat(VoiceReplyPlanner.complete(false, List.of(), "普通回答")).isEmpty();
    }

    @Test
    @DisplayName("回答为空时也不合成，避免发出空白语音")
    void blankReplyProducesNothing() {
        assertThat(VoiceReplyPlanner.complete(true, List.of(), "   ")).isEmpty();
        assertThat(VoiceReplyPlanner.complete(true, null, null)).isEmpty();
    }
}
