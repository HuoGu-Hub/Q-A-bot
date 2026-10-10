package com.example.qqbot.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探针：<b>调用方传的 {@link InvocationParameters} 能不能被 transformer 读到</b>。
 *
 * <p><b>为什么需要它</b>：切换 {@code ChatService} 时有个硬问题 —— 给模型的"用户消息"
 * 是个信封（发言者 / 引用 / 资料 / 用户内容），而<b>检索只该拿真正的问题去搜</b>。
 * 官方管线假设 Query 由用户消息派生，所以检索问题必须另找通道传进来。
 *
 * <p>框架的规则是：方法参数要么带 {@code @UserMessage} / {@code @V} / {@code @MemoryId}
 * / {@code @UserName} 注解，<b>要么类型是 {@link InvocationParameters}</b>。
 * 所以后者就是官方设计的通道 —— 这条测试把它钉死。
 *
 * <p>它同时是<b>升级哨兵</b>：哪天升级 LangChain4j 之后这条通道变了，这里会红。
 */
class AiServicesArgumentsProbeTest {

    /** 第二个参数不参与提示词，只用来把检索问题与诊断容器带进去 */
    interface Probe {
        String chat(@UserMessage String message, InvocationParameters params);
    }

    static final class EchoModel implements ChatModel {
        @Override
        public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from("好的")).build();
        }
    }

    /** 视觉路径的探针：整条 UserMessage（含图片）能不能当参数 */
    interface VisionProbe {
        // 注：UserMessage 有两个同名类 —— 注解在 service 包，消息类型在 data.message 包，
        // 所以消息类型这里必须用全限定名
        String chat(@UserMessage dev.langchain4j.data.message.UserMessage message,
                    InvocationParameters params);
    }

    @Test
    @DisplayName("★ 带图片的 UserMessage 能当参数 —— 视觉路径切换的前提")
    void userMessageWithImageAccepted() {
        VisionProbe assistant = AiServices.builder(VisionProbe.class)
                .chatModel(new EchoModel())
                .build();

        dev.langchain4j.data.message.UserMessage message =
                dev.langchain4j.data.message.UserMessage.from(
                        TextContent.from("发言者：小明"),
                        ImageContent.from("QUJD", "image/jpeg"));

        assertThat(assistant.chat(message, new InvocationParameters())).isEqualTo("好的");
    }

    /** 生产代码将来读的就是这两个键 */
    static final String KEY_QUERY = "kb.query";
    static final String KEY_SINK = "kb.diagnostics";

    @Test
    @DisplayName("★ 调用方传的 InvocationParameters 能被 transformer 读到")
    void invocationParametersReachTransformer() {
        StringBuilder seen = new StringBuilder();

        QueryTransformer probe = query -> {
            InvocationParameters p = query.metadata() == null ? null : query.metadata().invocationParameters();
            if (p != null) {
                Object q = p.get(KEY_QUERY);
                Object sink = p.get(KEY_SINK);
                seen.append(q).append('|').append(sink == null ? "无" : "有");
            }
            return List.of(query);
        };
        RetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(probe)
                .queryRouter(new DefaultQueryRouter(q -> List.of()))
                .build();

        Probe assistant = AiServices.builder(Probe.class)
                .chatModel(new EchoModel())
                .retrievalAugmentor(augmentor)
                .build();

        InvocationParameters params = new InvocationParameters();
        params.put(KEY_QUERY, "力量怎么加");
        params.put(KEY_SINK, new Object());

        String reply = assistant.chat(
                "发言者：小明\n<<<USER_CONTENT\n力量怎么加\nUSER_CONTENT>>>", params);

        assertThat(reply).isEqualTo("好的");
        assertThat(seen.toString())
                .as("transformer 必须能拿到真正的问题与诊断容器 —— 否则检索会拿整个信封去搜，静默变差")
                .isEqualTo("力量怎么加|有");
    }
}
