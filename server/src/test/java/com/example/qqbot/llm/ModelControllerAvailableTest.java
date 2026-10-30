package com.example.qqbot.llm;

import com.example.qqbot.config.LlmProperties;
import com.example.qqbot.settings.OverridesFile;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 「系统 → 模型 → 拉取列表」的返回合同 —— 2026-10-22 那次的回归测试。
 *
 * <p>当时 OpenCode Go 的 key 过期（HTTP 401），而接口把**任何**非 200 都说成
 * "该厂商可能不提供模型列表接口，请手动输入"，于是排查方向整个跑偏。
 * 401/403 必须明说是密钥问题，并且把厂商自己那句话带出来。
 */
class ModelControllerAvailableTest {

    private HttpServer server;
    private int status;
    private String body;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ModelController controller() {
        LlmProperties props = new LlmProperties();
        Provider provider = new Provider();
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        provider.setApiKey("test-key");
        provider.setModelName("fake-model");
        props.getProviders().put("opencode-go", provider);
        return new ModelController(props, mock(LlmRouter.class), mock(OverridesFile.class));
    }

    @Test
    @DisplayName("★ 401：说清是密钥过期，带上厂商原话，不许再说成\"厂商不提供接口\"")
    void authFailureIsExplained() {
        status = 401;
        body = "{\"error\":{\"type\":\"authentication_error\",\"code\":\"api_key_expired\","
                + "\"message\":\"This API key has expired.\"}}";

        var out = controller().available("opencode-go");

        assertThat(out.ok()).isFalse();
        assertThat(out.authError()).isTrue();
        assertThat(out.error())
                .contains("API Key")
                .contains("401")
                .contains("This API key has expired.")
                .doesNotContain("不提供模型列表接口");
    }

    @Test
    @DisplayName("404/405：这时才提示\"可能不提供接口，请手输\"")
    void notFoundKeepsManualHint() {
        status = 404;
        body = "no such endpoint";

        String error = controller().available("opencode-go").error();

        assertThat(error).contains("404").contains("不提供模型列表接口");
    }

    @Test
    @DisplayName("200：照常返回排好序的模型 ID")
    void listsModels() {
        status = 200;
        body = "{\"data\":[{\"id\":\"deepseek-v4-pro\"},{\"id\":\"deepseek-v4.1-flash\"}]}";

        var out = controller().available("opencode-go");

        assertThat(out.ok()).isTrue();
        assertThat(out.models()).isEqualTo(List.of("deepseek-v4-pro", "deepseek-v4.1-flash"));
        assertThat(out.count()).isEqualTo(2);
    }
}
