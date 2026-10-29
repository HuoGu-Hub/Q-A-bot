package com.example.qqbot.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流量聚合的口径。
 *
 * <p>这里最容易错的是分位数的下标（{@code ceil(p/100*n)-1} 还是 {@code floor}），
 * 差一位就会把 P95 报成"比最大值还大"或"等于中位数"。
 */
class TrafficMetricsTest {

    @Test
    @DisplayName("★ P95 落在尾部而不是中位数：100 个 1..100 的样本，P50=50、P95=95")
    void percentilesLandWhereExpected() {
        TrafficMetrics metrics = new TrafficMetrics();
        for (int i = 1; i <= 100; i++) {
            metrics.record(i, 200, true);
        }
        TrafficMetrics.Snapshot s = metrics.drain();
        assertThat(s).isNotNull();
        assertThat(s.requests()).isEqualTo(100);
        assertThat(s.p50()).isEqualTo(50);
        assertThat(s.p95()).isEqualTo(95);
        assertThat(s.max()).isEqualTo(100);
    }

    @Test
    @DisplayName("静态请求只计数、不进延迟样本 —— 否则 P95 会被 1ms 的静态资源稀释")
    void staticRequestsDoNotPolluteLatency() {
        TrafficMetrics metrics = new TrafficMetrics();
        metrics.record(1, 200, false);
        metrics.record(1, 200, false);
        metrics.record(800, 200, true);

        TrafficMetrics.Snapshot s = metrics.drain();
        assertThat(s.requests()).isEqualTo(3);
        assertThat(s.api()).isEqualTo(1);
        assertThat(s.p95()).isEqualTo(800);
    }

    @Test
    @DisplayName("5xx 计入错误，4xx 不计 —— 404 是用户点错了，不是服务坏了")
    void onlyServerErrorsCount() {
        TrafficMetrics metrics = new TrafficMetrics();
        metrics.record(5, 404, true);
        metrics.record(5, 500, true);
        metrics.record(5, 503, false);

        TrafficMetrics.Snapshot s = metrics.drain();
        assertThat(s.errors()).isEqualTo(2);
    }

    @Test
    @DisplayName("drain 之后清零：第二分钟不会把第一分钟的数再算一遍")
    void drainResets() {
        TrafficMetrics metrics = new TrafficMetrics();
        metrics.record(10, 200, true);
        assertThat(metrics.drain()).isNotNull();
        assertThat(metrics.drain()).isNull();
    }

    @Test
    @DisplayName("没有请求的分钟不产生空行 —— 前端补零比后端补空行简单")
    void emptyMinuteIsNull() {
        assertThat(new TrafficMetrics().drain()).isNull();
    }
}
