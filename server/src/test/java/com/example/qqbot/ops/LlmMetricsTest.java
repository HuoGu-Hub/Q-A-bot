package com.example.qqbot.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型调用聚合的口径。
 *
 * <p>这块唯一的坑是"按调用还是按回答计数"：一次群聊回答可能包含多轮工具调用，
 * 每一轮都是真实计费请求。按回答计数会系统性低估成本。
 */
class LlmMetricsTest {

    @Test
    @DisplayName("★ token 按往返累加：两轮工具调用 + 一次最终作答 = 3 次调用")
    void countsEveryRoundTrip() {
        LlmMetrics metrics = new LlmMetrics();
        metrics.record(800, true, 1200, 90);
        metrics.record(700, true, 1500, 120);
        metrics.record(900, true, 1800, 200);

        LlmMetrics.Snapshot s = metrics.drain();
        assertThat(s.calls()).isEqualTo(3);
        assertThat(s.tokensIn()).isEqualTo(4500);
        assertThat(s.tokensOut()).isEqualTo(410);
        assertThat(s.failures()).isZero();
    }

    @Test
    @DisplayName("失败也计数：超时同样占用时间，往往也已经计费")
    void failuresAreCounted() {
        LlmMetrics metrics = new LlmMetrics();
        metrics.record(60_000, false, 0, 0);
        metrics.record(500, true, 100, 20);

        LlmMetrics.Snapshot s = metrics.drain();
        assertThat(s.calls()).isEqualTo(2);
        assertThat(s.failures()).isEqualTo(1);
        assertThat(s.max()).isEqualTo(60_000);
    }

    @Test
    @DisplayName("厂商没回用量时传 0，不能变成负数或者 NaN")
    void missingUsageIsZero() {
        LlmMetrics metrics = new LlmMetrics();
        metrics.record(100, true, 0, 0);
        LlmMetrics.Snapshot s = metrics.drain();
        assertThat(s.tokensIn()).isZero();
        assertThat(s.tokensOut()).isZero();
    }

    @Test
    @DisplayName("drain 之后清零；没有调用的分钟返回 null")
    void drainResets() {
        LlmMetrics metrics = new LlmMetrics();
        assertThat(metrics.drain()).isNull();
        metrics.record(10, true, 1, 1);
        assertThat(metrics.drain()).isNotNull();
        assertThat(metrics.drain()).isNull();
    }
}
