package com.example.qqbot.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具调用记录：授权先占位，执行后补结果。
 * 这三条覆盖了 Agent 维度真正要看的三种结局：成功、失败、被拒。
 */
class ToolCallTraceTest {

    @Test
    @DisplayName("成功调用记下耗时与返回长度")
    void recordsSuccess() {
        ToolCallTrace.begin();
        ToolCallTrace.pending("lookup", "{\"q\":\"废料杯\"}");
        String out = ToolCallTrace.run("lookup", "{\"q\":\"废料杯\"}", () -> "三条资料");
        List<ToolCallTrace.Call> calls = ToolCallTrace.end();
        assertThat(out).isEqualTo("三条资料");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).status()).isEqualTo("ok");
        assertThat(calls.get(0).resultChars()).isEqualTo(4);
        assertThat(calls.get(0).latencyMs()).isNotNull();
        assertThat(calls.get(0).error()).isNull();
    }

    @Test
    @DisplayName("执行异常记为 error，异常照常抛出")
    void recordsError() {
        ToolCallTrace.begin();
        ToolCallTrace.pending("locate", "{}");
        assertThatThrownBy(() -> ToolCallTrace.run("locate", "{}", () -> {
            throw new IllegalStateException("地图不可用");
        })).isInstanceOf(IllegalStateException.class);
        ToolCallTrace.Call call = ToolCallTrace.end().get(0);
        assertThat(call.status()).isEqualTo("error");
        assertThat(call.error()).contains("地图不可用");
        assertThat(call.resultChars()).isNull();
    }

    @Test
    @DisplayName("授权拒绝单独记 denied，不算一次执行")
    void recordsDenied() {
        ToolCallTrace.begin();
        ToolCallTrace.denied("say", "{\"text\":\"hi\"}", "工具未授权");
        ToolCallTrace.Call call = ToolCallTrace.end().get(0);
        assertThat(call.status()).isEqualTo("denied");
        assertThat(call.error()).isEqualTo("工具未授权");
        assertThat(call.latencyMs()).isNull();
    }

    @Test
    @DisplayName("没打开收集时什么也不记")
    void noopWhenInactive() {
        ToolCallTrace.pending("lookup", "{}");
        ToolCallTrace.run("lookup", "{}", () -> "x");
        assertThat(ToolCallTrace.end()).isEmpty();
    }
}
