package com.example.qqbot.kb;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 入口适配器的验收 —— 它防的是<b>静默失败</b>。
 *
 * <p>外部入口（AiServices）会自己造 {@code Query}，那里面没有我们的上下文，
 * 两条检索路会返回空。不报错、不打日志、不降级，表现是"机器人答得没依据"。
 * 所以这条测试必须钉住"上下文被挂上去了"，而不是钉某个返回值。
 */
class KbContextQueryTransformerTest {

    private static KbContextQueryTransformer transformer() {
        KbPolicy policy = mock(KbPolicy.class);
        when(policy.isEnabled()).thenReturn(true); // 全局知识库开关，默认关
        when(policy.getTopK()).thenReturn(5);
        when(policy.getMinScore()).thenReturn(0.45);

        KbCorpus corpus = mock(KbCorpus.class);
        when(corpus.isReady()).thenReturn(false); // 不让它真去 embed
        when(corpus.entries()).thenReturn(List.of());

        Glossary glossary = mock(Glossary.class);
        when(glossary.expand(anyString())).thenReturn(List.of("力量"));

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isAvailable()).thenReturn(false);

        return new KbContextQueryTransformer(policy, corpus, glossary, embedding);
    }

    @Test
    @DisplayName("★ 外部入口造的空 Query → 挂上上下文，否则检索会静默返回空")
    void attachesContextWhenMissing() {
        Collection<Query> out = transformer().transform(Query.from("力量有什么用"));

        assertThat(out).hasSize(1);
        KbRetrievalContext ctx = KbRetrievalContext.of(out.iterator().next());
        assertThat(ctx).as("上下文必须挂上 —— 挂不上两条检索路都会返回空").isNotNull();
        assertThat(ctx.query()).isEqualTo("力量有什么用");
        assertThat(ctx.terms()).contains("力量");
        assertThat(ctx.topK()).isEqualTo(5);
        assertThat(ctx.minScore()).isEqualTo(0.45);
    }

    @Test
    @DisplayName("★ 已带上下文的 Query → 原样放行，不重建（否则丢诊断、多一次 embedding）")
    void passesThroughWhenContextPresent() {
        KbRetrievalContext original = new KbRetrievalContext(
                "力量有什么用", List.of(), List.of("已有术语"), null, 3, 12, 0.30, false, null);
        Query withContext = original.toQuery();

        Collection<Query> out = transformer().transform(withContext);

        assertThat(out).containsExactly(withContext);
        assertThat(KbRetrievalContext.of(withContext).terms())
                .as("原样放行意味着没有被重建：术语还是原来那个")
                .containsExactly("已有术语");
    }

    /** 造一个"带着调用方参数"的官方 Query —— 与 KbRetrievalContext.toQuery() 同构 */
    private static Query queryWith(InvocationParameters params, String text) {
        InvocationContext invocation = InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .timestampNow()
                .invocationParameters(params)
                .build();
        Metadata metadata = Metadata.builder()
                .chatMessage(UserMessage.from(text))
                .invocationContext(invocation)
                .build();
        return Query.from(text, metadata);
    }

    @Test
    @DisplayName("★ 检索用调用方传的问题，而不是整个信封")
    void usesQueryFromInvocationParameters() {
        InvocationParameters params = new InvocationParameters();
        params.put(KbContextQueryTransformer.KEY_QUERY, "力量怎么加");
        KbRetrievalDiagnostics sink = new KbRetrievalDiagnostics();
        params.put(KbContextQueryTransformer.KEY_SINK, sink);

        // 给模型的是信封：发言者 + 用户内容。拿它去检索会稀释查询向量、污染术语匹配
        Query envelope = queryWith(params, "发言者：小明\n<<<USER_CONTENT\n力量怎么加\nUSER_CONTENT>>>");

        Collection<Query> out = transformer().transform(envelope);
        KbRetrievalContext ctx = KbRetrievalContext.of(out.iterator().next());

        assertThat(ctx.query())
                .as("检索问题必须是真正的问题，不能是整个信封")
                .isEqualTo("力量怎么加");
        assertThat(ctx.diagnostics())
                .as("必须用调用方给的诊断容器 —— 另起一个的话统计会静默变空")
                .isSameAs(sink);
    }

    @Test
    @DisplayName("★ 调用方说不要资料 → 空快照与空向量，两条检索路都无事可做")
    void disabledMeansEmptySnapshot() {
        InvocationParameters params = new InvocationParameters();
        params.put(KbContextQueryTransformer.KEY_ENABLED, false);

        KbRetrievalContext ctx = KbRetrievalContext.of(
                transformer().transform(queryWith(params, "力量怎么加")).iterator().next());

        assertThat(ctx).isNotNull();
        assertThat(ctx.entries()).as("空语料 = 检索路拿不到任何块").isEmpty();
        assertThat(ctx.queryVector()).as("空向量 = 向量路直接跳过").isNull();
    }

    @Test
    @DisplayName("调用方没传问题 → 退回用 Query 文本，不至于不检索")
    void fallsBackToQueryText() {
        InvocationParameters params = new InvocationParameters();

        Collection<Query> out = transformer().transform(queryWith(params, "力量怎么加"));
        KbRetrievalContext ctx = KbRetrievalContext.of(out.iterator().next());

        assertThat(ctx.query()).isEqualTo("力量怎么加");
        assertThat(ctx.diagnostics()).isNotNull();
    }

    @Test
    @DisplayName("null 问题 → 返回空集合，不造上下文")
    void nullQueryYieldsNothing() {
        // 注：**空白问题到不了这里** —— 官方 Query 的构造器本身就拒绝空文本
        // （IllegalArgumentException: text cannot be null or blank）。
        // 所以下面那个判断是防御性的，真正可达的只有 null。
        assertThat(transformer().transform(null)).isEmpty();
    }
}
