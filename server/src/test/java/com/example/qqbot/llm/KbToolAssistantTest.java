package com.example.qqbot.llm;

import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.Glossary;
import com.example.qqbot.kb.KbContents;
import com.example.qqbot.kb.KbContentInjector;
import com.example.qqbot.kb.KbContextQueryTransformer;
import com.example.qqbot.kb.KbCorpus;
import com.example.qqbot.kb.KbLookupTool;
import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.KbRetrievalContext;
import com.example.qqbot.kb.KbRetrievalDiagnostics;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.kb.TwoSectionInjector;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具助手的验收：<b>授权默认拒绝</b>、装配能建起来、工具循环跑得通、
 * 而且<b>检索与注入在 AiServices 内部真的跑了</b>。
 *
 * <p>最后一条最关键：它证明系统提示词契约、官方检索管线、工具循环这三样
 * 在同一个 AiServices 里是通的 —— 而不是各自单测绿、拼起来不工作。
 */
class KbToolAssistantTest {

    // ---------- 桩件 ----------

    /** 假模型：可要求调工具，并记录它实际收到了什么 */
    static final class FakeChatModel implements ChatModel {
        private final String toolName;
        private final List<ChatRequest> seen = new ArrayList<>();
        private int calls;

        FakeChatModel(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            seen.add(request);
            calls++;
            if (toolName != null && calls == 1) {
                return ChatResponse.builder().aiMessage(AiMessage.from(
                        ToolExecutionRequest.builder()
                                .id("1")
                                .name(toolName)
                                .arguments("{\"query\":\"力量\"}")
                                .build())).build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("最终答案")).build();
        }

        /** 模型收到的最后一条用户消息 —— 断言"到底送了什么"必须看整条，不能只看正文 */
        UserMessage lastUserMessage() {
            return seen.get(seen.size() - 1).messages().stream()
                    .filter(m -> m instanceof UserMessage)
                    .map(m -> (UserMessage) m)
                    .reduce((a, b) -> b)
                    .orElseThrow(() -> new AssertionError("模型一条用户消息都没收到"));
        }

        /** 模型收到的最后一条用户消息的正文。只对"单条文本"的消息有意义，多内容会抛异常 */
        String lastUserText() {
            return lastUserMessage().singleText();
        }
    }

    /** 与生产同构的官方 augmentor：桩检索器 + 我们的注入器 */
    private static RetrievalAugmentor augmentorReturning(List<Content> contents) {
        ContentRetriever retriever = query -> contents;
        ContentAggregator aggregator = map -> map.values().stream()
                .flatMap(Collection::stream)
                .flatMap(Collection::stream)
                .toList();
        return DefaultRetrievalAugmentor.builder()
                .queryRouter(new DefaultQueryRouter(retriever))
                .contentAggregator(aggregator)
                .contentInjector(new KbContentInjector(new TwoSectionInjector()))
                .build();
    }

    private static KbRetriever kbWith(RetrievalAugmentor augmentor) {
        KbRetriever kb = mock(KbRetriever.class);
        when(kb.augmentor()).thenReturn(augmentor);
        return kb;
    }

    private static LlmRouter router() {
        LlmRouter router = mock(LlmRouter.class);
        when(router.systemPromptWithStyle()).thenReturn("你是飘雪喵，安全规则若干。");
        return router;
    }

    private static KbRetriever.Hit hit(String id, String title, String text, boolean curated, String at) {
        KbCorpus.Entry e = new KbCorpus.Entry(id, "doc-" + id, title, text, "https://w/" + id,
                List.of(), curated, at, null);
        return new KbRetriever.Hit(e, 0.9, "vector");
    }

    private static KbToolAssistant assistant(ChatModel model, KbRetriever kb) {
        return new KbToolAssistant(router(), model, kb, new KbLookupTool(mock(KbRetriever.class)));
    }

    /**
     * 与生产同构的 augmentor：<b>我们的 transformer</b> + 会读上下文的桩检索器。
     *
     * <p>桩检索器刻意模仿真检索器：<b>没有上下文就返回空</b>。
     * 这正是生产里的静默失败 —— 测试要能把它暴露出来，而不是绕过去。
     */
    private static RetrievalAugmentor augmentorLikeProduction(List<KbRetriever.Hit> hits) {
        KbPolicy policy = mock(KbPolicy.class);
        when(policy.isEnabled()).thenReturn(true); // 全局知识库开关，默认关
        when(policy.getTopK()).thenReturn(5);
        when(policy.getMinScore()).thenReturn(0.45);
        KbCorpus corpus = mock(KbCorpus.class);
        // 语料里就放这些块 —— transformer 会把快照装进上下文，"关掉资料"时快照是空的
        when(corpus.entries()).thenReturn(hits.stream().map(KbRetriever.Hit::entry).toList());
        Glossary glossary = mock(Glossary.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isAvailable()).thenReturn(false);

        ContentRetriever stub = query -> {
            KbRetrievalContext ctx = KbRetrievalContext.of(query);
            // 照真检索器的行为：没上下文、或语料快照是空的，就返回空。
            // 前者是生产里的静默失败，后者是"不用知识库"模式
            if (ctx == null || ctx.entries().isEmpty()) {
                return List.of();
            }
            return hits.stream().map(KbContents::of).toList();
        };
        ContentAggregator aggregator = map -> map.values().stream()
                .flatMap(Collection::stream)
                .flatMap(Collection::stream)
                .toList();
        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(new KbContextQueryTransformer(policy, corpus, glossary, embedding))
                .queryRouter(new DefaultQueryRouter(stub))
                .contentAggregator(aggregator)
                .contentInjector(new KbContentInjector(new TwoSectionInjector()))
                .build();
    }

