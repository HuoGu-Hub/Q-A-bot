package com.example.qqbot.eval;

import com.example.qqbot.trace.KbTrace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则判定器 —— 不调模型、不看概率，只按已经发生的事实下结论。
 *
 * <h2>为什么先做它</h2>
 * 判定模型要花预算，而结果质量的兜底决策是"无参考答案的用例只进人工"。
 * 在 rubric 定下来之前，规则判定能先把**结构性缺陷**抓出来：
 * 空返回、协议标记泄漏、该拒答却编了、该有资料却没检索到、注入噪声。
 * 这些都不需要模型，也不会因为模型抖动而时红时绿。
 *
 * <h2>刻意不做的事</h2>
 * 事实正确性、引用支持关系、有害内容都不在这里判。它们需要 rubric 与模型，
 * 在预算未定时按决策清单的兜底执行：标为仅人工，不产出自动通过率。
 */
@Component
public class RuleJudge {

    /** 单例时限，与 {@code eval_batch} 的单例超时保持一致 */
    static final long TIMEOUT_MS = 60_000L;

    /** 兜底话术出现即视为"没答出来"，和空返回同样处理 */
    private static final List<String> FALLBACK_MARKERS = List.of("走神了", "稍后再问我");

    /** 协议细节泄漏：模型把工具调用写进了正文 */
    private static final List<String> PROTOCOL_MARKERS = List.of("<tool_call", "DSML", "</tool_call>");

    public List<Judgement> judge(CaseSpec c, Observation o) {
        List<Judgement> out = new ArrayList<>();
        out.add(stability(c, o));
        out.add(quality(c, o));
        out.add(rag(c, o));
        out.add(cost(c, o));
        out.add(safety(c, o));
        return out;
    }

    private Judgement stability(CaseSpec c, Observation o) {
        if (o.totalMs() > TIMEOUT_MS) {
            return fail("stability", "单例耗时 " + o.totalMs() + " ms 超过时限", "STB_TIMEOUT",
                    Map.of("latencyMs", o.totalMs(), "limitMs", TIMEOUT_MS));
        }
        return skip("stability", "未做重复运行，稳定性暂不判");
    }

    private Judgement quality(CaseSpec c, Observation o) {
        String answer = o.answer() == null ? "" : o.answer().strip();
        if (answer.isEmpty()) {
            return fail("quality", "空返回", "STB_EMPTY", Map.of());
        }
        if (FALLBACK_MARKERS.stream().anyMatch(answer::contains)) {
            return fail("quality", "只拿到兜底话术", "STB_EMPTY", Map.of("answer", answer));
        }
        for (String marker : PROTOCOL_MARKERS) {
            if (answer.contains(marker)) {
                return fail("quality", "回复里出现工具调用协议标记", "ANS_FORMAT",
                        Map.of("marker", marker));
            }
        }
        if (c.expectEmpty() && "gap".equals(c.category())) {
            boolean refused = answer.contains("没查到") || answer.contains("没有");
            return refused
                    ? pass("quality", "库内无依据且明确说明没查到", 1.0, Map.of())
                    : fail("quality", "库内无依据却给出确定答案", "ANS_REFUSAL_UNDER",
                            Map.of("answer", answer));
        }
        return skip("quality", "无 rubric，事实与格式仅人工判", Map.of("answerChars", answer.length()));
    }

    private Judgement rag(CaseSpec c, Observation o) {
        if (!c.labelled()) {
            return skip("rag", "期望值未标注", Map.of());
        }
        if (!o.kbEnabled()) {
            return skip("rag", "本轮未检索", Map.of());
        }
        int hitCount = o.hits() == null ? 0 : o.hits().size();
        if (c.expectEmpty()) {
            if (hitCount == 0) {
                return pass("rag", "期望空结果，实际未注入资料", 1.0, Map.of());
            }
            return fail("rag", "期望空结果，却注入了 " + hitCount + " 块", "RET_NOISE",
                    Map.of("hits", hitCount, "sources", o.sources()));
        }
        int rank = rankOf(c.expected(), o.hits());
        if (rank == 1) {
            return pass("rag", "第 1 名命中期望块", 1.0, Map.of("rank", 1));
        }
        if (rank > 1) {
            return pass("rag", "第 " + rank + " 名命中期望块", 1.0 / rank, Map.of("rank", rank));
        }
        String tag = o.bestCosineRaw() < 0.30 ? "RET_NO_CANDIDATE" : "RET_MISS";
        return fail("rag", "期望块未出现在结果里", tag,
                Map.of("expected", c.expected(), "hits", hitCount,
                        "bestCosineRaw", round(o.bestCosineRaw())));
    }

    private Judgement cost(CaseSpec c, Observation o) {
        return skip("cost", "未逐次计量 token", Map.of("latencyMs", o.totalMs()));
    }

    private Judgement safety(CaseSpec c, Observation o) {
        return skip("safety", "安全用例集未接入", Map.of());
    }

    /** 期望块在结果里第一次出现的名次，从 1 开始；没命中返回 0 */
    static int rankOf(List<String> expected, List<KbTrace.Hit> hits) {
        if (expected == null || expected.isEmpty() || hits == null) {
            return 0;
        }
        for (int i = 0; i < hits.size(); i++) {
            String id = hits.get(i).id();
            if (id != null && expected.contains(id)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static Judgement pass(String dim, String reason, double score, Map<String, Object> ev) {
        return new Judgement(dim, Judgement.PASS, score, reason, null, new LinkedHashMap<>(ev));
    }

    private static Judgement fail(String dim, String reason, String tag, Map<String, Object> ev) {
        return new Judgement(dim, Judgement.FAIL, 0.0, reason, tag, new LinkedHashMap<>(ev));
    }

    private static Judgement skip(String dim, String reason) {
        return skip(dim, reason, Map.of());
    }

    private static Judgement skip(String dim, String reason, Map<String, Object> ev) {
        return new Judgement(dim, Judgement.SKIP, 0.0, reason, null, new LinkedHashMap<>(ev));
    }
}
