package com.example.qqbot.eval;

import com.example.qqbot.llm.JudgeModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1-5 分判定器：解析要能容忍包裹文字，采样要按均值或投票收敛。
 */
class RubricJudgeTest {

    private static RubricJudge judgeReturning(String... outputs) {
        JudgeModel model = mock(JudgeModel.class);
        var stub = when(model.ask(anyString(), anyString(), anyString()));
        if (outputs.length == 1) {
            stub.thenReturn(outputs[0]);
        } else {
            stub.thenReturn(outputs[0], java.util.Arrays.copyOfRange(outputs, 1, outputs.length));
        }
        return new RubricJudge(model, new ObjectMapper());
    }

    @Test
    @DisplayName("均值策略：三次采样取平均，过阈值算通过")
    void averagesSamples() {
        RubricJudge j = judgeReturning(
                "{\"score\": 4, \"reason\": \"完整\"}",
                "{\"score\": 3, \"reason\": \"缺细节\"}",
                "{\"score\": 5, \"reason\": \"准确\"}");
        RubricJudge.Opinion o = j.judge(RubricJudge.RELEVANCE, "问题", "回答", "", 3, "average", "");
        assertThat(o.score()).isEqualTo(4.0);
        assertThat(o.pass()).isTrue();
        assertThat(o.samples()).isEqualTo(3);
        assertThat(o.rawScores()).containsExactly(4.0, 3.0, 5.0);
    }

    @Test
    @DisplayName("投票策略：多数样本不过就不过，不被均值掩盖")
    void votesSamples() {
        RubricJudge j = judgeReturning(
                "{\"score\": 2, \"reason\": \"跑题\"}",
                "{\"score\": 2, \"reason\": \"跑题\"}",
                "{\"score\": 5, \"reason\": \"偶尔答对\"}");
        RubricJudge.Opinion o = j.judge(RubricJudge.RELEVANCE, "问题", "回答", "", 3, "vote", "");
        assertThat(o.pass()).isFalse();
        assertThat(o.score()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("解析容忍包裹文字，也能只认 score")
    void parsesLooseOutput() {
        RubricJudge j = judgeReturning("评分如下：{\"score\": 3.5, \"reason\": \"一般\"} 以上");
        assertThat(j.judge(RubricJudge.RELEVANCE, "q", "a", "", 1, "average", "").score())
                .isEqualTo(3.5);

        RubricJudge j2 = judgeReturning("score: 4");
        assertThat(j2.judge(RubricJudge.RELEVANCE, "q", "a", "", 1, "average", "").score())
                .isEqualTo(4.0);
    }

    @Test
    @DisplayName("完全解析不出来就放弃，不猜分数")
    void rejectsGarbage() {
        RubricJudge j = judgeReturning("我觉得还行");
        assertThat(j.judge(RubricJudge.RELEVANCE, "q", "a", "", 1, "average", "")).isNull();
    }
}
