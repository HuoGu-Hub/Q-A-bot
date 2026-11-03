package com.example.qqbot.eval;

import com.example.qqbot.trace.KbTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次检索的快照 —— RAG 维度的事实来源。
 *
 * <p>现在能拿到的是 {@link KbTrace} 里的那几项：命中块、候选数、卡阈值前后的最高余弦、
 * 术语命中、耗时。融合中间态与重排逐条分数还没有通道，字段先留空而不是编一个数：
 * 过程视图宁可显示"未采集"，也不该让人拿假数据去调参。
 */
public record RetrievalSnapshot(boolean enabled, String query, List<Map<String, Object>> terms,
                                int vectorCandidates, int keywordCandidates,
                                double bestCosine, double bestCosineRaw,
                                List<Map<String, Object>> hits, String sources,
                                String emptyReason, long latencyMs) {

    /** 空结果原因：把"库里没有"和"阈值挡掉"分开，这两件事的修法完全不同 */
    public static String emptyReasonOf(KbTrace trace) {
        if (trace == null || !trace.enabled()) {
            return "disabled";
        }
        if (!trace.hits().isEmpty()) {
            return null;
        }
        return trace.bestCosineRaw() < 0.30 ? "no_candidate" : "below_threshold";
    }

    public static RetrievalSnapshot of(String query, KbTrace trace) {
        KbTrace t = trace == null ? KbTrace.disabled() : trace;
        List<Map<String, Object>> hits = new ArrayList<>();
        for (KbTrace.Hit h : t.hits()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", h.id());
            row.put("title", h.title());
            row.put("docId", h.docId());
            row.put("score", Math.round(h.score() * 1000) / 1000.0);
            row.put("source", h.source());
            hits.add(row);
        }
        List<Map<String, Object>> terms = new ArrayList<>();
        for (KbTrace.MatchedTerm term : t.matchedTerms()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("zh", term.zh());
            row.put("en", term.en());
            row.put("inRetrieved", term.inRetrieved());
            terms.add(row);
        }
        return new RetrievalSnapshot(t.enabled(), query, terms, t.vectorCandidates(),
                t.keywordCandidates(), t.bestCosine(), t.bestCosineRaw(), hits, t.sources(),
                emptyReasonOf(t), t.retrieveMs());
    }
}