    // ---------- 授权 ----------

    @Test
    @DisplayName("★ 默认拒绝：白名单外的工具一律不放行")
    void deniesUnknownTools() {
        assertThat(KbToolAssistant.isAllowed("lookup")).isTrue();
        assertThat(KbToolAssistant.isAllowed("deleteEverything")).isFalse();
        assertThat(KbToolAssistant.isAllowed(null)).isFalse();
        assertThat(KbToolAssistant.isAllowed("")).isFalse();
    }

    @Test
    @DisplayName("★ 授权拒绝路径：白名单外的工具被拦下，而且根本没被执行")
    void deniesToolDuringLoop() {
        KbRetriever retriever = mock(KbRetriever.class);
        KbToolAssistant a = new KbToolAssistant(router(), new FakeChatModel("deleteEverything"),
                kbWith(augmentorReturning(List.of())), new KbLookupTool(retriever));

        try {
            a.chat("删掉一切");
        } catch (Exception ignored) {
            // 拒绝可能被框架包一层再抛；要钉的是"工具没被执行" —— 那才是安全边界
        }
        verify(retriever, never()).retrieve(anyString());
    }

    // ---------- 装配与循环 ----------

    @Test
    @DisplayName("★ 无身份的调用一律拒绝工具，而且工具根本没被执行")
    void deniesCallWithoutIdentity() {
        KbRetriever retriever = mock(KbRetriever.class);
        KbToolAssistant a = new KbToolAssistant(router(), new FakeChatModel("lookup"),
                kbWith(augmentorReturning(List.of())), new KbLookupTool(retriever));

        try {
            // 身份传空 —— 应当被授权钩子拦下
            a.chat("力量", "力量", new KbRetrievalDiagnostics(), true, "");
        } catch (Exception ignored) {
            // 拒绝可能被框架包一层再抛；要钉的是"工具没被执行"
        }
        verify(retriever, never()).retrieve(anyString());
    }

    @Test
    @DisplayName("装配能建起来：AiServices + 系统提示词 + 检索管线 + 工具 + 授权钩子")
    void wiringBuilds() {
        assertThat(assistant(new FakeChatModel(null), kbWith(augmentorReturning(List.of())))).isNotNull();
    }

    @Test
    @DisplayName("★ 工具循环跑通：模型要工具 → 工具真的被执行 → 第二轮给出答案")
    void runsToolLoop() {
        KbRetriever retriever = mock(KbRetriever.class);
        KbCorpus.Entry e = new KbCorpus.Entry("wiki-strength", "力量、Strength",
                "力量提升生命上限。", "https://w/Strength", List.of(), false, "", new float[]{1f, 0f});
        when(retriever.retrieve(anyString())).thenReturn(new KbRetriever.Retrieval(
                List.of(new KbRetriever.Hit(e, 0.9, "vector")), 0.9, 0.9, 1, 0, List.of()));

        KbToolAssistant a = new KbToolAssistant(router(), new FakeChatModel("lookup"),
                kbWith(augmentorReturning(List.of())), new KbLookupTool(retriever));

        // 必须带身份：授权钩子对"无身份"是一律拒绝的
        assertThat(a.chat("力量有什么用", "力量有什么用", new KbRetrievalDiagnostics(), true, "群:1/用户:2"))
                .isEqualTo("最终答案");
        verify(retriever).retrieve(anyString());
    }

    // ---------- 工具本身 ----------

    @Test
    @DisplayName("工具：命中时把标题与正文拼出来")
    void toolReturnsHits() {
        KbRetriever retriever = mock(KbRetriever.class);
        KbCorpus.Entry e = new KbCorpus.Entry("wiki-strength", "力量、Strength",
                "力量提升生命上限。", "https://w/Strength", List.of(), false, "", new float[]{1f, 0f});
        when(retriever.retrieve(anyString())).thenReturn(new KbRetriever.Retrieval(
                List.of(new KbRetriever.Hit(e, 0.9, "vector")), 0.9, 0.9, 1, 0, List.of()));

        String out = new KbLookupTool(retriever).lookup("力量有什么用");
        assertThat(out).contains("力量、Strength").contains("力量提升生命上限。");
    }

