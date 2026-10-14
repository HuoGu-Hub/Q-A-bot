package com.example.qqbot.kb;

import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 把 {@link KbRetrievalContext} 挂到进来的 {@link Query} 上 —— <b>官方管线的入口适配器</b>。
 *
 * <h2>它解决的问题</h2>
 * 我们的两条检索路都靠 {@code Query} 元数据里的上下文工作（语料快照、术语展开、已算好的问题向量）。
 * 而<b>外部入口会自己造 {@code Query}</b>：AiServices 驱动管线时 {@code KbRetriever.retrieve}
 * 根本不会被调用，那个 {@code Query} 里没有上下文 —— 检索路于是<b>静默返回空</b>，
 * 机器人拿不到资料却照常回答，不报错、不打日志、不降级。
 *
 * <p>这正是官方管线里 {@code QueryTransformer} 那一格的用途：在入口把上下文补齐，
 * 让管线从"只能被我们自己的入口驱动"变成"任何入口都能驱动"。
 *
 * <h2>为什么不覆盖已有的上下文</h2>
 * {@code KbRetriever.retrieve} 会自己带上上下文（还带着缓存与诊断）。那种情况下这里必须
 * <b>原样放行</b> —— 覆盖会丢掉诊断，也会让"一次检索只算一次问题向量"失效。
 *
 * <h2>为什么依赖零件而不是 KbRetriever</h2>
 * 依赖 KbRetriever 就是环：KbRetriever 的构造器要建 augmentor，augmentor 要带本类，
 * 本类又要 KbRetriever。所以这里只依赖 {@link KbContextFactory} 需要的那几个零件。
 */
@Component
public class KbContextQueryTransformer implements QueryTransformer {

    /** 调用方通过 {@link InvocationParameters} 传进来的键：检索用的问题 */
    public static final String KEY_QUERY = "kb.query";

    /** 调用方传进来的诊断容器 —— 他要在调用返回后读命中块与余弦，所以必须用他给的那个 */
    public static final String KEY_SINK = "kb.diagnostics";

    /**
     * 调用方传 {@code false} = 本次<b>不要资料</b>（后台"不用知识库"模式）。
     *
     * <p>做法是给一个<b>空语料快照</b>：两条检索路看到"没有块"就都无事可做，
     * 注入器拿不到内容自然不加标记。这样比"让调用方绕过管线"干净 ——
     * 编排仍然只有一条，开关只是喂给它的数据不同。
     */
    public static final String KEY_ENABLED = "kb.enabled";

    private final KbPolicy policy;
    private final KbCorpus corpus;
    private final Glossary glossary;
    private final EmbeddingClient embedding;

    public KbContextQueryTransformer(KbPolicy policy, KbCorpus corpus, Glossary glossary,
                                     EmbeddingClient embedding) {
        this.policy = policy;
        this.corpus = corpus;
        this.glossary = glossary;
        this.embedding = embedding;
    }

    @Override
    public Collection<Query> transform(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            // 防御性：官方 Query 的构造器已保证文本非空，所以"空白"这一支实际不可达。
            // 留着是因为这个类也接受外部直接传入的 Query 实现。
            return List.of();
        }
        if (KbRetrievalContext.of(query) != null) {
            // 已有上下文（KbRetriever.retrieve 那条路）—— 原样放行，别覆盖
            return List.of(query);
        }
        // ★ 检索用的问题与给模型的消息是两件事。
        //   给模型的是信封（发言者 / 引用 / 用户内容），而检索只该拿真正的问题去搜 ——
        //   拿信封去搜会让多出来的文字稀释查询向量、污染术语匹配，而且不会报错，只会答得变差。
        //   所以调用方通过 InvocationParameters 把真正的问题单独传进来。
        InvocationParameters params = query.metadata() == null
                ? null : query.metadata().invocationParameters();
        String kbQuery = query.text();
        if (params != null && params.get(KEY_QUERY) instanceof String s && !s.isBlank()) {
            kbQuery = s;
        }

        // 诊断容器用调用方给的那个：他要在返回后读命中块与最高余弦做统计，
        // 我们另起一个的话，统计会静默变空。
        KbRetrievalDiagnostics sink = new KbRetrievalDiagnostics();
        if (params != null && params.get(KEY_SINK) instanceof KbRetrievalDiagnostics given) {
            sink = given;
        }

        int k = Math.max(1, policy.getTopK());
        double minScore = policy.getMinScore();

        // 两个来源都要看：全局开关（app.kb.enabled）与本次调用方意愿。
        // 换了路径也不能绕过全局开关 —— 否则后台关掉知识库之后，这条新路照旧检索。
        boolean enabled = policy.isEnabled();
        if (params != null && params.get(KEY_ENABLED) instanceof Boolean b) {
            enabled = enabled && b;
        }
        sink.recordStart();

        // 关掉资料时给一个空快照与空向量：两条检索路都无事可做，注入器也不会加标记。
        // 不跳过管线是有意的 —— 编排只有一条，"不用资料"是喂给它的数据不同，不是另一条路。
        KbRetrievalContext ctx = enabled
                ? KbContextFactory.build(kbQuery, true, k, minScore, true, true,
                        corpus, glossary, embedding, sink)
                : new KbRetrievalContext(kbQuery, List.of(), List.of(), null, k,
                        Math.max(k * 4, 20), minScore, true, sink);
        return List.of(ctx.toQuery());
    }
}
