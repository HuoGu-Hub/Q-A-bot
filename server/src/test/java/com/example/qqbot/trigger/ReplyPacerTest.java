package com.example.qqbot.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 节奏闸：间隔、用户冷却、每日上限、0 = 不限 */
class ReplyPacerTest {

    private static final long GROUP = 1L;
    private static final long USER = 2L;

    @Test
    @DisplayName("同群间隔与用户冷却同时生效")
    void gapAndUserCooldown() {
        Policy policy = new Policy();
        policy.minGapSeconds = 10;
        policy.userCooldownSeconds = 20;
        long[] now = {0L};
        ReplyPacer pacer = new ReplyPacer(policy, () -> now[0]);

        assertThat(pacer.allows(GROUP, USER)).isTrue();
        assertThat(pacer.tryReserve(GROUP, USER)).isTrue();

        now[0] = 5_000L;
        assertThat(pacer.allows(GROUP, USER)).isFalse();

        now[0] = 10_000L;
        assertThat(pacer.allows(GROUP, USER)).isFalse();

        now[0] = 20_000L;
        assertThat(pacer.allows(GROUP, USER)).isTrue();
        assertThat(pacer.tryReserve(GROUP, USER)).isTrue();
    }

    @Test
    @DisplayName("每日上限：第 N+1 条被挡")
    void dailyMax() {
        Policy policy = new Policy();
        policy.dailyMaxPerGroup = 2;
        ReplyPacer pacer = new ReplyPacer(policy, () -> 0L);

        assertThat(pacer.tryReserve(GROUP, USER)).isTrue();
        assertThat(pacer.tryReserve(GROUP, USER + 1)).isTrue();
        assertThat(pacer.allows(GROUP, USER + 2)).isFalse();
        assertThat(pacer.tryReserve(GROUP, USER + 2)).isFalse();
    }

    @Test
    @DisplayName("全 0 = 不限")
    void zerosMeanUnlimited() {
        Policy policy = new Policy();
        ReplyPacer pacer = new ReplyPacer(policy, () -> 0L);
        for (int i = 0; i < 100; i++) {
            assertThat(pacer.tryReserve(GROUP, USER + i)).isTrue();
        }
    }

    private static final class Policy implements TriggerPolicy {
        double probability = 1.0;
        int minGapSeconds;
        int userCooldownSeconds;
        int dailyMaxPerGroup;

        @Override public double probability() { return probability; }
        @Override public List<Long> enabledGroups() { return List.of(GROUP); }
        @Override public int minGapSeconds() { return minGapSeconds; }
        @Override public int userCooldownSeconds() { return userCooldownSeconds; }
        @Override public int dailyMaxPerGroup() { return dailyMaxPerGroup; }
        @Override public boolean shadowMode() { return false; }
    }
}
