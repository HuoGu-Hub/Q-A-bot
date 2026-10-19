package com.example.qqbot.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站节奏的两道闸。
 *
 * <p>这里只验纯逻辑：间隔区间、以及"两道闸取较晚的一个"这条不叠加的性质。
 * 真实睡眠交给集成行为，不在这里等真实时间。
 */
class OutboundPacerTest {

    private static Outbound config(long groupBase, long groupMin, long groupMax,
                                   long globalBase, long globalMin, long globalMax) {
        Outbound out = new Outbound();
        out.setGroupMinIntervalMillis(groupBase);
        out.setGroupJitterMinMillis(groupMin);
        out.setGroupJitterMaxMillis(groupMax);
        out.setGlobalMinIntervalMillis(globalBase);
        out.setGlobalJitterMinMillis(globalMin);
        out.setGlobalJitterMaxMillis(globalMax);
        return out;
    }

    // ==================== 间隔区间 ====================

    @Test
    @DisplayName("抖动落在给定区间内，不是对称的正负值")
    void jitterStaysInRange() {
        for (int i = 0; i < 2000; i++) {
            long v = OutboundPacer.interval(500, -100, 400);
            assertThat(v).isBetween(400L, 900L);
        }
    }

    @Test
    @DisplayName("区间两头都能取到 —— 上下限真的生效了")
    void jitterReachesBothEnds() {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < 2000; i++) {
            long v = OutboundPacer.interval(500, -100, 400);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertThat(min).isLessThanOrEqualTo(410L);
        assertThat(max).isGreaterThanOrEqualTo(890L);
    }

    @Test
    @DisplayName("抖动为 0 时就是固定间隔")
    void zeroJitterIsExact() {
        assertThat(OutboundPacer.interval(500, 0, 0)).isEqualTo(500L);
    }

    @Test
    @DisplayName("基础间隔为 0 表示这道闸关着，抖动也不生效")
    void zeroBaseMeansOff() {
        assertThat(OutboundPacer.interval(0, -100, 400)).isZero();
    }

    @Test
    @DisplayName("下限大于上限时自动纠正，不会抛异常")
    void swappedBoundsAreTolerated() {
        for (int i = 0; i < 200; i++) {
            assertThat(OutboundPacer.interval(500, 400, -100)).isBetween(400L, 900L);
        }
    }

    // ==================== 两道闸不叠加 ====================

    @Test
    @DisplayName("★ 群级与账号级取较晚的一个，不是相加")
    void twoGatesTakeTheLaterNotTheSum() {
        OutboundPacer pacer = new OutboundPacer(config(100, 0, 0, 100, 0, 0));

        long first = pacer.reserve(true, 1L);
        long second = pacer.reserve(true, 1L);

        assertThat(first).isLessThan(50L);
        // 相加的话这里会是 200 左右
        assertThat(second).isBetween(50L, 160L);
    }

    @Test
    @DisplayName("★ 换一个群照样受账号级约束：这正是跨群爆发的那道闸")
    void otherGroupStillWaitsForTheAccountGate() {
        OutboundPacer pacer = new OutboundPacer(config(100, 0, 0, 100, 0, 0));

        pacer.reserve(true, 1L);
        long otherGroup = pacer.reserve(true, 2L);

        // 群 2 自己没发过消息，但账号只能按同一节奏走
        assertThat(otherGroup).isBetween(50L, 160L);
    }

    @Test
    @DisplayName("总开关关掉时立刻可发")
    void disabledMeansNoWait() {
        Outbound out = config(100, 0, 0, 100, 0, 0);
        out.setEnabled(false);
        OutboundPacer pacer = new OutboundPacer(out);

        assertThat(pacer.reserve(true, 1L)).isZero();
        assertThat(pacer.reserve(true, 1L)).isZero();
    }

    @Test
    @DisplayName("私聊也要过账号级这道闸")
    void privateSendsShareTheAccountGate() {
        OutboundPacer pacer = new OutboundPacer(config(0, 0, 0, 100, 0, 0));

        pacer.reserve(false, 0L);
        long second = pacer.reserve(false, 0L);

        assertThat(second).isBetween(50L, 160L);
    }

    // ==================== 切分 ====================

    @Test
    @DisplayName("不超限就原样返回一条")
    void splitKeepsShortText() {
        OutboundPacer pacer = new OutboundPacer(config(0, 0, 0, 0, 0, 0));
        assertThat(pacer.split("短句。")).containsExactly("短句。");
    }

    @Test
    @DisplayName("按中文句号切，不在英文句点上切 —— 否则网址会被切坏")
    void splitRespectsSentenceBoundaries() {
        Outbound out = config(0, 0, 0, 0, 0, 0);
        out.setMaxCharsPerMessage(12);
        OutboundPacer pacer = new OutboundPacer(out);

        var parts = pacer.split("第一句话说完了。第二句话也说完了。");

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).endsWith("。");
    }
}
