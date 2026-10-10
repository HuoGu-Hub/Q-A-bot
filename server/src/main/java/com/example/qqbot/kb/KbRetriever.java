package com.example.qqbot.kb;

import com.example.qqbot.trace.KbTrace;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 知识库检索 —— <b>编排</b>：A 路（正文向量）+ B 路（关键词）+ C 路（标题向量闸门）。
 *
 * <p><b>编排本身已经交给官方管线</b>：{@link DefaultRetrievalAugmentor} 负责
 * "变换查询 → 路由到检索器 → 聚合 → 注入"这条链，两条召回各自实现官方
 * {@link ContentRetriever}，融合与闸门在 {@link KbContentAggregator} 里。
 * 本类只剩三件事：
 * <ol>
 *   <li>把这次检索的输入准备好（语料快照、术语展开、问题向量）——
 *       它们必须"一次检索内只算一遍"，所以放在 {@link KbRetrievalContext} 里，
 *       通过官方的 {@code InvocationParameters} 传给各检索器</li>
 *   <li>调 {@code augment()}</li>
 *   <li>把过程记录组装成 {@link Retrieval}（问答统计与黄金集要的那些数字）</li>
 * </ol>
 *
 * <h2>为什么问题向量不由 A 路自己算</h2>
 * 官方 {@code EmbeddingStoreContentRetriever} 内部必然自己 embed，换它就得每轮多一次
 * 外部调用，而"一次检索只调一次 embedding、A 与 C 共用同一个问题向量"是钉死的验收。
 * 所以 A 路是自研的 {@link ContentRetriever}（{@link VectorContentRetriever}），
 * 它从上下文里读已经算好的向量，只把**向量检索**那一格交给官方 {@code EmbeddingStore}。
 *
 * <h2>C 路为什么是"闸门 + 置顶"而不是"再加一路进 RRF"</h2>
 * 语料 94% 的块标题是「中文名（English）」，而正文是简介、几乎不重复标题里的名字 ——
 * 用名字提问时 A 路<b>必然</b>把同族词排前面（「酸蚀之咬」本体排第 7）。
 * 这种"系统性错"的一路不该在 RRF 里有平等票权：实测 RRF 融合后 R@1 只有 49.5%，
 * 而"高阈值闸门 + 置顶"是 99.5%。
 *
 * <p><b>硬性约定：本类的方法绝不抛异常。</b>知识库只是锦上添花，
 * 它挂了、key 过期了、索引没建，机器人都必须照常回答 —— 只是回答时手里没有资料。
 */
@Component
public class KbRetriever {

    private static final Logger log = LoggerFactory.getLogger(KbRetriever.class);

    /**
     * 注入直通 —— 官方管线的注入那一格我们**不用**。
     *
     * <p>资料怎么拼进提示词由 agent 层决定（{@code <<<KNOWLEDGE>>>} 标记与两段出处，
     * 系统提示词第九章的规则挂在上面），所以这里原样返回。
     * 我们用 augment() 要的是"路由 + 检索 + 聚合"那一段，不是它的注入。
     */
    /**
     * 资料注入器：把聚合后的块渲染成 {@code <<<KNOWLEDGE>>>} 段，<b>在官方管线的最后一格完成</b>。
     *
     * <p>原来是直通，标记由 agent 层自己拼（{@code ChatService} 与 {@code FallbackService} 各拼一遍）。
     * 收进管线之后，"资料长什么样"只有一处决定 —— 换编排也不会漏掉标记，
     * 而标记漏了不会报错，只会让系统提示词第九章静默失效。
     *
     * <p>{@code TwoSectionInjector} 是无状态纯函数，这里直接 new 一个实例；
     * Spring 里那个 bean 是给 agent 层老路径用的。
     */
    private static final ContentInjector KNOWLEDGE_INJECTOR =
            new KbContentInjector(new TwoSectionInjector());

    private final KbPolicy props;
    private final KbCorpus corpus;
    private final Glossary glossary;
    private final EmbeddingClient embedding;
    private final RetrievalAugmentor augmentor;

    /** 同一问题的重复提问不再重复付两次外部调用（键里含语料版本，写完自动失效） */
    private final KbRetrievalCache cache;

