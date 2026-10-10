package com.example.qqbot.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * C 路：标题向量闸门。
 *
 * <p>阈值与 A 路的 {@code min-score}（0.45）是两个不同的东西：后者问"这段正文够不够相关"，
 * 前者问"这次提问是不是就是在问某个名字"。实测 200 条名字查询 + 120 条真实口语提问：
 * 0.70 时名字查询 100% 触发、口语提问只有 2.5% 误触发；放到 0.65 误触发涨到 10%，
 * 提到 0.80 则名字覆盖率掉到 95.5%。所以别随手调，调之前看日志里的余弦分布。
 */
@Component
public class TitleVectorGate implements KbTitleGateSource {

    private static final Logger log = LoggerFactory.getLogger(TitleVectorGate.class);

    private final KbCorpus corpus;

    public TitleVectorGate(KbCorpus corpus) {
        this.corpus = corpus;
    }

    @Override
    public KbRetriever.TitleGate check(KbRetrievalContext q, double threshold) {
        List<KbCorpus.Entry> entries = q.entries();
        float[] qv = q.queryVector();
        if (threshold <= 0 || qv == null || entries.isEmpty()) {
            return KbRetriever.TitleGate.NONE;
        }
        KbCorpus.Entry best = null;
        double bestCos = 0.0;
        for (KbCorpus.Entry e : entries) {
            float[] tv = e.titleVector();
            // 维度对不上说明标题向量是别的模型算的 —— 静默跳过，
            // 别拿它算一个"看着有值"的错余弦
            if (tv == null || tv.length != qv.length) {
                continue;
            }
            double c = KbVectors.cosine(qv, tv);
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
        return new KbRetriever.TitleGate(best, bestCos, hit);
    }
}
