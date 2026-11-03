package com.example.qqbot.eval;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.llm.LlmPolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次评测的"被测目标"快照。
 *
 * <p>为什么必须在建批时固定：批次跑 20 分钟，期间后台改一次 {@code min-score}
 * 或切一次模型，后半批就不是同一个系统了。不锁快照，趋势图上的每一次抖动都说不清来源。
 *
 * <p>只放会影响结论的项：检索阈值、重排阈值、工具预算、默认 provider。
 * 语料版本单独记在批次里，因为它决定的是"资料库"而不是"配置"。
 */
public record EvalTarget(int topK, double minScore, double titleGate, double rerankMinScore,
                         String provider, int maxToolCalls, int maxToolRoundTrips,
                         int toolDeadlineSeconds) {

    public static EvalTarget of(KbPolicy kb, LlmPolicy llm) {
        double rerankMin = kb != null && kb.getRerank() != null ? kb.getRerank().getMinScore() : 0.0;
        int maxCalls = llm != null && llm.getTool() != null ? llm.getTool().getMaxCalls() : 3;
        int maxRounds = llm != null && llm.getTool() != null ? llm.getTool().getMaxRoundTrips() : 3;
        int deadline = llm != null && llm.getTool() != null ? llm.getTool().getDeadlineSeconds() : 30;
        return new EvalTarget(
                kb == null ? 0 : kb.getTopK(),
                kb == null ? 0.0 : kb.getMinScore(),
                kb == null ? 0.0 : kb.getTitleGate(),
                rerankMin,
                llm == null ? "" : llm.getDefaultProvider(),
                maxCalls, maxRounds, deadline);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topK", topK);
        m.put("minScore", minScore);
        m.put("titleGate", titleGate);
        m.put("rerankMinScore", rerankMinScore);
        m.put("provider", provider);
        m.put("maxToolCalls", maxToolCalls);
        m.put("maxToolRoundTrips", maxToolRoundTrips);
        m.put("toolDeadlineSeconds", toolDeadlineSeconds);
        return m;
    }
}
