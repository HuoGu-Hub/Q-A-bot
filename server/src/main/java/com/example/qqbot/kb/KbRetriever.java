package com.example.qqbot.kb;

import com.example.qqbot.config.KbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库检索 —— **A 路（正文向量）+ B 路（关键词）+ C 路（标题向量闸门）**。
 *
 * <pre>
 *                        用户问题
 *                            │
 *                  ┌─────────┼──────────────┐
 *                  ▼         ▼              ▼
 *          【A 路】向量   【B 路】关键词  【C 路】标题向量
 *          正文向量余弦   术语表字面匹配   标题向量余弦 ≥ 闸门？
 *                  │         │              │（名字直击，置顶）
 *                  └────┬────┘              │
 *                       ▼                   │
 *                  RRF 融合 top-k ◀─────────┘
 *                       ▼
 *                  拼进 prompt
 * </pre>
 *
 * <p><b>C 路为什么是"闸门 + 置顶"而不是"再加一路进 RRF"</b>（2026-10-01 实测，见
 * `docs/md/知识库标题向量召回方案.md`）：语料 94% 的块标题是「中文名（English）」，
 * 而正文是简介、几乎不重复标题里的名字 —— 用名字提问时 A 路**必然**把同族词排前面
 * （「酸蚀之咬」本体排第 7，前面是永恒酸蚀咬击、酸蚀砍刀…）。这种"系统性错"的一路
 * 不该在 RRF 里有平等票权：实测 RRF 融合后 R@1 只有 49.5%，而"高阈值闸门 + 置顶"是 99.5%。
 * 同时 C 路必须带闸门：真实口语提问里标题路和正文路的 top1 只有 9.2% 重合，
 * 无条件融合会把一半口语提问的答案换掉（实测 50.8%）。
 *
 * <p><b>语料从哪来不归它管</b>：本类只认 {@link KbCorpus}，
 * 当前由 {@code KbBlockIndex}（id 即身份）实现 ——
 * 换存储 = 换一个 Bean，**算法这一份不用动**。
 * 这是有意的：下面那些权重和加成都是实测调出来的，复制成两份必然漂移。
 *
 * <p><b>硬性约定：本类的方法绝不抛异常。</b>知识库只是锦上添花，
 * 它挂了、key 过期了、索引没建，机器人都必须照常回答 —— 只是回答时手里没有资料。
 */
@Component
public class KbRetriever {

    private static final Logger log = LoggerFactory.getLogger(KbRetriever.class);

    /** 关键词检索里，命中标题的权重（标题比正文重要得多） */
    private static final double TITLE_WEIGHT = 3.0;

    /**
     * RRF（Reciprocal Rank Fusion）融合参数。
     *
     * <p>两路的分数量纲完全不同（余弦 0~1 vs 词频累加），不能直接相加，
     * 所以按**排名**融合：{@code score = Σ w / (K + rank)}。
     */
    private static final int RRF_K = 10;

    /** 关键词路的权重。它一旦命中就是"术语表里确实有这个游戏名词"，精度很高，所以给更大权重 */
    private static final double KEYWORD_WEIGHT = 2.0;

    /**
     * 标题**正好等于**查询里的游戏名词时的加成。
     *
     * <p>实测的问题：搜「废料杯」时，正文里提到 Scrap Cup 的 Tableware 页面
     * 会因为两路都排得靠前而压过 Scrap Cup 本体；搜「火把」时
     * Crypt Skull Torch 会排在 Torch 前面。**用户打的是物品名，要的就是那一页**，
     * 所以标题精确命中直接给一个足够大的加成盖过排名差异。
     */
    private static final double TITLE_EXACT_BONUS = 0.5;

    /** 标题里出现了这个词（但不是全部）时的少量加成 */
    private static final double TITLE_PARTIAL_BONUS = 0.15;

    /**
     * 置顶那一条在 RRF 量纲下的报告分。
     *
     * <p>闸门命中的块不是通过排名融合进来的，本来没有 RRF 分；但 {@code Hit.score}
     * 会进问答统计，混一个余弦进来会把统计口径搞乱。所以给它一个"某一路的第 1 名"
     * 该有的值（1/(K+1)）—— 只用于展示，不参与任何排序。
     */
    private static final double GATE_FUSED_SCORE = 1.0 / (RRF_K + 1);

