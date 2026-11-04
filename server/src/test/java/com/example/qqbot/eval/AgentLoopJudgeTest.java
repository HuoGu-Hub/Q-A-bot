package com.example.qqbot.eval;

import com.example.qqbot.trace.ToolCallTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 循环判定：两次算重试，三次以上算打转；被拒绝的调用不参与。
 */
class AgentLoopJudgeTest {

    private static ToolCallTrace.Call call(int i, String name, String args, String status) {
        return new ToolCallTrace.Call(i, name, args, status, 5L, 10, null);
    }

    @Test
    @DisplayName("没有工具调用就没有这个维度")
    void noCalls() {
        assertThat(AgentLoopJudge.judge(List.of())).isNull();
        assertThat(AgentLoopJudge.judge(null)).isNull();
    }

    @Test
    @DisplayName("两次相同调用算正常重试")
    void twoIsFine() {
        Judgement j = AgentLoopJudge.judge(List.of(
                call(1, "lookup", "{\"q\":\"x\"}", "ok"),
                call(2, "lookup", "{\"q\":\"x\"}", "ok")));
        assertThat(j.verdict()).isEqualTo(Judgement.PASS);
    }

    @Test
    @DisplayName("同一个工具同样参数三次判循环")
    void threeIsLoop() {
        Judgement j = AgentLoopJudge.judge(List.of(
                call(1, "lookup", "{\"q\":\"x\"}", "ok"),
                call(2, "lookup", "{\"q\":\"x\"}", "ok"),
                call(3, "lookup", "{\"q\":\"x\"}", "ok")));
        assertThat(j.verdict()).isEqualTo(Judgement.FAIL);
        assertThat(j.tag()).isEqualTo("AGENT_LOOP");
        assertThat(j.reason()).contains("3");
    }

    @Test
    @DisplayName("被拒绝的调用不计入重复")
    void deniedIgnored() {
        Judgement j = AgentLoopJudge.judge(List.of(
                call(1, "lookup", "{}", "denied"),
                call(2, "lookup", "{}", "denied"),
                call(3, "lookup", "{}", "denied")));
        assertThat(j.verdict()).isEqualTo(Judgement.PASS);
    }
}
