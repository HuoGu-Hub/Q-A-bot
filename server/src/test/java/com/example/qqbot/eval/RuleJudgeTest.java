package com.example.qqbot.eval;

import com.example.qqbot.trace.KbTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则判定的口径测试 —— 这些结论会直接进失败归因，判错一个标签就把人引到错的模块。
 */
class RuleJudgeTest {

    private final RuleJudge judge = new RuleJudge();

    private static KbTrace trace(String... ids) {
        List<KbTrace.Hit> hits = java.util.Arrays.stream(ids)
                .map(id -> new KbTrace.Hit(id, "标题", "doc", 0.8, "vector"))
                .toList();
        return new KbTrace(true, hits, 0.7, 0.71, 3, 2, List.of(), 120);
    }

    private static Judgement find(List<Judgement> all, String dimension) {
        return all.stream().filter(j -> j.dimension().equals(dimension)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("空返回判质量失败，标签是空返回而不是事实错误")
    void blankAnswerIsEmptyFailure() {
        CaseSpec spec = new CaseSpec(1, "c1", "entity", "废料杯在哪", List.of("b1"), false);
        List<Judgement> out = judge.judge(spec, new Observation("  ", true, trace("b1").hits(),
                0.7, "vector", 800));
        assertThat(find(out, "quality").tag()).isEqualTo("STB_EMPTY");
        assertThat(find(out, "quality").failed()).isTrue();
    }

    @Test
    @DisplayName("期望空结果却注入了块 → 噪声，不是未命中")
    void injectedNoiseIsCalledNoise() {
        CaseSpec spec = new CaseSpec(2, "c2", "gap", "游戏里有飞行坐骑吗", List.of(), true);
        Observation obs = new Observation("资料里没查到相关内容", true, trace("b9").hits(),
                0.66, "vector", 900);
        List<Judgement> out = judge.judge(spec, obs);
        assertThat(find(out, "rag").tag()).isEqualTo("RET_NOISE");
        assertThat(find(out, "quality").verdict()).isEqualTo(Judgement.PASS);
    }

    @Test
    @DisplayName("命中期望块按名次给分，第 1 名满分")
    void rankDrivesScore() {
        CaseSpec spec = new CaseSpec(3, "c3", "entity", "金黄花", List.of("b2"), false);
        Judgement first = find(judge.judge(spec, new Observation("答案", true,
                trace("b2", "b1").hits(), 0.7, "vector", 800)), "rag");
        Judgement second = find(judge.judge(spec, new Observation("答案", true,
                trace("b1", "b2").hits(), 0.7, "vector", 800)), "rag");
        assertThat(first.score()).isEqualTo(1.0);
        assertThat(second.score()).isEqualTo(0.5);
        assertThat(second.verdict()).isEqualTo(Judgement.PASS);
    }

    @Test
    @DisplayName("期望块没出现：余弦高是命中失败，低是库里没资料 —— 两种修法不同")
    void missSplitsIntoTwoTags() {
        CaseSpec spec = new CaseSpec(4, "c4", "entity", "火把", List.of("b7"), false);
        Judgement candidateLow = find(judge.judge(spec, new Observation("答案", true,
                List.of(), 0.12, "none", 700)), "rag");
        Judgement thresholdHigh = find(judge.judge(spec, new Observation("答案", true,
                List.of(), 0.42, "none", 700)), "rag");
        assertThat(candidateLow.tag()).isEqualTo("RET_NO_CANDIDATE");
        assertThat(thresholdHigh.tag()).isEqualTo("RET_MISS");
    }

    @Test
    @DisplayName("未标注期望值的用例只出 SKIP，不进自动结论")
    void unlabelledCaseIsSkipped() {
        CaseSpec spec = new CaseSpec(5, "c5", "descriptive", "想搭一个木屋", List.of(), false);
        List<Judgement> out = judge.judge(spec, new Observation("可以这样搭", true,
                trace("b1").hits(), 0.7, "vector", 800));
        assertThat(find(out, "rag").verdict()).isEqualTo(Judgement.SKIP);
        assertThat(find(out, "rag").tag()).isNull();
    }

    @Test
    @DisplayName("超时判稳定性失败，且优先级高于质量")
    void timeoutIsStabilityFailure() {
        CaseSpec spec = new CaseSpec(6, "c6", "entity", "废料杯", List.of("b1"), false);
        List<Judgement> out = judge.judge(spec, new Observation("答案", true,
                trace("b1").hits(), 0.7, "vector", 90_000));
        assertThat(find(out, "stability").tag()).isEqualTo("STB_TIMEOUT");
    }
}