    /**
     * 主构造器 —— 由 Spring 注入各段实现。
     *
     * <p>⚠️ <b>{@code @Autowired} 不能去掉。</b>本类有多个 public 构造器
     * （下面两个是给测试与黄金集跑手的便利构造）。Spring 遇到"多个构造器、
     * 且都没有 {@code @Autowired}"时会退回去找<b>无参</b>构造器 —— 找不到就报
     * {@code No default constructor found}，<b>整个应用起不来</b>。
     *
     * <p>两个 {@link ContentRetriever} 必须靠 {@code @Qualifier} 区分：
     * 容器里有两个候选，按类型注入会直接启动失败。
     */
    @Autowired
    public KbRetriever(KbPolicy props, KbCorpus corpus, Glossary glossary, EmbeddingClient embedding,
                       @Qualifier("vectorContentRetriever") ContentRetriever vectorRetriever,
                       @Qualifier("keywordContentRetriever") ContentRetriever keywordRetriever,
                       ContentAggregator aggregator,
                       QueryTransformer contextTransformer) {
        this(props, corpus, glossary, embedding,
                buildAugmentor(vectorRetriever, keywordRetriever, aggregator, contextTransformer));
    }

    /**
     * 便利构造：没有重排（等于关掉 {@code app.kb.rerank.enabled}）。
     *
     * <p>留着它主要是给<b>测试</b>用 —— 单测里不该为了验三路融合去构一个会发网络请求的客户端。
     */
    public KbRetriever(KbPolicy props, KbCorpus corpus, Glossary glossary, EmbeddingClient embedding) {
        // 显式转型：null 同时匹配 RerankClient 与 RetrievalAugmentor 两个重载
        this(props, corpus, glossary, embedding, (RerankClient) null);
    }

    /**
     * 便利构造：装配<b>默认实现</b>，不经过 Spring。
     *
     * <p>黄金集跑手走的就是这一条 —— 它要的是"真实检索器"，所以这里装的必须是
     * 与生产同一套实现，而不是任何测试替身。
     */
    public KbRetriever(KbPolicy props, KbCorpus corpus, Glossary glossary, EmbeddingClient embedding,
                       RerankClient rerankClient) {
        this(props, corpus, glossary, embedding, buildAugmentor(
                new VectorContentRetriever(corpus),
                new KeywordContentRetriever(corpus),
                new KbContentAggregator(props, new RrfFuser(), new TitleVectorGate(corpus),
                        new CrossEncoderReranker(props, rerankClient)),
                // 便利构造不经过 Spring，所以这里自己装一个 —— 黄金集跑手走的就是这条，
                // 它要的是"与生产同一套实现"，不能少了入口适配器
                new KbContextQueryTransformer(props, corpus, glossary, embedding)));
    }

    private KbRetriever(KbPolicy props, KbCorpus corpus, Glossary glossary, EmbeddingClient embedding,
                        RetrievalAugmentor augmentor) {
        this.props = props;
        this.corpus = corpus;
        this.glossary = glossary;
        this.embedding = embedding;
        this.augmentor = augmentor;
        // 容量在构造时定下：换容量要重启（见 KbPolicy#getCacheSize 的说明）
        this.cache = new KbRetrievalCache(props.getCacheSize());
    }

    private static RetrievalAugmentor buildAugmentor(ContentRetriever vector, ContentRetriever keyword,
                                                     ContentAggregator aggregator,
                                                     QueryTransformer contextTransformer) {
        return DefaultRetrievalAugmentor.builder()
                // ★ 入口适配器：外部入口（AiServices）会自己造 Query，那里面没有我们的上下文，
                //   两条检索路于是静默返回空 —— 机器人拿不到资料却照常回答。
                //   这里把上下文补上；已有上下文时（retrieve 那条路）原样放行。
                .queryTransformer(contextTransformer)
                // 广播给两条召回 —— 它们各有盲区，必须都跑（见 retrieve 的注释）
                .queryRouter(new DefaultQueryRouter(vector, keyword))
                .contentAggregator(aggregator)
                .contentInjector(KNOWLEDGE_INJECTOR)
                // 不借线程池：一次检索本来就该在一个线程里走完，
                // 而且我们的上下文是跟着 Query 走的，跨线程只会多一层不确定性
                .offloadBlocking(false)
                .build();
    }

    /**
     * 一条检索结果。
     *
     * @param source "vector" 或 "keyword"，用于日志排查（知道是走的哪条路）
     */
    public record Hit(KbCorpus.Entry entry, double score, String source) {
    }

