package com.example.qqbot.eval;

import com.example.qqbot.persistence.EvalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导出的是"待标注"文件：列数固定、字段里不能有制表符与换行，否则粘回去会错列。
 */
class CalibrationExportTest {

    @Test
    @DisplayName("表头加数据行，五列，expected 留空")
    void rendersFiveColumns() {
        String tsv = EvalService.renderCandidates("relevance", List.of(
                new EvalRepository.CalibrationCandidate(1, "废料杯在哪", "废料杯在废料堆旁边", "succeeded"),
                new EvalRepository.CalibrationCandidate(2, "火把\t怎么用", "点燃\n即可", "failed")));

        // 不能先 strip：会把最后一行的尾部空列吃掉了
        String[] lines = tsv.split("\\R");
        assertThat(lines[0]).startsWith("# grader");
        assertThat(lines[2].split("\\t", -1)).hasSize(5);
        assertThat(lines[2].split("\\t", -1)[4]).isEmpty();
        // 换行与制表符被压成空格
        assertThat(lines[3]).contains("火把 怎么用").contains("点燃 即可");
        assertThat(lines[3].split("\\t", -1)).hasSize(5);
    }

    @Test
    @DisplayName("超长回答截断，避免一行几万字")
    void truncatesLongAnswer() {
        String longAnswer = "啊".repeat(2000);
        String tsv = EvalService.renderCandidates("relevance", List.of(
                new EvalRepository.CalibrationCandidate(1, "q", longAnswer, "succeeded")));
        assertThat(tsv).contains("…");
        assertThat(tsv.length()).isLessThan(1700);
    }
}