    private final KbProperties props;
    private final KbCorpus corpus;
    private final Glossary glossary;
    private final EmbeddingClient embedding;
    private final RerankClient rerankClient;

    public KbRetriever(KbProperties props, KbCorpus corpus, Glossary glossary, EmbeddingClient embedding,
                       RerankClient rerankClient) {
        this.props = props;
        this.corpus = corpus;
        this.glossary = glossary;
        this.embedding = embedding;
        this.rerankClient = rerankClient;
    }

    /**
     * 便利构造：没有重排（等于关掉 {@code app.kb.rerank.enabled}）。
     *
     * <p>留着它主要是给**测试**用 —— 单测里不该为了验三路融合去构一个会发网络请求的客户端。
     */
    public KbRetriever(KbProperties props, KbCorpus corpus, Glossary glossary, EmbeddingClient embedding) {
        this(props, corpus, glossary, embedding, null);
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
     * <p><b>为什么要把 bestCosine 单独带出来</b>：融合后的 Hit.score 是 RRF 分数（0.1 量级），
     * 而调 min-score 阈值需要的是**余弦相似度**（0~1 量级）。两者量纲完全不同，
     * 混在一起存进数据库就没法用来调阈值了。
     *
     * @param hits              融合后的结果（带进 prompt 的就是它们）
     * @param bestCosine        向量路里最高的余弦（低于阈值调优用的就是它）
     * @param vectorCandidates  向量路候选数
     * @param keywordCandidates 关键词路候选数
     */
    public record Retrieval(List<Hit> hits, double bestCosine, double bestCosineRaw,
                            int vectorCandidates, int keywordCandidates) {

        public static Retrieval empty() {
            return new Retrieval(List.of(), 0.0, 0.0, 0, 0);
        }
    }

    /**
     * 检索。**A、B 两路都跑，按排名融合（RRF）**，任何一路失败都不影响另一路。
     *
     * <p><b>为什么不是"A 有结果就不跑 B"：</b>实测（全量 4,131 块）「废料杯怎么合成？」
     * 纯向量把 Scrap Cup 排到第 **69** 名、「卷毛山羊怎么驯服」把 Frizzy Goat 排到第 6 ——
     * 都进不了 top-5。而术语表里明明有「废料杯 → Scrap Cup」，
     * 关键词路一击就能把它顶到第一。**两条路各有盲区，必须都跑。**
     */
    public Retrieval retrieve(String query) {
        return retrieve(query, true);
    }

    /**
     * 检索（可选择是否走向量路）。
     *
     * @param allowEmbedding 是否允许走向量路。向量路每次调用都要请求外部模型 API，
     *        所以**公开接口不能直接用它** —— 要走
     *        {@link com.example.qqbot.publicapi.PublicSearchService}，
     *        那里有结果缓存和按天配额兜底。关掉后只用本地关键词路
     *        （查术语表 + 扫内存索引），零外部成本、毫秒级。
     */
    public Retrieval retrieve(String query, boolean allowEmbedding) {
        return retrieve(query, allowEmbedding, props.getTopK(), props.getMinScore());
    }

    /**
     * 检索（显式指定条数与阈值）。
     *
     * <p><b>为什么需要这个重载</b>：群内问答和公开站搜索的目标完全不同 ——
     * 群内是"给模型当依据"，宁缺毋滥（阈值 0.45、只要 5 条）；
     * 公开站是"按相关度排给人看"，要拿得多一点、阈值松一点（见
     * {@link com.example.qqbot.config.KbProperties.PublicSearch}）。
     * 用同一套参数会两头不讨好。
     *
     * @param topK     融合后取前几条
     * @param minScore 向量路的相似度下限
     */
    public Retrieval retrieve(String query, boolean allowEmbedding, int topK, double minScore) {
        return retrieve(query, allowEmbedding, topK, minScore, true);
    }

    /**
     * 检索（可选择是否重排）。
     *
     * @param allowRerank 是否允许送 cross-encoder 重排。公开站搜索会**明确关掉**它：
     *        那里有按天配额与 LRU 缓存，多一次外部调用不划算，而且它的目标是"按相关度排给人看"，
     *        不是"决定要不要给资料"。
     */
    public Retrieval retrieve(String query, boolean allowEmbedding, int topK, double minScore, boolean allowRerank) {
        if (!props.isEnabled() || query == null || query.isBlank()) {
            return Retrieval.empty();
        }
        int k = Math.max(1, topK);
        // 每路的候选数：最终条数的 4 倍（最少 20）—— 留够余量给 RRF 融合和调用方去重
        int pool = Math.max(k * 4, 20);

        // 语料只取一次快照：一次检索内看到的世界必须是一致的（写入是另一个线程）
        List<KbCorpus.Entry> entries = safe(() -> corpus.entries(), List.of(), "读取语料");

        // 术语展开只做一次，喂给关键词路和标题加成共用（matchChinese 内部要排序 3,600 条，别重复做）
        List<String> terms = safe(() -> glossary.expand(query), List.of(), "术语展开");

        // ★ 问题向量只算一次，A 路和 C 路共用：
        //   一次检索最多只该有一次外部 embedding 调用（省的是钱，也是 200~300ms 延迟）。
        float[] qv = allowEmbedding ? safeVector(query) : null;

        VectorPath vec = qv == null
                ? new VectorPath(List.of(), 0.0)
                : safe(() -> byVector(entries, qv, pool, minScore), new VectorPath(List.of(), 0.0), "A 路（向量）");
        List<Hit> byVec = vec.hits();
        List<Hit> byKey = safe(() -> byKeyword(entries, pool, terms), "B 路（关键词）");
        // C 路：标题向量闸门。命中就把那一条置顶（不参与 RRF 投票，理由见类注释）
        TitleGate gate = qv == null
                ? TitleGate.NONE
                : safe(() -> titleGate(entries, qv, props.getTitleGate()), TitleGate.NONE, "C 路（标题向量）");
        // byVec 已按余弦降序，第一个就是最高余弦（注意：这是**卡完阈值**之后的）
        double bestCosine = byVec.isEmpty() ? 0.0 : byVec.get(0).score();
        // 卡阈值【之前】的最高余弦。未命中时 bestCosine 必然是 0，只有它能说明
        // 到底是"库里根本没有相关块(比如 0.2)"还是"差一点点被阈值挡了(0.43)"
        double bestCosineRaw = vec.rawBestCosine();

        if (byVec.isEmpty() && byKey.isEmpty() && !gate.hit()) {
            log.debug("[KB] 三路都没有命中（A 路最高相似度 {}，阈值 {}；标题路最高 {}，闸门 {}）",
                    String.format("%.3f", bestCosineRaw), String.format("%.3f", minScore),
                    String.format("%.3f", gate.cosine()), String.format("%.3f", props.getTitleGate()));
            return new Retrieval(List.of(), bestCosine, bestCosineRaw, 0, 0);
        }
        // 要重排就必须融合出**多于 top-k** 的候选，否则重排没有翻盘空间（实测"藏红花幼苗 > 藏红花"
        // 这类错序，只有把本体留在候选池里才可能被抬上来）
        boolean doRerank = allowRerank && rerankClient != null && rerankClient.isAvailable();
        int fuseLimit = doRerank ? Math.max(k, Math.max(1, props.getRerank().getCandidateLimit())) : k;

        List<Hit> fused = fuse(byVec, byKey, fuseLimit, terms);
        fused = pinGate(fused, gate, fuseLimit);
        if (doRerank) {
            fused = rerank(query, fused, k, gate.hit());
        } else if (fused.size() > k) {
            fused = fused.subList(0, k);
        }
        log.debug("[KB] 融合后取 {} 块：{}（向量候选 {}，关键词候选 {}，最高余弦 {}）",
                fused.size(), fused.stream().map(h -> h.entry().title()).toList(),
                byVec.size(), byKey.size(), String.format("%.3f", bestCosine));
        return new Retrieval(fused, bestCosine, bestCosineRaw, byVec.size(), byKey.size());
    }

    /**
     * C 路的判定结果。
     *
     * @param entry   闸门命中的那块（未命中时是 {@code null}）
     * @param cosine  标题向量的最高余弦（**不管有没有过闸门**，调阈值就看它）
     * @param hit     是否 ≥ 闸门阈值
     */
    public record TitleGate(KbCorpus.Entry entry, double cosine, boolean hit) {
        static final TitleGate NONE = new TitleGate(null, 0.0, false);
    }

    /**
     * 把问题向量化。
     *
     * <p>和 A 路原来的写法一样：**绝不抛异常**。拿不到向量就返回 {@code null}，
     * A 路和 C 路一起跳过（B 路是纯本地的，照常跑）。
     */
    private float[] safeVector(String query) {
        try {
            if (!embedding.isAvailable() || !corpus.isReady()) {
                return null;
            }
            float[] qv = embedding.embedOne(query);
            if (qv == null || qv.length != corpus.dimensions()) {
                return null;
            }
            return qv;
        } catch (Exception e) {
            log.warn("[KB] 问题向量化失败（A/C 路跳过，B 路照常）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * C 路：拿问题向量去比**标题向量**，取最高的一条。
     *
     * <p>阈值由 {@code app.kb.title-gate} 给（默认 0.70）。这个阈值和 A 路的
     * {@code min-score}（0.45）是两个不同的东西：后者问"这段正文够不够相关"，
     * 前者问"这次提问是不是就是在问某个名字"。实测（200 条名字查询 + 120 条真实口语提问）：
     * 0.70 时名字查询 100% 触发、口语提问只有 2.5% 误触发；放到 0.65 误触发涨到 10%，
     * 提到 0.80 则名字覆盖率掉到 95.5%。所以别随手调，调之前看日志里的余弦分布。
     */
    private TitleGate titleGate(List<KbCorpus.Entry> entries, float[] qv, double threshold) {
        if (threshold <= 0 || entries.isEmpty()) {
            return TitleGate.NONE;
        }
        KbCorpus.Entry best = null;
        double bestCos = 0.0;
        for (KbCorpus.Entry e : entries) {
            float[] tv = e.titleVector();
            // 维度对不上说明标题向量是别的模型算的 —— 静默跳过，别拿它算一个"看着有值"的错余弦
            if (tv == null || tv.length != qv.length) {
                continue;
            }
            double c = cosine(qv, tv);
            if (c > bestCos) {
                bestCos = c;
                best = e;
            }
        }
        boolean hit = best != null && bestCos >= threshold;
        if (hit) {
            log.info("[KB-TITLE] 闸门命中 id={} 余弦 {} ≥ {}（已置顶：{}）",
                    best.id(), String.format("%.3f", bestCos), String.format("%.3f", threshold),
                    best.title());
        } else {
            log.debug("[KB-TITLE] 闸门未命中（最高 {} < {}，块={}）",
                    String.format("%.3f", bestCos), String.format("%.3f", threshold),
                    best == null ? "（没有标题向量）" : best.title());
        }
        return new TitleGate(best, bestCos, hit);
    }

    /**
     * 闸门命中 → 把那一条提到第 1 位。
     *
     * <p>其余条目的相对顺序保持不变（不做任何分数混合）：这样"多一路"带来的
     * 唯一变化就是"这一条排最前"，口语提问即使被误触发，代价也只是首条换了，
     * 而不是整张表重排。
     *
     * <p>它可能压根不在融合结果里（A 路没过阈值、B 路也没命中字面）——
     * 那就补进来占第 1 位，并挤掉最后一个。
     */
    private List<Hit> pinGate(List<Hit> fused, TitleGate gate, int topK) {
        if (!gate.hit() || gate.entry() == null) {
            return fused;
        }
        String id = gate.entry().id();
        List<Hit> rest = new ArrayList<>();
        Hit existing = null;
        for (Hit h : fused) {
            if (h.entry().id().equals(id)) {
                existing = h;
            } else {
                rest.add(h);
            }
        }
        Hit pinned = existing != null
                // 来源标成 x+title：既保留它原来是被哪一路找到的，又能让统计看出"闸门开过"
                ? new Hit(existing.entry(), existing.score(), existing.source() + "+title")
                : new Hit(gate.entry(), GATE_FUSED_SCORE, "title");
        List<Hit> out = new ArrayList<>();
        out.add(pinned);
        for (Hit h : rest) {
            if (out.size() >= topK) {
                break;
            }
            out.add(h);
        }
        return out;
    }

    /**
     * 重排：把融合后的候选交给 cross-encoder，并用**它的分数**决定"这次到底有没有资料"。
     *
     * <p><b>为什么不重排就没有区分度</b>（2026-10-02 黄金集实测）：双塔余弦"该答"的问题最低 0.548、
     * "库里根本没有"的问题最高 0.559，分布完全重叠 —— 所以调 min-score 是死路。
     * cross-encoder 把问题和文档拼在一起看，闲聊 top 分 ≤0.002、真实问题 ≥0.04，才有可分性。
     *
     * <p><b>fail-open</b>：重排挂了就保持原 RRF 顺序照常回答（最多是排序差一点），
     * 绝不因为"锦上添花的一层"失败而不回答。
     *
     * @param gateHit C 路闸门是否命中。命中说明"用户就是在问这个实体名"（标题向量余弦 ≥0.70），
     *                此时**不允许"一条都不给"** —— 那种情况下丢掉资料是明确的错。
     */
    private List<Hit> rerank(String query, List<Hit> fused, int topK, boolean gateHit) {
        if (fused.isEmpty()) {
            return fused;
        }
        KbProperties.Rerank cfg = props.getRerank();
        int limit = Math.min(fused.size(), Math.max(1, cfg.getCandidateLimit()));
        List<Hit> candidates = fused.subList(0, limit);
        List<String> docs = new ArrayList<>(candidates.size());
        for (Hit h : candidates) {
            // 标题必须一起送：语料标题是「中文名（English）」，实体名就在标题里。
            // 实测「氨液腺」→「氨腺」字面完全不重合，正是靠标题那半截才被重排认出来（0.596、第 1 名）。
            docs.add(h.entry().title() + "\n" + h.entry().text());
        }

        double[] scores;
        try {
            scores = rerankClient.score(query, docs);
        } catch (Exception e) {
            log.warn("[KB-RERANK] 重排失败，保持 RRF 顺序继续回答：{}", e.getMessage());
            return fused.size() <= topK ? fused : fused.subList(0, topK);
        }

        List<Hit> ranked = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Hit h = candidates.get(i);
            double s = i < scores.length ? scores[i] : 0.0;
            // 分数换成重排分：它才是有区分度的相关度，进问答统计比 RRF 排名分更可解释
            ranked.add(new Hit(h.entry(), s, h.source() + "+rerank"));
        }
        ranked.sort(Comparator.comparingDouble(Hit::score).reversed());
        double best = ranked.get(0).score();

        if (!gateHit && cfg.getMinScore() > 0 && best < cfg.getMinScore()) {
            log.info("[KB-RERANK] 最高重排分 {} < {}，判定库里没有相关资料 —— 本次一条都不给（丢弃 {} 条候选）",
                    String.format("%.4f", best), String.format("%.4f", cfg.getMinScore()), ranked.size());
            return List.of();
        }
        log.info("[KB-RERANK] 重排完成：最高 {}（{}），前 {} 条取自候选 {} 条",
                String.format("%.3f", best), ranked.get(0).entry().title(),
                Math.min(topK, ranked.size()), ranked.size());
        return ranked.size() <= topK ? ranked : ranked.subList(0, topK);
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

    /** 一路失败不能影响另一路 —— 知识库只是锦上添花 */
    private List<Hit> safe(java.util.function.Supplier<List<Hit>> supplier, String label) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("[KB] {}失败（不影响另一路）：{}", label, e.getMessage());
            return List.of();
        }
    }

    /**
     * RRF 融合：按排名而不是分数合并两路结果。
     *
     * <p>分数量纲不同（余弦 0~1 vs 词频累加），直接相加没有意义；
     * 按排名融合既不需要归一化，也对"某一路特别自信"天然鲁棒。
     *
     * <p>键是**条目的 id**（不是行号）—— 换存储之后"同一个块"靠 id 认，
     * 这也正是这次改造的意义。
     */
    private List<Hit> fuse(List<Hit> byVec, List<Hit> byKey, int topK, List<String> terms) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, KbCorpus.Entry> entries = new HashMap<>();
        Map<String, String> sources = new HashMap<>();

        for (int i = 0; i < byVec.size(); i++) {
            String id = byVec.get(i).entry().id();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            entries.put(id, byVec.get(i).entry());
            sources.merge(id, "vector", (a, b) -> a.equals(b) ? a : "both");
        }
        for (int i = 0; i < byKey.size(); i++) {
            String id = byKey.get(i).entry().id();
            scores.merge(id, KEYWORD_WEIGHT / (RRF_K + i + 1), Double::sum);
            entries.put(id, byKey.get(i).entry());
            sources.merge(id, "keyword", (a, b) -> a.equals(b) ? a : "both");
        }
        // 标题加成：用户打的是"物品名"时，物品页必须压过"正文提到它的页"
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            double bonus = titleBonus(entries.get(e.getKey()), terms);
            if (bonus > 0) {
                e.setValue(e.getValue() + bonus);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> new Hit(entries.get(e.getKey()), e.getValue(), sources.get(e.getKey())))
                .toList();
    }

    /**
     * 标题加成：标题正好就是这个名词 → 大加成；标题里含这个词 → 小加成。
     *
     * <p>这就是"点进来就对了"和"点进来还得再找"的区别。
     *
     * <p><b>为什么要剥掉结尾的「（English）」</b>：语料里标题大量写成
     * {@code 酸性菌丝（Acidic Mycelium）}，而词条名是 {@code 酸性菌丝} ——
     * 两者**不相等**，于是本体永远只吃到"部分命中"的小加成。
     * 实测后果：「酸性菌丝有什么用」把本体排到了**第 3**，前面是两个 {@code 菌丝体}。
     * 剥掉之后中文名算精确命中，本体回到第 1；直接打英文（{@code Acidic Mycelium}）
     * 也能命中括号里那半截。
     */
    private static double titleBonus(KbCorpus.Entry entry, List<String> terms) {
        if (terms == null || terms.isEmpty() || entry == null || entry.title() == null) {
            return 0;
        }
        String title = entry.title().toLowerCase(Locale.ROOT).trim();
        String head = stripTrailingParens(title);
        String inner = trailingParenContent(title);
        double best = 0;
        for (String raw : terms) {
            if (raw == null) {
                continue;
            }
            String term = raw.toLowerCase(Locale.ROOT).trim();
            if (term.isEmpty()) {
                continue;
            }
            if (title.equals(term) || head.equals(term) || term.equals(inner)) {
                return TITLE_EXACT_BONUS;
            }
            if (containsTerm(title, term) || containsTerm(head, term)) {
                best = Math.max(best, TITLE_PARTIAL_BONUS);
            }
        }
        return best;
    }

    /** 去掉标题**结尾**的「（…）」或「(…)」；只看最后一对，不碰中间的括号 */
    private static String stripTrailingParens(String s) {
        int close = s.length() - 1;
        if (close < 0) {
            return s;
        }
        char last = s.charAt(close);
        if (last != '）' && last != ')') {
            return s;
        }
        int at = s.lastIndexOf(last == '）' ? '（' : '(', close);
        return at > 0 ? s.substring(0, at).trim() : s;
    }

    /** 结尾括号里的内容（没有就空串）—— 英文原名就写在这里 */
    private static String trailingParenContent(String s) {
        int close = s.length() - 1;
        if (close < 0) {
            return "";
        }
        char last = s.charAt(close);
        if (last != '）' && last != ')') {
            return "";
        }
        int at = s.lastIndexOf(last == '）' ? '（' : '(', close);
        return at >= 0 ? s.substring(at + 1, close).trim() : "";
    }

    /**
     * A 路的结果。
     *
     * @param hits          过阈值之后的命中
     * @param rawBestCosine **过阈值之前**的最高余弦（诊断 / 调 min-score 用）
     */
    private record VectorPath(List<Hit> hits, double rawBestCosine) {
    }

    /**
     * A 路：问题向量（调用方算好，A/C 两路共用）和语料里每条算余弦，取 top-k 且不低于阈值。
     *
     * <p>注意这里**不再自己调 embedding** —— 一次检索只该有一次外部调用，
     * 而且 C 路必须和 A 路看同一个坐标系。
     */
    private VectorPath byVector(List<KbCorpus.Entry> entries, float[] qv, int candidateLimit, double minScore) {
        if (!corpus.isReady() || entries.isEmpty() || qv == null) {
            return new VectorPath(List.of(), 0.0);
        }

        List<Hit> scored = new ArrayList<>(entries.size());
        for (KbCorpus.Entry e : entries) {
            scored.add(new Hit(e, cosine(qv, e.vector()), "vector"));
        }
        scored.sort(Comparator.comparingDouble(Hit::score).reversed());

        // 卡阈值【之前】的最高余弦 —— 调 min-score 全靠它。
        // 卡完再取的话，所有未命中都只能是 0，就分不出"库里没有"和"差一点被挡"了。
        double rawBest = scored.isEmpty() ? 0.0 : scored.get(0).score();

        double min = minScore;
        List<Hit> out = new ArrayList<>();
        for (Hit h : scored) {
            if (h.score() < min || out.size() >= candidateLimit) {
                break;
            }
            out.add(h);
        }
        if (out.isEmpty()) {
            log.debug("[KB] A 路 0 块过阈值（最高相似度 {} < {}）",
                    String.format("%.3f", rawBest), String.format("%.3f", min));
        } else {
            log.info("[KB] A 路命中 {} 块，最高相似度 {}", out.size(), String.format("%.3f", out.get(0).score()));
        }
        return new VectorPath(out, rawBest);
    }

    /**
     * B 路：用术语表把中文问题里的游戏名词翻成英文，再按词频给块打分。
     *
     * <p>打分刻意简单：命中标题 3 分，正文里每出现一次 1 分（最多 3 次）。
     * 它不追求"语义相似"，只追求"这个词确实在这页里"—— 这正是向量检索的短板。
     */
    private List<Hit> byKeyword(List<KbCorpus.Entry> entries, int candidateLimit, List<String> terms) {
        if (!corpus.isReady() || entries.isEmpty()) {
            return List.of();
        }
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }

        List<Hit> scored = new ArrayList<>();
        for (KbCorpus.Entry e : entries) {
            String title = e.title() == null ? "" : e.title().toLowerCase();
            String text = e.text() == null ? "" : e.text().toLowerCase();
            double score = 0;
            for (String term : terms) {
                if (containsTerm(title, term)) {
                    score += TITLE_WEIGHT;
                }
                score += Math.min(3, countOccurrences(text, term));
            }
            if (score > 0) {
                scored.add(new Hit(e, score, "keyword"));
            }
        }
        scored.sort(Comparator.comparingDouble(Hit::score).reversed());

        List<Hit> out = scored.size() > candidateLimit ? scored.subList(0, candidateLimit) : scored;
        if (!out.isEmpty()) {
            log.info("[KB] B 路命中 {} 块（查询词：{}），最高分 {}",
                    out.size(), String.join(",", terms), out.get(0).score());
        }
        return out;
    }

    /**
     * 词条是否出现在文本里。
     *
     * <p>英文用**词边界**匹配：否则 "Cat" 会命中 "Category"、"Yak" 会命中 "Yaksomething"，
     * 关键词路立刻被噪音淹没。中文没有词边界概念，用子串即可。
     */
    private static boolean containsTerm(String haystack, String term) {
        String t = term.toLowerCase();
        if (t.isEmpty()) {
            return false;
        }
        if (t.chars().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)) {
            return haystack.contains(t);
        }
        return Pattern.compile("\\b" + Pattern.quote(t) + "\\b").matcher(haystack).find();
    }

    private static int countOccurrences(String haystack, String term) {
        String t = term.toLowerCase();
        if (t.isEmpty()) {
            return 0;
        }
        if (t.chars().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)) {
            int count = 0;
            int at = haystack.indexOf(t);
            while (at >= 0 && count < 5) {
                count++;
                at = haystack.indexOf(t, at + t.length());
            }
            return count;
        }
        Matcher m = Pattern.compile("\\b" + Pattern.quote(t) + "\\b").matcher(haystack);
        int count = 0;
        while (m.find() && count < 5) {
            count++;
        }
        return count;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
