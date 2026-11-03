package com.example.qqbot.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分维度回退：只看两侧都有判定的维度，回退超过 5 个百分点才提示。
 */
class EvalDimensionDriftTest {

    private static EvalViews.Summary summary(int qualityPass, int qualityFail, int ragPass, int ragFail) {
        int pass = qualityPass + ragPass;
        int fail = qualityFail + ragFail;
        return new EvalViews.Summary(pass + fail, pass + fail, pass, fail, 0,
                pass + fail == 0 ? 0 : (double) pass / (pass + fail), 800, 1200, 0, 0, 0, 0,
                List.of(new EvalViews.DimStat("quality", qualityPass, qualityFail, 0),
                        new EvalViews.DimStat("rag", ragPass, ragFail, 0)),
                List.of());
    }

    @Test
    @DisplayName("某维度回退超过 5 个百分点就点名")
    void namesWorstDimension() {
        EvalViews.Summary base = summary(9, 1, 10, 0);
        EvalViews.Summary now = summary(4, 6, 10, 0);
        assertThat(EvalRunner.dimensionDrift(base, now)).contains("结果质量").contains("50.0");
    }

    @Test
    @DisplayName("波动在阈值内不提示")
    void ignoresSmallDrop() {
        // 90% → 89%，只退 1 个百分点
        assertThat(EvalRunner.dimensionDrift(summary(90, 10, 10, 0), summary(89, 11, 10, 0))).isNull();
    }

    @Test
    @DisplayName("某一侧没有判定就不比")
    void skipsUnjudgedDimension() {
        assertThat(EvalRunner.dimensionDrift(summary(0, 0, 10, 0), summary(0, 0, 10, 0))).isNull();
    }
}
