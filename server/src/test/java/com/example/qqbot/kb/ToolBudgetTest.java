package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用预算 —— 2026-10-14 加。
 *
 * <p>模型能多轮检索之后，"为了答得更好"会想一直查下去（配方里有铁与布料，
 * 再去查铁怎么获得、布料怎么获得）。这本身是好事，但响应时间不可控，
 * 所以两道闸都要有：时限管"别卡太久"，次数管"别问太多"。
 */
class ToolBudgetTest {

    @Test
    @DisplayName("★ 次数闸：用满就拒绝，并给出原因")
    void refusesAfterMaxCalls() {
        ToolBudget b = new ToolBudget(30, 3);
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).as("第 4 次必须被拦").isFalse();
        assertThat(b.reason()).contains("3 次");
        assertThat(b.used()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 时限闸：到点就拒绝，即使次数没用完")
    void refusesAfterDeadline() throws Exception {
        ToolBudget b = new ToolBudget(1, 10);
        assertThat(b.tryAcquire()).isTrue();
        Thread.sleep(1100);
        assertThat(b.tryAcquire()).as("过点必须被拦").isFalse();
        assertThat(b.reason()).contains("秒");
        assertThat(b.used()).as("被拦的那次不该计数").isEqualTo(1);
    }

    @Test
    @DisplayName("取不到预算时不限制 —— 老调用方与测试不该因此失败")
    void noContextMeansNoLimit() {
        assertThat(ToolBudget.of(null)).isNull();
    }
}
