package com.example.qqbot.kb;

import java.util.List;

/**
 * 一次检索的"痕迹" —— 记录系统靠它分析答错原因。
 *
 * @param enabled    这次是否启用了知识库
 * @param retrieval  检索结果（含融合后的块、最高余弦、两路候选数）
 * @param retrieveMs 检索耗时
 */
public record KbTrace(boolean enabled, KbRetriever.Retrieval retrieval, long retrieveMs) {

    public static KbTrace disabled() {
        return new KbTrace(false, KbRetriever.Retrieval.empty(), 0);
    }

    public List<KbRetriever.Hit> hits() {
        return retrieval == null ? List.of() : retrieval.hits();
    }

    public int hitCount() {
        return hits().size();
    }

    /**
     * 最高**余弦相似度**（不是融合分）。
     *
     * <p>⚠️ 这是**卡完 min-score 阈值之后**的值 —— 未命中时它必然是 0，
     * 所以它<b>没法</b>用来判断阈值该调高还是调低（融合分是 RRF 量纲，更不行）。
     * 调阈值请用 {@link #bestCosineRaw()}。
     */
    public double bestCosine() {
        return retrieval == null ? 0.0 : retrieval.bestCosine();
    }

    /**
     * 卡 min-score 阈值**之前**的最高余弦 —— 这才是"库里到底有多像"的真值。
     *
     * <p>用途：未命中时区分两种情况。`0.43` = 差一点被阈值挡了（该降阈值）；
     * `0.2` = 库里确实没有相关内容（该补资料，调阈值没用）。
     */
    public double bestCosineRaw() {
        return retrieval == null ? 0.0 : retrieval.bestCosineRaw();
    }

    /** 融合后最高分（仅供参考，量纲是 RRF） */
    public double topFusedScore() {
        return hits().isEmpty() ? 0.0 : hits().get(0).score();
    }

    /**
     * 命中的资料来自哪条路：title / vector / keyword / both / none。
     *
     * <p><b>{@code title} 优先</b>：它表示 C 路的闸门开过（"这次提问就是在问某个名字"）。
     * 对调参来说这是最有信息量的事实 —— 问答统计里的 "title" 桶就是闸门的实际触发率，
     * 拿它和 {@code app.kb.title-gate} 对照就能知道阈值定得松还是紧。
     */
    public String sources() {
        if (hits().isEmpty()) {
            return "none";
        }
        boolean title = hits().stream().anyMatch(h -> h.source().contains("title"));
        if (title) {
            return "title";
        }
        boolean vector = hits().stream().anyMatch(h -> h.source().contains("vector"));
        boolean keyword = hits().stream().anyMatch(h -> h.source().contains("keyword"));
        if (vector && keyword) {
            return "both";
        }
        return vector ? "vector" : "keyword";
    }

    public List<String> titles() {
        return hits().stream().map(h -> h.entry().title()).toList();
    }
}
