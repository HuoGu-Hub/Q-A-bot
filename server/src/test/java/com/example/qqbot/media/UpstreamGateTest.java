package com.example.qqbot.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站闸门的验收测试 —— 三件事：**间隔是固定的**、**连击到上限就暂停**、**统计每窗口只说一次**。
 *
 * <p>它替代的是原先"撞一次 429 就把间隔翻倍、顺手打两条日志"的自适应漂移：
 * 那套在真实限流面前只会来回抖，日志还淹掉了一切。
 */
class UpstreamGateTest {

    @Test
    @DisplayName("★ 出站间隔是固定值：三次请求至少等两个间隔")
    void enforcesFixedGap() throws Exception {
        UpstreamGate gate = new UpstreamGate(150, 0, 3, 50);

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            gate.enter();
            gate.leave();
        }

        assertThat(System.currentTimeMillis() - t0).isGreaterThanOrEqualTo(280);
    }

    @Test
    @DisplayName("★ 连击到上限就暂停，期间一次请求都不发")
    void pausesAfterStreak() {
        UpstreamGate gate = new UpstreamGate(50, 200, 2, 50);

        assertThat(gate.onRateLimited()).as("第 1 次只顶高间隔").isFalse();
        assertThat(gate.gapMs()).isEqualTo(100);
        assertThat(gate.onRateLimited()).as("第 2 次进入暂停").isTrue();
        assertThat(gate.paused()).isTrue();
        assertThat(gate.pauseRemainingMs()).isGreaterThan(0);
    }

    @Test
    @DisplayName("暂停到期自动恢复，间隔回到基准值")
    void resumesAfterPause() throws Exception {
        UpstreamGate gate = new UpstreamGate(50, 80, 1, 50);
        assertThat(gate.onRateLimited()).isTrue();

        Thread.sleep(120);

        assertThat(gate.paused()).isFalse();
        assertThat(gate.gapMs()).isEqualTo(50);
    }

    @Test
    @DisplayName("成功会清掉连击 —— 偶发一次限流不该越攒越多")
    void successResetsStreak() {
        UpstreamGate gate = new UpstreamGate(50, 10_000, 3, 50);
        gate.onRateLimited();
        gate.onRateLimited();
        gate.onSuccess();

        assertThat(gate.onRateLimited()).as("清过之后这一次不该触发暂停").isFalse();
        assertThat(gate.paused()).isFalse();
    }

    @Test
    @DisplayName("★ 限流统计每窗口只说一次，没限流时一句都不说")
    void summarizesOncePerWindow() throws Exception {
        UpstreamGate gate = new UpstreamGate(50, 10_000, 5, 60);

        assertThat(gate.summaryIfDue()).as("窗口没到就不报").isNull();
        gate.onRateLimited();
        Thread.sleep(80);

        UpstreamGate.Stats stats = gate.summaryIfDue();
        assertThat(stats).isNotNull();
        assertThat(stats.limited()).isEqualTo(1);
        assertThat(gate.summaryIfDue()).as("刚报过就不再报").isNull();
    }
}
