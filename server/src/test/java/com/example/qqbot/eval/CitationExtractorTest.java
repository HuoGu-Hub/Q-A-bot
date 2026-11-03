package com.example.qqbot.eval;

import com.example.qqbot.trace.KbTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 逐句引用：只按标题做字符归属，不猜语义。
 */
class CitationExtractorTest {

    private static final List<KbTrace.Hit> HITS = List.of(
            new KbTrace.Hit("b1", "废料杯", "doc-1", 0.9, "vector"),
            new KbTrace.Hit("b2", "火把", "doc-2", 0.8, "vector"));

    @Test
    @DisplayName("句子命中标题才算标注，没命中留空")
    void mapsSentences() {
        List<CitationExtractor.Line> lines = CitationExtractor.extract(
                "废料杯在废料堆旁边。火把可以照明。今天天气不错。", HITS);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).blockIds()).containsExactly("b1");
        assertThat(lines.get(1).blockIds()).containsExactly("b2");
        assertThat(lines.get(2).blockIds()).isEmpty();
        assertThat(CitationExtractor.supported(lines)).isEqualTo(2);
    }

    @Test
    @DisplayName("回答为空或没有检索结果时不产生行")
    void emptyInputs() {
        assertThat(CitationExtractor.extract("", HITS)).isEmpty();
        assertThat(CitationExtractor.extract("废料杯在哪", List.of())).isEmpty();
    }
}
