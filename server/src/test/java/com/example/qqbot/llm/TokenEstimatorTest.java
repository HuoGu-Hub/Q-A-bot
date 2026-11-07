package com.example.qqbot.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 估算器的口径。
 *
 * <p>不追求逐 token 对齐 —— 它只是"厂商没回 usage"时的兜底。
 * 这里锁住三件事：中文字比英文贵、结构开销不会漏、工具定义与图片不会被当成 0。
 */
class TokenEstimatorTest {

    @Test
    @DisplayName("英文按 4 字符 1 token 折算")
    void asciiUsesFourCharsPerToken() {
        assertThat(TokenEstimator.textTokens("a".repeat(400))).isEqualTo(100);
        assertThat(TokenEstimator.textTokens("abcd")).isEqualTo(1);
    }

    @Test
    @DisplayName("中文按字折算，明显高于同长度英文")
    void chineseCostsMoreThanAscii() {
        long zh = TokenEstimator.textTokens("中".repeat(100));
        long en = TokenEstimator.textTokens("a".repeat(100));
        assertThat(zh).isBetween(80L, 90L);
        assertThat(zh).isGreaterThan(en * 3);
    }

    @Test
    @DisplayName("输入含每条消息与整条请求的固定开销")
    void inputIncludesStructuralOverhead() {
        ChatRequest one = ChatRequest.builder()
                .messages(List.of(SystemMessage.from("a"), UserMessage.from("b")))
                .build();

        long tokens = TokenEstimator.inputTokens(one);

        assertThat(tokens).isGreaterThanOrEqualTo(
                TokenEstimator.textTokens("a") + TokenEstimator.textTokens("b")
                        + 2L * TokenEstimator.MESSAGE_OVERHEAD + TokenEstimator.REQUEST_OVERHEAD);
    }

    @Test
    @DisplayName("工具定义算进输入：五个工具不是一串零")
    void toolDefinitionsCountTowardInput() {
        ToolSpecification tool = ToolSpecification.builder()
                .name("lookup")
                .description("在知识库里检索游戏资料，返回条目正文与来源")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "检索关键词")
                        .build())
                .build();
        ChatRequest withTool = ChatRequest.builder()
                .messages(List.of(UserMessage.from("狮鹫套怎么获得")))
                .toolSpecifications(List.of(tool))
                .build();
        ChatRequest withoutTool = ChatRequest.builder()
                .messages(List.of(UserMessage.from("狮鹫套怎么获得")))
                .build();

        assertThat(TokenEstimator.inputTokens(withTool))
                .isGreaterThan(TokenEstimator.inputTokens(withoutTool));
    }

    @Test
    @DisplayName("图片按固定值折算；字节数不参与计算")
    void imagesAddFixedTokens() {
        UserMessage textOnly = UserMessage.from("看图");
        ChatRequest textRequest = ChatRequest.builder().messages(List.of(textOnly)).build();
        ChatRequest imageRequest = ChatRequest.builder()
                .messages(List.of(UserMessage.from(List.of(
                        dev.langchain4j.data.message.TextContent.from("看图"),
                        ImageContent.from("aGVsbG8=", "image/png")))))
                .build();

        assertThat(TokenEstimator.inputTokens(imageRequest))
                .isGreaterThanOrEqualTo(TokenEstimator.inputTokens(textRequest)
                        + TokenEstimator.IMAGE_TOKENS);
    }

    @Test
    @DisplayName("输出按正文算；工具调用参数也是模型生成的 token")
    void outputCountsTextAndToolArguments() {
        ChatResponse plain = ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.from("好的"))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(10, 2)).build())
                .build();
        ChatResponse withTool = ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.from(List.of(
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                .id("1")
                                .name("lookup")
                                .arguments("{\"query\":\"狮鹫套\"}")
                                .build())))
                .metadata(ChatResponseMetadata.builder().build())
                .build();

        assertThat(TokenEstimator.outputTokens(plain))
                .as("两个汉字 + 固定的结尾开销")
                .isEqualTo(4);
        assertThat(TokenEstimator.outputTokens(withTool)).isPositive();
    }
}
