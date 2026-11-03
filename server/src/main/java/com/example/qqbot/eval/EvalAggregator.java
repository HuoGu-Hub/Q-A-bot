package com.example.qqbot.eval;

import com.example.qqbot.persistence.EvalRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次指标聚合 —— 通过率、维度分布、延迟分位、失败标签。
 *
 * <h2>口径</h2>
 * 通过率的分母是**已判定**，不含 {@code SKIP}：未标注期望的用例本来就不该算进自动结论，
 * 把它们当失败会把"标注没做完"误读成"系统变差"。
 *
 * <p>门禁三态先按默认阈值 80% 给结论：达到是通过，低于是通过率不足的警告。
 * 基线机制上线后，这里换成与激活基线比较，阈值不再写死。
 */
@Component
public class EvalAggregator {

    /** 默认质量阈值，基线未配置时使用 */
    public static final double PASS_THRESHOLD = 0.80;

    private final EvalRepository repo;

    public EvalAggregator(EvalRepository repo) {
        this.repo = repo;
    }

    public EvalViews.Summary summarise(long batchId) {
        List<EvalRepository.RunLite> runs = repo.runsOfBatch(batchId);
        Map<Long, Map<String, String[]>> byRun = new LinkedHashMap<>();
        for (EvalRepository.JudgeLite j : repo.judgesOfBatch(batchId)) {
            byRun.computeIfAbsent(j.runId(), k -> new LinkedHashMap<>())
                    .put(j.dimension(), new String[]{j.verdict(), j.tag()});
        }

        int judged = 0;
        int pass = 0;
        int fail = 0;
        int skip = 0;
        Map<String, int[]> dims = new LinkedHashMap<>();
        for (String d : List.of("quality", "rag", "stability", "cost", "safety")) {
            dims.put(d, new int[]{0, 0, 0});
        }
        for (Map.Entry<Long, Map<String, String[]>> e : byRun.entrySet()) {
            boolean any = false;
            for (Map.Entry<String, String[]> d : e.getValue().entrySet()) {
                int[] c = dims.computeIfAbsent(d.getKey(), k -> new int[]{0, 0, 0});
                String verdict = d.getValue()[0];
                if (Judgement.PASS.equals(verdict)) {
                    c[0]++;
                    any = true;
                } else if (Judgement.FAIL.equals(verdict)) {
                    c[1]++;
                    any = true;
                } else {
                    c[2]++;
                }
            }
            if (any) {
                judged++;
            }
        }
        // 通过与否看 RAG、质量与安全三维：安全失败必须计入失败，否则被绕过的用例会被当成通过
        for (Map.Entry<Long, Map<String, String[]>> e : byRun.entrySet()) {
            String rag = verdictOf(e.getValue(), "rag");
            String quality = verdictOf(e.getValue(), "quality");
            String safety = verdictOf(e.getValue(), "safety");
            if (Judgement.FAIL.equals(rag) || Judgement.FAIL.equals(quality)
                    || Judgement.FAIL.equals(safety)) {
                fail++;
            } else if (Judgement.PASS.equals(rag) || Judgement.PASS.equals(quality)
                    || Judgement.PASS.equals(safety)) {
                pass++;
            } else {
                skip++;
            }
        }

        List<Long> latencies = new ArrayList<>();
        long tokensIn = 0;
        long tokensOut = 0;
        long costMicro = 0;
        int pricedRuns = 0;
        for (EvalRepository.RunLite r : runs) {
            latencies.add(r.latencyMs());
            tokensIn += r.tokensIn();
            tokensOut += r.tokensOut();
            if (r.costMicro() > 0) {
                costMicro += r.costMicro();
                pricedRuns++;
            }
        }
        double rate = pass + fail == 0 ? 0 : (double) pass / (pass + fail);

        List<EvalViews.DimStat> dimStats = new ArrayList<>();
        for (Map.Entry<String, int[]> e : dims.entrySet()) {
            int[] c = e.getValue();
            dimStats.add(new EvalViews.DimStat(e.getKey(), c[0], c[1], c[2]));
        }
        List<EvalViews.TagStat> tags = new ArrayList<>();
        for (EvalRepository.TagCount t : repo.topTags(batchId, 10)) {
            tags.add(new EvalViews.TagStat(t.tag(), (int) t.count()));
        }
        return new EvalViews.Summary(runs.size(), judged, pass, fail, skip,
                Math.round(rate * 1000) / 1000.0, percentile(latencies, 0.50), percentile(latencies, 0.95),
                tokensIn, tokensOut, costMicro, pricedRuns, dimStats, tags);
    }

    /** 门禁三态：没有判定就还是 pending，有判定才谈得上通过与警告 */
    public String gateOf(EvalViews.Summary s) {
        if (s.pass() + s.fail() == 0) {
            return "pending";
        }
        return s.passRate() >= PASS_THRESHOLD ? "passed" : "warned";
    }

    private static String verdictOf(Map<String, String[]> dims, String dimension) {
        String[] v = dims.get(dimension);
        return v == null ? null : v[0];
    }

    /** 最近秩法分位数，与 {@code QaAnalytics} 保持同一口径 */
    static long percentile(List<Long> values, double p) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }
}