    /**
     * 一次检索的完整结果。
     *
     * @param hits              融合后的结果（带进 prompt 的就是它们）
     * @param bestCosine        向量路里最高的余弦（卡完阈值之后的）
     * @param bestCosineRaw     卡阈值<b>之前</b>的最高余弦
     * @param vectorCandidates  向量路候选数
     * @param keywordCandidates 关键词路候选数
     */
    public record Retrieval(List<Hit> hits, double bestCosine, double bestCosineRaw,
                            int vectorCandidates, int keywordCandidates,
                            List<KbTrace.MatchedTerm> matchedTerms) {

        public static Retrieval empty() {
            return new Retrieval(List.of(), 0.0, 0.0, 0, 0, List.of());
        }
    }

    /**
     * 把检索结果转成中性的 {@link KbTrace}。
     *
     * <p><b>这个转换刻意留在 kb 里</b>：{@code trace} 包因此可以是零依赖的纯数据。
     */
    public static KbTrace traceOf(Retrieval r, long retrieveMs) {
        if (r == null) {
            return KbTrace.disabled();
        }
        return new KbTrace(true, traceHitsOf(r.hits()), r.bestCosine(), r.bestCosineRaw(),
                r.vectorCandidates(), r.keywordCandidates(), r.matchedTerms(), retrieveMs);
    }

    /**
     * 从<b>诊断容器</b>重建统计 —— 官方管线驱动的检索走这一条。
     *
     * <p><b>为什么需要它</b>：AiServices 驱动管线时 {@code retrieve} 不会被调用，
     * 检索发生在 augmentor 内部，{@code augment()} 也不把结果返回给调用方。
     * 命中块与余弦只留在诊断容器里，而问答统计要的正是它们 ——
     * 没有这条通道，统计会<b>静默变空</b>：机器人照常回答，后台数据却一片零。
     *
     * <p>各项算法与 {@link #retrieve} 里逐字一致，不另立口径。
     *
     * @param query 真正用于检索的问题。术语命中要拿它算，不能拿给模型的消息（那是信封）
     */
    public KbTrace traceOf(KbRetrievalDiagnostics diagnostics, String query, long retrieveMs) {
        if (diagnostics == null) {
            return KbTrace.disabled();
        }
        List<Hit> byVec = diagnostics.vectorHits();
        List<Hit> hits = diagnostics.finalHits();
        // 与 retrieve 一致：byVec 已按余弦降序，第一个就是**卡完阈值**之后的最高余弦
        double bestCosine = byVec.isEmpty() ? 0.0 : byVec.get(0).score();
        return new KbTrace(true, traceHitsOf(hits), bestCosine, diagnostics.bestCosineRaw(),
                diagnostics.vectorCandidates(), diagnostics.keywordCandidates(),
                matchedTermsOf(query, hits), retrieveMs);
    }

    /** 命中块 → 统计用的精简形状 */
    private static List<KbTrace.Hit> traceHitsOf(List<Hit> hits) {
        return hits.stream()
                .map(h -> new KbTrace.Hit(h.entry().id(), h.entry().title(), h.entry().docId(),
                        h.score(), h.source()))
                .toList();
    }

    /**
     * 这次提问命中了哪些术语，以及它们有没有真的出现在检索结果里。
     *
     * <p>原先这个计算在 {@code qa.QaCollector} 里做，于是记录系统得拿着 Glossary 和
     * 整个 Retrieval 自己算 —— 数据在哪、算法就该在哪。
     */
    private List<KbTrace.MatchedTerm> matchedTermsOf(String query, List<Hit> hits) {
        List<Glossary.Term> matched = safe(() -> glossary.matchChinese(query), List.of(), "术语匹配");
        if (matched.isEmpty()) {
            return List.of();
        }
        String haystack = hits.stream()
                .map(h -> (h.entry().title() + " " + h.entry().text()).toLowerCase(Locale.ROOT))
                .reduce("", (a, b) -> a + " " + b);
        List<KbTrace.MatchedTerm> out = new ArrayList<>();
        for (Glossary.Term term : matched) {
            boolean inKb = !haystack.isBlank() && haystack.contains(term.en().toLowerCase(Locale.ROOT));
            out.add(new KbTrace.MatchedTerm(term.zh(), term.en(), inKb));
        }
        return out;
    }

    /**
     * 检索。**A、B 两路都跑，按排名融合（RRF）**，任何一路失败都不影响另一路。
     *
     * <p><b>为什么不是"A 有结果就不跑 B"：</b>实测（全量 4,131 块）「废料杯怎么合成？」
     * 纯向量把 Scrap Cup 排到第 <b>69</b> 名、「卷毛山羊怎么驯服」把 Frizzy Goat 排到第 6 ——
     * 都进不了 top-5。而术语表里明明有「废料杯 → Scrap Cup」，
     * 关键词路一击就能把它顶到第一。<b>两条路各有盲区，必须都跑。</b>
     */
    public Retrieval retrieve(String query) {
        return retrieve(query, true);
    }

