package com.example.qqbot.trace;

import java.util.List;

/**
 * 一次检索的"痕迹" —— 记录系统靠它分析答错原因。
 *
 * <h2>为什么它不在 kb 包里</h2>
 * 它原先住在 {@code kb}，并且**内嵌了 {@code KbRetriever.Retrieval} 与 {@code KbRetriever.Hit}**。
 * 后果：记录系统（{@code qa.QaCollector}）为了读一份统计，
 * 被迫依赖整个知识库的检索实现 —— 于是有了反向依赖 {@code qa -> kb}。
 *
 * <p>现在它被**扁平化**成自成一体的纯数据（**不再引用任何 kb 类型**），
 * 并搬到中性的 {@code trace} 包：谁都能产出它，谁都能读它。
 * 从 {@code KbRetriever.Retrieval} 造它的那一步留在 kb 里
 * （见 {@code KbRetriever.traceOf}），所以这个包**零依赖**。
 *
 * @param enabled           这次是否启用了知识库
 * @param hits              命中的块（只留分析需要的字段，不含正文）
 * @param bestCosine        卡 min-score 阈值**之后**的最高余弦（未命中时必为 0，所以它没法用来判断阈值松紧）
 * @param bestCosineRaw     卡阈值**之前**的最高余弦 —— 这才是"库里到底有多像"的真值
 * @param vectorCandidates  向量路候选数
 * @param keywordCandidates 关键词路候选数
 * @param matchedTerms      这次提问命中的术语（含"是否落在检索结果里"）
 * @param retrieveMs        检索耗时
 */
public record KbTrace(boolean enabled, List<Hit> hits, double bestCosine, double bestCosineRaw,
                      int vectorCandidates, int keywordCandidates, List<MatchedTerm> matchedTerms,
                      long retrieveMs) {

    /** 命中的一块 —— 只保留分析需要的字段，**不带正文**（正文不该进统计表） */
    public record Hit(String id, String title, String docId, double score, String source) {
    }

    /**
     * 提问命中的一个术语。
     *
     * @param zh          中文名
     * @param en          英文名（块 id 或英文词条）
     * @param inRetrieved 这次检索结果里有没有它。用来区分两种失败：
     *                    「术语表里有这个词、但知识库没检索到」与「检索到了、但模型没答好」
     */
    public record MatchedTerm(String zh, String en, boolean inRetrieved) {
    }

    /** 知识库没启用 / 没检索 */
    public static KbTrace disabled() {
        return new KbTrace(false, List.of(), 0.0, 0.0, 0, 0, List.of(), 0);
    }

    /** 启用了、但这次没检索（比如"不用知识库"模式） */
    public static KbTrace empty(long retrieveMs) {
        return new KbTrace(true, List.of(), 0.0, 0.0, 0, 0, List.of(), retrieveMs);
    }

    public int hitCount() {
        return hits == null ? 0 : hits.size();
    }

    /** 融合后最高分（仅供参考，量纲是 RRF） */
    public double topFusedScore() {
        return hits == null || hits.isEmpty() ? 0.0 : hits.get(0).score();
    }

    /**
     * 命中的资料来自哪条路：title / vector / keyword / both / none。
     *
     * <p><b>{@code title} 优先</b>：它表示 C 路的闸门开过（"这次提问就是在问某个名字"）。
     * 对调参来说这是最有信息量的事实 —— 问答统计里的 "title" 桶就是闸门的实际触发率，
     * 拿它和 {@code app.kb.title-gate} 对照就能知道阈值定得松还是紧。
     */
    public String sources() {
        if (hits == null || hits.isEmpty()) {
            return "none";
        }
        if (hits.stream().anyMatch(h -> h.source() != null && h.source().contains("title"))) {
            return "title";
        }
        boolean vector = hits.stream().anyMatch(h -> h.source() != null && h.source().contains("vector"));
        boolean keyword = hits.stream().anyMatch(h -> h.source() != null && h.source().contains("keyword"));
        if (vector && keyword) {
            return "both";
        }
        return vector ? "vector" : "keyword";
    }

    public List<String> titles() {
        return hits == null ? List.of() : hits.stream().map(Hit::title).toList();
    }
}
