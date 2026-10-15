package com.example.qqbot.llm;

import com.example.qqbot.config.LlmProperties;
import com.example.qqbot.guard.BudgetGuard;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 工具定义必须**真的发到厂商** —— 2026-10-15 漏工具事故的回归测试。
 *
 * <p>事故链条：{@code LlmRouterChatModel} 只把 {@code request.messages()} 转给路由，
 * 工具定义被丢掉；模型于是照 system.md 的文字自己编工具调用，
 * 写出来的 {@code <tool_call>…</tool_call>} 被当成回答发进了群。
 *
 * <p>这里用一个本地假厂商（JDK 自带的 HttpServer）验三件事：
 * 请求体里必须有 {@code tools}；厂商返回"空文本 + tool_calls"必须被当成正常响应
 * （而不是"空回复"换下一家）；声明了没有 tools 能力的厂商不该收到带工具的请求。
 */
class LlmRouterToolForwardingTest {

    private HttpServer server;
    private String port;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private String cannedReply;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = cannedReply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        port = String.valueOf(server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** 假厂商：内容为空 + 一个工具请求，finish_reason=tool_calls（真实厂商就是这么回的） */
    private static final String TOOL_CALL_REPLY = """
            {"id":"1","object":"chat.completion","created":0,"model":"fake",
             "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
               "tool_calls":[{"id":"call_1","type":"function",
                 "function":{"name":"search_titles","arguments":"{}"}}]}}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
            """;

    private LlmRouter router(List<String> capabilities) {
        LlmProperties props = new LlmProperties();
        props.setSystemPromptFile("system.md");

        Provider provider = new Provider();
        provider.setBaseUrl("http://127.0.0.1:" + port + "/v1");
        provider.setApiKey("test-key");
        provider.setModelName("fake-model");
        provider.setCapabilities(capabilities);
        provider.setTimeoutSeconds(10);
        provider.setMaxRetries(0);
        provider.setTemperature(0.0);

        props.getProviders().put("fake", provider);
        props.setDefaultProvider("fake");
        return new LlmRouter(props, new DefaultResourceLoader(), mock(BudgetGuard.class));
    }

    private static ChatRequest requestWithTools() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("search_titles")
                .description("按英文关键词查条目名")
                .parameters(JsonObjectSchema.builder().addStringProperty("word").build())
                .build();
        return ChatRequest.builder()
                .messages(List.of(UserMessage.from("光芒戒指在哪里")))
                .toolSpecifications(List.of(spec))
                .toolChoice(ToolChoice.AUTO)
                .build();
    }

    @Test
    @DisplayName("★ 工具定义真的进了请求体，而且空文本 + tool_calls 被当成正常响应")
    void forwardsToolsAndAcceptsToolCallResponse() {
        cannedReply = TOOL_CALL_REPLY;
        LlmRouter router = router(List.of("chat", "tools"));

        ChatResponse response = router.chatMessages(requestWithTools());

        assertThat(response.aiMessage().hasToolExecutionRequests())
                .as("工具请求不能被当成空回复丢掉")
                .isTrue();
        assertThat(response.aiMessage().toolExecutionRequests().get(0).name()).isEqualTo("search_titles");
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).as("工具定义必须发给厂商").contains("\"tools\"").contains("search_titles");
    }

    @Test
    @DisplayName("★ 没声明 tools 能力的厂商收不到带工具的请求（宁可不答，也不能让模型瞎编）")
    void doesNotSendToolsToProviderWithoutCapability() {
        cannedReply = TOOL_CALL_REPLY;
        LlmRouter router = router(List.of("chat"));

        assertThatThrownBy(() -> router.chatMessages(requestWithTools()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("tools");
        assertThat(bodies).as("请求根本不该发出去").isEmpty();
    }

    @Test
    @DisplayName("不带工具的请求照旧：老路径不受影响")
    void plainRequestStillWorks() {
        cannedReply = "{\"id\":\"1\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"fake\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}";
        LlmRouter router = router(List.of("chat"));

        assertThat(router.chat("在吗")).isEqualTo("你好");
        assertThat(bodies.get(0)).doesNotContain("\"tools\"");
    }
}