    /**
     * 检索（可选择是否走向量路）。
     *
     * @param allowEmbedding 是否允许走向量路。向量路每次调用都要请求外部模型 API，
     *        所以<b>公开接口不能直接用它</b> —— 要走
     *        {@link com.example.qqbot.publicapi.PublicSearchService}，
     *        那里有结果缓存和按天配额兜底。关掉后只用本地关键词路。
     */
    public Retrieval retrieve(String query, boolean allowEmbedding) {
        return retrieve(query, allowEmbedding, props.getTopK(), props.getMinScore());
    }

    /**
     * 检索（显式指定条数与阈值）。
     *
     * <p><b>为什么需要这个重载</b>：群内问答和公开站搜索的目标完全不同 ——
     * 群内是"给模型当依据"，宁缺毋滥（阈值 0.45、只要 5 条）；
     * 公开站是"按相关度排给人看"，要拿得多一点、阈值松一点（见 {@link PublicSearch}）。
     */
    public Retrieval retrieve(String query, boolean allowEmbedding, int topK, double minScore) {
        return retrieve(query, allowEmbedding, topK, minScore, true);
    }

    /**
     * 检索（可选择是否重排）。
     *
     * @param allowRerank 是否允许送 cross-encoder 重排。公开站搜索会<b>明确关掉</b>它：
     *        那里有按天配额与 LRU 缓存，多一次外部调用不划算。
     */
    public Retrieval retrieve(String query, boolean allowEmbedding, int topK, double minScore, boolean allowRerank) {
        if (!props.isEnabled() || query == null || query.isBlank()) {
            return Retrieval.empty();
        }
        int k = Math.max(1, topK);
        // 每路的候选数：最终条数的 4 倍（最少 20）—— 留够余量给 RRF 融合和调用方去重
        int pool = Math.max(k * 4, 20);

        // 缓存键里带语料版本与检索参数：写完语料版本就变，旧条目自然不再命中；
        // 群内与公开站的阈值/条数不同，参数不同就是不同的问题，不能互相命中。
        long corpusVersion = safe(() -> corpus.version(), 0L, "读取语料版本");
        KbRetrievalCache.Key cacheKey = new KbRetrievalCache.Key(
                corpusVersion, query, allowEmbedding, k, minScore, allowRerank);
        Retrieval cached = cache.get(cacheKey);
        if (cached != null) {
            log.debug("[KB] 命中检索缓存（语料版本 {}）：{}", corpusVersion, query);
            return cached;
        }

        KbRetrievalDiagnostics diagnostics = new KbRetrievalDiagnostics();
        KbRetrievalContext ctx = newContext(query, allowEmbedding, k, minScore, allowRerank, diagnostics);

        try {
            augmentor.augment(new AugmentationRequest(UserMessage.from(query), ctx.toQuery().metadata()));
        } catch (Exception e) {
            // 编排层挂了 = 这次没有资料，但机器人照常回答
            log.warn("[KB] 检索管线异常，本次不带资料回答：{}", e.getMessage());
            return new Retrieval(List.of(), 0.0, diagnostics.bestCosineRaw(), 0, 0, List.of());
        }

        List<Hit> byVec = diagnostics.vectorHits();
        List<Hit> byKey = diagnostics.keywordHits();
        TitleGate gate = diagnostics.gate();

        // byVec 已按余弦降序，第一个就是最高余弦（注意：这是**卡完阈值**之后的）
        double bestCosine = byVec.isEmpty() ? 0.0 : byVec.get(0).score();
        // 卡阈值【之前】的最高余弦。未命中时 bestCosine 必然是 0，只有它能说明
        // 到底是"库里根本没有相关块"还是"差一点点被阈值挡了"
        double bestCosineRaw = diagnostics.bestCosineRaw();

        if (byVec.isEmpty() && byKey.isEmpty() && !gate.hit()) {
            log.debug("[KB] 三路都没有命中（A 路最高相似度 {}，阈值 {}；标题路最高 {}，闸门 {}）",
                    String.format("%.3f", bestCosineRaw), String.format("%.3f", minScore),
                    String.format("%.3f", gate.cosine()), String.format("%.3f", props.getTitleGate()));
            Retrieval nothing = new Retrieval(List.of(), bestCosine, bestCosineRaw, 0, 0, List.of());
            cache.put(cacheKey, nothing);
            return nothing;
        }

        List<Hit> fused = diagnostics.finalHits();
        log.debug("[KB] 融合后取 {} 块：{}（向量候选 {}，关键词候选 {}，最高余弦 {}）",
                fused.size(), fused.stream().map(h -> h.entry().title()).toList(),
                byVec.size(), byKey.size(), String.format("%.3f", bestCosine));
        Retrieval result = new Retrieval(fused, bestCosine, bestCosineRaw, byVec.size(), byKey.size(),
                matchedTermsOf(query, fused));
        cache.put(cacheKey, result);
        return result;
    }