    @Test
    @DisplayName("工具：没命中时明说没查到，不编内容")
    void toolSaysNotFound() {
        KbRetriever retriever = mock(KbRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(KbRetriever.Retrieval.empty());
        assertThat(new KbLookupTool(retriever).lookup("不存在的东西")).contains("没有查到");
    }

    @Test
    @DisplayName("★ 切换流程端到端：信封给模型、问题给检索、诊断回流、资料按契约注入")
    void flipFlowWorksEndToEnd() {
        FakeChatModel model = new FakeChatModel(null);
        KbRetriever.Hit h = hit("wiki-strength", "力量、Strength", "力量提升生命上限。", false, "2026-10-01");
        KbToolAssistant a = assistant(model, kbWith(augmentorLikeProduction(List.of(h))));

        KbRetrievalDiagnostics sink = new KbRetrievalDiagnostics();
        String reply = a.chat("发言者：小明\n<<<USER_CONTENT\n力量怎么加\nUSER_CONTENT>>>",
                "力量怎么加", sink, true);

        assertThat(reply).isEqualTo("最终答案");
        assertThat(model.lastUserText())
                .as("资料必须按契约注入，且注到给模型的那条消息上")
                .contains("<<<KNOWLEDGE").contains("力量提升生命上限。");
        // 注：诊断回流那条链路由 kb 包的两条测试分两半钉住 ——
        //   · transformer 用的是调用方给的容器（KbContextQueryTransformerTest）
        //   · 从容器重建统计（KbTraceFromDiagnosticsTest）
        // 这里不断言容器内容，因为往容器里写的是真检索器，不是桩。
    }

    @Test
    @DisplayName("★ 不要资料时：管线照走但不加标记，诊断也是空的")
    void flipFlowWithKnowledgeOff() {
        FakeChatModel model = new FakeChatModel(null);
        KbRetriever.Hit h = hit("wiki-strength", "力量、Strength", "力量提升生命上限。", false, "2026-10-01");
        KbToolAssistant a = assistant(model, kbWith(augmentorLikeProduction(List.of(h))));

        KbRetrievalDiagnostics sink = new KbRetrievalDiagnostics();
        a.chat("发言者：小明\n<<<USER_CONTENT\n力量怎么加\nUSER_CONTENT>>>", "力量怎么加", sink, false);

        assertThat(model.lastUserText())
                .as("关了资料就不该出现标记")
                .doesNotContain("<<<KNOWLEDGE");
    }

    @Test
    @DisplayName("★ 检索与注入在 AiServices 内部真的跑了：模型收到的用户消息带着 KNOWLEDGE 契约")
    void augmentorRunsInsideAiServices() {
        FakeChatModel model = new FakeChatModel(null);
        KbToolAssistant a = assistant(model, kbWith(augmentorReturning(List.of(
                KbContents.of(hit("ours", "灵火祭坛", "我们改过的正文", true, "2026-10-01"))))));

        a.chat("灵火祭坛在哪");

        String received = model.lastUserText();
        assertThat(received).as("用户原话必须还在").contains("灵火祭坛在哪");
        assertThat(received).contains("<<<KNOWLEDGE").contains("KNOWLEDGE>>>");
        assertThat(received).contains("【我们维护的内容】").contains("我们改过的正文");
    }

    @Test
    @DisplayName("★ 视觉入口：图片以 ImageContent 到达模型，资料同时按契约注入")
    void chatWithImagesKeepsImageContents() {
        FakeChatModel model = new FakeChatModel(null);
        KbRetriever.Hit h = hit("wiki-campfire", "营火、Campfire", "营火用来烹饪。", false, "2026-10-01");
        KbToolAssistant a = assistant(model, kbWith(augmentorLikeProduction(List.of(h))));

        KbRetrievalDiagnostics sink = new KbRetrievalDiagnostics();
        a.chatWithImages("<<<USER_CONTENT\n这个怎么用\nUSER_CONTENT>>>",
                List.of(new VisionImage("QUJD", "image/jpeg")),
                "这个怎么用", sink, true, "群:1/用户:2");

        UserMessage received = model.lastUserMessage();
        assertThat(received.contents())
                .as("图片必须以 ImageContent 到达模型 —— 塞进 UserMessage 参数会被框架 toString 成纯文本")
                .anyMatch(ImageContent.class::isInstance);
        assertThat(((TextContent) received.contents().get(0)).text())
                .as("信封与资料仍在同一条消息的文本里")
                .contains("<<<USER_CONTENT")
                .contains("<<<KNOWLEDGE");
        assertThat(received.contents())
                .as("只有图片也算一条视觉消息，不应被拆成两条")
                .hasSize(2);
    }
}
