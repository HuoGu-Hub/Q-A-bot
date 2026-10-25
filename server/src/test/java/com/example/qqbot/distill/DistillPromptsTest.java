package com.example.qqbot.distill;

import com.example.qqbot.persistence.ChatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DistillPromptsTest {

    private static ChatRepository.Message msg(long id, String text) {
        return new ChatRepository.Message(id, "live", 0, 1, 100 + id,
                "2026-10-26T01:00:00Z", "h" + id, "甲", text, 0, "r" + id, "2026-10-26T01:00:00Z");
    }

    @Test
    @DisplayName("配置的关注点进入用户提示词，空关注点不额外插入")
    void focusIsInjected() {
        var messages = List.of(msg(1, "灵火祭坛在东边"));
        String withFocus = DistillPrompts.batchPrompt(messages, Map.of("h1", "群友A"),
                Map.of(), List.of("大佬攻略"), "只整理建筑和配方", false);
        assertThat(withFocus).contains("本次蒸馏关注点");
        assertThat(withFocus).contains("只整理建筑和配方");

        String without = DistillPrompts.batchPrompt(messages, Map.of("h1", "群友A"),
                Map.of(), List.of("大佬攻略"), "", false);
        assertThat(without).doesNotContain("本次蒸馏关注点");
    }

    @Test
    @DisplayName("图片作为附件时不再标记未识别，并对模型说明顺序")
    void imageAttachmentNote() {
        var messages = List.of(msg(1, "看这张图"));
        var image = new ChatRepository.Image(3, 1, "m", "a.png", "image/png", 10, "",
                "2026-10-26T01:00:00Z");
        String attached = DistillPrompts.batchPrompt(messages, Map.of("h1", "群友A"),
                Map.of(1L, List.of(image)), List.of("大佬攻略"), "", true);
        assertThat(attached).contains("作为附件提供");
        assertThat(attached).doesNotContain("未识别");

        String notAttached = DistillPrompts.batchPrompt(messages, Map.of("h1", "群友A"),
                Map.of(1L, List.of(image)), List.of("大佬攻略"), "", false);
        assertThat(notAttached).contains("未识别");
    }
}
