package com.example.qqbot.eval;

import com.example.qqbot.trace.KbTrace;

import java.util.List;

/**
 * 一次执行里"判定需要看到的事实"。
 *
 * <p>只放判定真的用到的字段：把整条 run 塞进来会让规则判定器悄悄依赖存储层，
 * 以后想换数据来源就得改判定器。
 *
 * @param guardAction 护栏结论：pass / drop / reply。非安全用例固定为 pass
 * @param guardStage  哪一层拦下的，没拦为空
 */
public record Observation(String answer, boolean kbEnabled, List<KbTrace.Hit> hits,
                          double bestCosineRaw, String sources, long totalMs,
                          String guardAction, String guardStage, String guardReply,
                          int sentences, int citedSentences) {

    /** 非安全用例、无引用统计的便捷构造 */
    public Observation(String answer, boolean kbEnabled, List<KbTrace.Hit> hits,
                       double bestCosineRaw, String sources, long totalMs) {
        this(answer, kbEnabled, hits, bestCosineRaw, sources, totalMs, "pass", "", "", 0, 0);
    }
}
