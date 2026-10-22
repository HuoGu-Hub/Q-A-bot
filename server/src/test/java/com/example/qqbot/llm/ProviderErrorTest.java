package com.example.qqbot.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 厂商错误体 → 人话。2026-10-22 的教训：管理后台拉 OpenCode Go 的模型列表拿到 401，
 * 界面却说"该厂商可能不提供模型列表接口"，人就去手打模型 ID 了 —— 白折腾。
 */
class ProviderErrorTest {

    @Test
    @DisplayName("★ OpenCode Go 的 api_key_expired：取出 message，别把整段 JSON 丢给人")
    void readsOpenCodeAuthError() {
        String raw = "{\"error\":{\"type\":\"authentication_error\",\"code\":\"api_key_expired\","
                + "\"message\":\"This API key has expired. Create a new API key in OpenCode Console.\"}}";

        assertThat(ProviderError.readable(raw))
                .isEqualTo("This API key has expired. Create a new API key in OpenCode Console.");
        assertThat(ProviderError.isAuthError(401)).isTrue();
        assertThat(ProviderError.isAuthError(403)).isTrue();
        assertThat(ProviderError.isAuthError(500)).isFalse();
    }

    @Test
    @DisplayName("没有 message 时依次退到 code / type，再不行就原样返回")
    void fallsBackThroughCodeAndType() {
        assertThat(ProviderError.readable("{\"error\":{\"code\":\"rate_limited\"}}")).isEqualTo("rate_limited");
        assertThat(ProviderError.readable("{\"error\":{\"type\":\"server_error\"}}")).isEqualTo("server_error");
        assertThat(ProviderError.readable("{\"error\":\"plain string\"}")).isEqualTo("plain string");
    }

    @Test
    @DisplayName("不是 JSON 的原样返回（连接失败、超时的异常消息都走这里）")
    void passesThroughPlainText() {
        assertThat(ProviderError.readable("java.net.ConnectException: Connection refused"))
                .isEqualTo("java.net.ConnectException: Connection refused");
        assertThat(ProviderError.readable("  ")).isEmpty();
        assertThat(ProviderError.readable(null)).isEmpty();
    }

    @Test
    @DisplayName("超长错误截断，别把整段 HTML 塞进界面")
    void truncatesLongText() {
        String longText = "x".repeat(1000);
        String out = ProviderError.readable(longText);
        assertThat(out).hasSizeLessThanOrEqualTo(301).endsWith("…");
    }
}
