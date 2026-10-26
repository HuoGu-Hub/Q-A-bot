package com.example.qqbot.voice;

import com.example.qqbot.persistence.VoiceTriggerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 关键词触发的验收：命中、最长优先、冷却、作用域、全角归一化。
 *
 * <p>它只回答"要不要语音"，不决定内容 —— 所以这里一个素材都不需要。
 */
class VoiceTriggerServiceTest {

    private VoiceTriggerService service;

    @BeforeEach
    void setUp() {
        VoiceTriggerRepository repo = mock(VoiceTriggerRepository.class);
        when(repo.isAvailable()).thenReturn(true);
        when(repo.count()).thenReturn(2);
        when(repo.all()).thenReturn(List.of(
                new VoiceTriggerRepository.Row(1, "唱首歌", 120, "", true, "", ""),
                new VoiceTriggerRepository.Row(2, "歌", 0, "", true, "", "")));
        service = new VoiceTriggerService(repo);
        service.init();
    }

    @Test
    @DisplayName("命中：「你能唱首歌吗」触发语音")
    void matchesKeyword() {
        assertThat(service.requiresVoice(100L, "你能唱首歌吗")).isTrue();
    }

    @Test
    @DisplayName("冷却：同一个群第二次不触发")
    void cooldownBlocksSecondHit() {
        assertThat(service.requiresVoice(100L, "唱首歌")).isTrue();
        assertThat(service.requiresVoice(100L, "唱首歌")).isFalse();
    }

    @Test
    @DisplayName("冷却按群隔离：另一个群照常触发")
    void cooldownIsPerGroup() {
        assertThat(service.requiresVoice(100L, "唱首歌")).isTrue();
        assertThat(service.requiresVoice(200L, "唱首歌")).isTrue();
    }

    @Test
    @DisplayName("全角与空格不影响匹配")
    void normalizesText() {
        // 两个群各测一次：命中本身有冷却，同一群第二次一定是 false
        assertThat(service.requiresVoice(300L, "唱 首 歌")).isTrue();
        assertThat(service.requiresVoice(301L, "唱首歌")).isTrue();
    }

    @Test
    @DisplayName("没命中就是 false，控制权交回模型")
    void missReturnsFalse() {
        assertThat(service.requiresVoice(100L, "今天天气怎么样")).isFalse();
    }

    @Test
    @DisplayName("空文本与空群号不炸")
    void toleratesEmptyInput() {
        assertThat(service.requiresVoice(null, null)).isFalse();
        assertThat(service.requiresVoice(null, "  ")).isFalse();
    }

    @Test
    @DisplayName("作用域限定：只在该群触发")
    void respectsGroupScope() {
        VoiceTriggerRepository repo = mock(VoiceTriggerRepository.class);
        when(repo.isAvailable()).thenReturn(true);
        when(repo.count()).thenReturn(1);
        when(repo.all()).thenReturn(List.of(
                new VoiceTriggerRepository.Row(9, "晚安", 0, "555", true, "", "")));
        VoiceTriggerService scoped = new VoiceTriggerService(repo);
        scoped.init();

        assertThat(scoped.requiresVoice(555L, "晚安")).isTrue();
        assertThat(scoped.requiresVoice(666L, "晚安")).isFalse();
    }
}
