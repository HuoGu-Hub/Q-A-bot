package com.example.qqbot.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用量收集器只在显式打开时记 —— 关着的记录必须被丢掉，
 * 否则群聊线程池上的列表会一直长。
 */
class LlmUsageTest {

    @Test
    @DisplayName("没打开时记录被丢弃，打开后能取到并自动清空")
    void recordsOnlyWhenActive() {
        LlmUsage.record("p", "m", 100, 20);
        assertThat(LlmUsage.end()).isEmpty();

        LlmUsage.begin();
        LlmUsage.record("p1", "m1", 100, 20);
        LlmUsage.record("p2", "m2", 200, 40);
        List<LlmUsage.Call> calls = LlmUsage.end();
        assertThat(calls).hasSize(2);
        assertThat(LlmUsage.totalIn(calls)).isEqualTo(300);
        assertThat(LlmUsage.totalOut(calls)).isEqualTo(60);
        assertThat(LlmUsage.end()).isEmpty();
    }

    @Test
    @DisplayName("单次最多留 32 条，异常调用链不会无限增长")
    void capsCalls() {
        LlmUsage.begin();
        for (int i = 0; i < 40; i++) {
            LlmUsage.record("p", "m", 1, 1);
        }
        assertThat(LlmUsage.end()).hasSize(32);
    }
}