    /**
     * 官方编排器 —— 交给 AiServices 驱动。
     *
     * <p>AiServices 自己会跑 augmentor，所以它需要一个入口；
     * 而"我们的检索长什么样"只有这里知道。
     */
    public RetrievalAugmentor augmentor() {
        return augmentor;
    }

    /**
     * 建一次检索的共享输入：语料快照、术语展开、问题向量。
     *
     * <p><b>为什么抽成一个方法</b>：这套东西原来只在 {@link #retrieve} 里建。
     * 而 AiServices 驱动管线时 {@code retrieve} <b>不会被调用</b> —— 它自己造 {@code Query}，
     * 两条检索路取不到上下文就<b>静默返回空</b>，机器人拿不到资料却照常回答。
     * {@link KbContextQueryTransformer} 要用同一套逻辑把上下文挂上去，
     * 所以"上下文怎么建"只能有一处。
     */
    KbRetrievalContext newContext(String query, boolean allowEmbedding, int k, double minScore,
                                  boolean allowRerank, KbRetrievalDiagnostics diagnostics) {
        return KbContextFactory.build(query, allowEmbedding, k, minScore, allowRerank,
                corpus, glossary, embedding, diagnostics);
    }

    /**
     * 群内默认参数下的上下文 —— 外部入口（AiServices）驱动管线时由
     * {@link KbContextQueryTransformer} 调它。
     */
    public KbRetrievalContext defaultContext(String query) {
        return newContext(query, true, Math.max(1, props.getTopK()), props.getMinScore(), true,
                new KbRetrievalDiagnostics());
    }

    /**
     * C 路的判定结果。
     *
     * @param entry   闸门命中的那块（未命中时是 {@code null}）
     * @param cosine  标题向量的最高余弦（<b>不管有没有过闸门</b>，调阈值就看它）
     * @param hit     是否 ≥ 闸门阈值
     */
    public record TitleGate(KbCorpus.Entry entry, double cosine, boolean hit) {
        static final TitleGate NONE = new TitleGate(null, 0.0, false);
    }

    /**
     * 把问题向量化。
     *
     * <p>和 A 路原来的写法一样：<b>绝不抛异常</b>。拿不到向量就返回 {@code null}，
     * A 路和 C 路一起跳过（B 路是纯本地的，照常跑）。
     */
    private float[] safeVector(String query) {
        return KbContextFactory.safeVector(query, corpus, embedding);
    }

    /** 带兜底值的 safe —— 术语展开/读语料挂了也不影响检索 */
    private <T> T safe(java.util.function.Supplier<T> supplier, T fallback, String label) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("[KB] {}失败（不影响检索）：{}", label, e.getMessage());
            return fallback;
        }
    }

    /**
     * 聚合型提问（"X有什么"）的意图修正：把<b>已经在候选里</b>的地点块提到最前。
     *
     * <p>实测动机见 {@link PlaceIntent}：问「空洞大厅有什么」时地点块排第 7（0.9558），
     * 被 6 条名字带"空洞大厅"的物品挤出前五，而第 1 名 0.9701 只高 0.014 ——
     * 阈值分不开，这是意图问题。这里<b>只改顺序、不造分</b>。
     */
    static List<Hit> applyPlaceIntent(List<Hit> hits, String query) {
        if (hits == null || hits.size() < 2 || !PlaceIntent.isAggregate(query)) {
            return hits;
        }
        String place = PlaceIntent.placeTerm(query);
        List<String> docIds = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (Hit h : hits) {
            docIds.add(h.entry().docId());
            titles.add(h.entry().title());
        }
        int at = PlaceIntent.findPlaceBlock(docIds, titles, place, PlaceIntent.PLACE_DOC_ID);
        if (at < 0) {
            return hits;
        }
        List<Hit> out = new ArrayList<>(hits);
        out.add(0, out.remove(at));
        return out;
    }

}
