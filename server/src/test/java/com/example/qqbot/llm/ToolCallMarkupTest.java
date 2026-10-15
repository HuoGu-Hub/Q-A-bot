package com.example.qqbot.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用标记的摘除 —— 2026-10-15 群里漏出 {@code <tool_call>} 的事故回归。
 *
 * <p>模型有时不返回结构化的 {@code tool_calls}，而是把调用写进正文。
 * 这种文字一旦发出去，群友看到的就是协议细节，所以出站前必须摘干净。
 */
class ToolCallMarkupTest {

    @Test
    @DisplayName("★ 完整 <tool_call> 块整块摘掉，标记后面的正文保留")
    void stripsToolCallBlock() {
        String text = "<tool_call>{\"name\": \"search_titles\", \"arguments\": {\"query\": \"光芒戒指\"}}</tool_call>\n戒指在 Elder Bridge。";

        assertThat(ToolCallMarkup.hasMarkup(text)).isTrue();
        assertThat(ToolCallMarkup.strip(text)).isEqualTo("戒指在 Elder Bridge。");
    }

    @Test
    @DisplayName("★ 没闭合的 <tool_call> 从标记起到末尾全删（半截协议绝不能发出去）")
    void stripsUnclosedToolCall() {
        String text = "答案在这。\n<tool_call>{\"name\": \"lookup\"";

        assertThat(ToolCallMarkup.strip(text)).isEqualTo("答案在这。");
    }

    @Test
    @DisplayName("★ DeepSeek 的 DSML 标记同样摘掉（实测第二种漏法）")
    void stripsDsml() {
        String text = "<｜｜DSML｜｜ calls>\n<｜｜DSML｜｜ invoke name=\"search_titles\">\n"
                + "<｜｜DSML｜｜ parameter name=\"query\" string=\"true\">Collector</｜｜DSML｜｜ parameter>\n"
                + "</｜｜DSML｜｜ invoke>\n</｜｜DSML｜｜ calls>";

        assertThat(ToolCallMarkup.hasMarkup(text)).isTrue();
        assertThat(ToolCallMarkup.strip(text)).isEmpty();
    }

    @Test
    @DisplayName("★ 整条只有标记：isOnlyMarkup 为真 —— 调用方得用兜底话术顶上，不能发空消息")
    void detectsMarkupOnly() {
        String only = "<tool_call>{\"name\": \"search_titles\", \"arguments\": {\"query\": \"光芒戒指\"}}</tool_call>";

        assertThat(ToolCallMarkup.isOnlyMarkup(only)).isTrue();
        assertThat(ToolCallMarkup.isOnlyMarkup("戒指在 Elder Bridge。")).isFalse();
    }

    @Test
    @DisplayName("正常回复一个字都不动（别误伤普通正文）")
    void leavesNormalTextAlone() {
        String text = "光芒戒指在 Elder Bridge 的宝箱里，坐标 (12, 34)。";

        assertThat(ToolCallMarkup.hasMarkup(text)).isFalse();
        assertThat(ToolCallMarkup.strip(text)).isEqualTo(text);
        assertThat(ToolCallMarkup.strip(null)).isNull();
    }
}
