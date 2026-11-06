package com.example.qqbot.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 每日上限：0 = 不限；按群独立 */
class ReplyPacerTest {

    private static final long GROUP = 1L;

    @Test
    @DisplayName("每群每日上限：第 N+1 条被挡")
    void dailyMax() {
        ReplyPacer pacer = new ReplyPacer(policy(2));

        assertThat(pacer.tryReserve(GROUP)).isTrue();
        assertThat(pacer.tryReserve(GROUP)).isTrue();
        assertThat(pacer.allows(GROUP)).isFalse();
        assertThat(pacer.tryReserve(GROUP)).isFalse();
    }

    @Test
    @DisplayName("0 = 不限")
    void zerosMeanUnlimited() {
        ReplyPacer pacer = new ReplyPacer(policy(0));
        for (int i = 0; i < 100; i++) {
            assertThat(pacer.tryReserve(GROUP)).isTrue();
        }
    }

    @Test
    @DisplayName("每个群各自计数，互不影响")
    void perGroup() {
        ReplyPacer pacer = new ReplyPacer(policy(1));
        assertThat(pacer.tryReserve(GROUP)).isTrue();
        assertThat(pacer.tryReserve(GROUP + 1)).isTrue();
        assertThat(pacer.tryReserve(GROUP)).isFalse();
    }

    private static Policy policy(int dailyMax) {
        Policy p = new Policy();
        p.dailyMax = dailyMax;
        return p;
    }

    private static final class Policy implements TriggerPolicy {
        int dailyMax;

        @Override public double probability() { return 1.0; }
        @Override public List<Long> enabledGroups() { return List.of(GROUP); }
        @Override public int dailyMaxPerGroup() { return dailyMax; }
        @Override public boolean shadowMode() { return false; }
    }
}
