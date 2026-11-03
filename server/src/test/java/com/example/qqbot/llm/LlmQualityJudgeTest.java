package com.example.qqbot.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析失败必须返回 null：宁可退回规则判定，也不能往通过率里塞一个猜的分数。
 */
class LlmQualityJudgeTest {

    @Test
    @DisplayName("标准输出解析出分数与理由")
    void parsesScore() {
        LlmQualityJudge.Opinion opinion = LlmQualityJudge.parse(
                "SCORE: 0.82\nREASON: 答案完整，引用正确", "mock", 12, 8);
        assertThat(opinion).isNotNull();
        assertThat(opinion.score()).isEqualTo(0.82);
        assertThat(opinion.pass()).isTrue();
        assertThat(opinion.reason()).contains("引用正确");
    }

    @Test
    @DisplayName("低分判不通过，中文冒号也能解析")
    void lowScoreFails() {
        LlmQualityJudge.Opinion opinion = LlmQualityJudge.parse(
                "SCORE：0.2\nREASON：编造了资料里没有的信息", "mock", 1, 1);
        assertThat(opinion.pass()).isFalse();
        assertThat(opinion.reason()).contains("编造");
    }

    @Test
    @DisplayName("没有分数就放弃，不猜")
    void unparseableReturnsNull() {
        assertThat(LlmQualityJudge.parse("我不知道", "mock", 0, 0)).isNull();
        assertThat(LlmQualityJudge.parse(null, "mock", 0, 0)).isNull();
    }
}
