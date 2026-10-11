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

    /**
     * 记录"模型到底收到了什么"的模型。
     *
     * <p>探针只看返回值是不够的 —— 视觉路径出过一次静默事故：调用不抛异常、
     * 返回值正常，但图片压根没到模型。所以这里必须能回看输入。
     */
    static final class RecordingModel implements ChatModel {
        private ChatRequest last;

        @Override
        public ChatResponse doChat(ChatRequest request) {
            this.last = request;
            return ChatResponse.builder().aiMessage(AiMessage.from("好的")).build();
        }

        /** 模型收到的最后一条用户消息 */
        dev.langchain4j.data.message.UserMessage lastUserMessage() {
            return last.messages().stream()
                    .filter(m -> m instanceof dev.langchain4j.data.message.UserMessage)
                    .map(m -> (dev.langchain4j.data.message.UserMessage) m)
                    .reduce((a, b) -> b)
                    .orElseThrow(() -> new AssertionError("模型一条用户消息都没收到"));
        }
    }

    /**
     * 视觉路径的探针 —— <b>与生产同一个形状</b>：文本走 {@code @UserMessage String}，
     * 图片走 {@code @UserMessage List<ImageContent>}。
     *
     * <p>两条硬约束：
     * <ol>
     *   <li>图片必须是独立的 Content 参数。塞进一条 {@code UserMessage} 再当参数传，
     *       框架会取它的 {@code toString()} 当模板，图片变成正文里的 base64 文本；</li>
     *   <li>probe 必须挂 {@code retrievalAugmentor}，生产就是挂着的 ——
     *       上一版探针没挂，所以没暴露第一条。</li>
     * </ol>
     */
    interface VisionProbe {
        String chat(@UserMessage String message,
                    @UserMessage List<ImageContent> images,
                    InvocationParameters params);
    }

    @Test
    @DisplayName("★ 带图片的一问一答：ImageContent 真的到了模型，而不是被序列化成文本")
    void userMessageWithImageReachesModel() {
        RecordingModel model = new RecordingModel();
        // ★ 必须挂 augmentor：生产挂着它，而正是它让"整条 UserMessage 当参数"那条路露馅
        RetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .queryRouter(new DefaultQueryRouter(q -> List.of()))
                .build();

        VisionProbe assistant = AiServices.builder(VisionProbe.class)
                .chatModel(model)
                .retrievalAugmentor(augmentor)
                .build();

        List<ImageContent> images = List.of(ImageContent.from("QUJD", "image/jpeg"));
        String reply = assistant.chat(
                "发言者：小明\n<<<USER_CONTENT\n这是什么\nUSER_CONTENT>>>",
                images, new InvocationParameters());

        assertThat(reply).isEqualTo("好的");
        assertThat(model.lastUserMessage().contents())
                .as("图片必须以 ImageContent 到达模型。若这条红了，说明图片被当文本发了："
                        + "contents 只剩一条 TextContent，正文里带着 base64")
                .anyMatch(ImageContent.class::isInstance);
        assertThat(((TextContent) model.lastUserMessage().contents().get(0)).text())
                .as("正文是信封本身，不是 UserMessage{...} 的序列化结果")
                .doesNotContain("UserMessage {")
                .contains("这是什么");
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
