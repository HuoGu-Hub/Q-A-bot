package com.example.qqbot.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * cross-encoder 重排。
 *
 * <p><b>为什么没有它就没有区分度</b>（2026-10-02 黄金集实测）：双塔余弦"该答"的问题最低 0.548、
 * "库里根本没有"的问题最高 0.559，分布完全重叠 —— 所以调 min-score 是死路。
 * cross-encoder 把问题和文档拼在一起看，闲聊 top 分 ≤0.002、真实问题 ≥0.04，才有可分性。
 *
 * <p><b>fail-open</b>：重排挂了就保持原顺序照常回答，绝不因为"锦上添花的一层"失败而不回答。
 */
@Component
public class CrossEncoderReranker implements KbReranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);

    private final KbPolicy props;

    /** 可以为 {@code null} —— 便利构造（测试）不装重排，等于关掉它 */
    private final RerankClient rerankClient;

    public CrossEncoderReranker(KbPolicy props, RerankClient rerankClient) {
        this.props = props;
        this.rerankClient = rerankClient;
    }

    @Override
    public boolean isAvailable() {
        return rerankClient != null && rerankClient.isAvailable();
    }

    @Override
    public List<KbRetriever.Hit> rerank(String query, List<KbRetriever.Hit> fused, int topK, boolean gateHit) {
        if (fused.isEmpty()) {
            return fused;
        }
        Rerank cfg = props.getRerank();
        int limit = Math.min(fused.size(), Math.max(1, cfg.getCandidateLimit()));
        List<KbRetriever.Hit> candidates = fused.subList(0, limit);
        List<String> docs = new ArrayList<>(candidates.size());
        for (KbRetriever.Hit h : candidates) {
            // 标题必须一起送：语料标题是「中文名（English）」，实体名就在标题里。
            // 实测「氨液腺」→「氨腺」字面完全不重合，正是靠标题那半截才被重排认出来。
            docs.add(h.entry().title() + "\n" + h.entry().text());
        }

        double[] scores;
        try {
            scores = rerankClient.score(query, docs);
        } catch (Exception e) {
            log.warn("[KB-RERANK] 重排失败，保持 RRF 顺序继续回答：{}", e.getMessage());
            return fused.size() <= topK ? fused : fused.subList(0, topK);
        }

        List<KbRetriever.Hit> ranked = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            KbRetriever.Hit h = candidates.get(i);
            double s = i < scores.length ? scores[i] : 0.0;
            // 分数换成重排分：它才是有区分度的相关度，进问答统计比 RRF 排名分更可解释
            ranked.add(new KbRetriever.Hit(h.entry(), s, h.source() + "+rerank"));
        }
        ranked.sort(Comparator.comparingDouble(KbRetriever.Hit::score).reversed());
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
}
