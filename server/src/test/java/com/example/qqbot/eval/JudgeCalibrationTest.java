package com.example.qqbot.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校准算的是"判定与人一致不一致"，不是"判定准不准"本身；
 * 这里用桩模型固定分数，验证统计口径：一致率、两类均值与分档差。
 */
class JudgeCalibrationTest {

    private static final String TSV = """
            不知道\t这个我不知道\tFAIL\t拒答
            火把怎么用\t拿着火把靠近木头就会点燃\tPASS\t清楚
            金黄花在哪\t金黄花在河边草地\tPASS\t具体
            怎么解锁\t结局有很多种\tFAIL\t空泛
            """;

    @Test
    @DisplayName("一致率与分档差按人工标注对齐")
    void computesAgreement() {
        RubricJudge judge = mock(RubricJudge.class);
        when(judge.judge(eq(RubricJudge.RELEVANCE), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString())).thenAnswer(inv -> {
                    String answer = inv.getArgument(2);
                    boolean bad = answer.contains("不知道") || answer.contains("很多种");
                    return new RubricJudge.Opinion(bad ? 1.5 : 4.5, !bad, bad ? "没答到" : "清楚",
                            1, java.util.List.of(bad ? 1.5 : 4.5), "average", "relevance", 1);
                });

        JudgeCalibration.Report report = new JudgeCalibration(judge).run(TSV, "");
        assertThat(report.total()).isEqualTo(4);
        assertThat(report.agreed()).isEqualTo(4);
        assertThat(report.agreement()).isEqualTo(1.0);
        assertThat(report.passMean()).isEqualTo(4.5);
        assertThat(report.failMean()).isEqualTo(1.5);
        assertThat(report.separation()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("标注与判定相反时一致率下降")
    void countsDisagreement() {
        RubricJudge judge = mock(RubricJudge.class);
        when(judge.judge(eq(RubricJudge.RELEVANCE), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenReturn(new RubricJudge.Opinion(4.0, true, "看起来不错", 1,
                        java.util.List.of(4.0), "average", "relevance", 1));

        JudgeCalibration.Report report = new JudgeCalibration(judge).run(TSV, "");
        assertThat(report.total()).isEqualTo(4);
        // 两条 PASS 被判定命中，两条 FAIL 没命中
        assertThat(report.agreed()).isEqualTo(2);
        assertThat(report.agreement()).isEqualTo(0.5);
    }
}
