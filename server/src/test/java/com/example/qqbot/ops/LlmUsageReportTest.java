package com.example.qqbot.ops;

import com.example.qqbot.persistence.LlmUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聚合规则：日期补零、估算值作为子集、模型按用量排序。
 *
 * <p>这些规则错了页面不会报错，只会静默少一天、或者把估算值算进真实值里，
 * 所以单独钉住。
 */
class LlmUsageReportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    private static LlmUsageRepository.Row row(String day, String provider, String model,
                                              boolean estimated, long calls,
                                              long tokensIn, long tokensOut) {
        return new LlmUsageRepository.Row(day, provider, model, estimated, calls, tokensIn, tokensOut);
    }

    private static LlmUsageLedger.Bucket bucket(String day, String provider, String model,
                                                boolean estimated, long calls,
                                                long tokensIn, long tokensOut) {
        return new LlmUsageLedger.Bucket(day, provider, model, estimated, calls, tokensIn, tokensOut);
    }

    @Test
    @DisplayName("★ 日期补零：窗口里没数据的日期也要出现，图上不能出现缺口")
    void fillsEveryDay() {
        LlmUsageReport.UsageReport report = LlmUsageReport.build(TODAY, 3,
                List.of(row("2026-11-08", "a", "m", false, 1, 10, 2)), List.of());

        assertThat(report.daily()).hasSize(3);
        assertThat(report.daily().get(0).day()).isEqualTo("2026-11-06");
        assertThat(report.daily().get(0).calls()).isZero();
        assertThat(report.daily().get(2).tokensIn()).isEqualTo(10);
        assertThat(report.totals().days()).isEqualTo(3);
    }

    @Test
    @DisplayName("估算值是子集，不是额外一笔")
    void estimatedIsSubset() {
        LlmUsageReport.UsageReport report = LlmUsageReport.build(TODAY, 1, List.of(
                row("2026-11-08", "a", "m", false, 1, 100, 20),
                row("2026-11-08", "a", "m", true, 1, 40, 8)), List.of());

        assertThat(report.totals().tokensIn()).isEqualTo(140);
        assertThat(report.totals().tokensOut()).isEqualTo(28);
        assertThat(report.totals().estimatedIn()).isEqualTo(40);
        assertThat(report.totals().estimatedOut()).isEqualTo(8);
    }

    @Test
    @DisplayName("还没落库的账也要算进去，否则今日总量少一截")
    void pendingCounts() {
        LlmUsageReport.UsageReport report = LlmUsageReport.build(TODAY, 1,
                List.of(row("2026-11-08", "a", "m", false, 1, 10, 2)),
                List.of(bucket("2026-11-08", "a", "m", false, 1, 5, 1)));

        assertThat(report.totals().calls()).isEqualTo(2);
        assertThat(report.totals().tokensIn()).isEqualTo(15);
        assertThat(report.daily().get(0).tokensIn()).isEqualTo(15);
    }

    @Test
    @DisplayName("模型按总量倒序，同额按调用次数，顺序稳定")
    void modelsAreSortedByUsage() {
        LlmUsageReport.UsageReport report = LlmUsageReport.build(TODAY, 1, List.of(
                row("2026-11-08", "a", "small", false, 1, 10, 1),
                row("2026-11-08", "b", "big", false, 1, 100, 10),
                row("2026-11-08", "c", "same", false, 1, 100, 10)), List.of());

        assertThat(report.models()).extracting(LlmUsageReport.ModelUsage::model)
                .containsExactly("big", "same", "small");
    }

    @Test
    @DisplayName("窗口外的行不混进来；坏日期被忽略而不是抛异常")
    void ignoresOutOfRangeAndBadDates() {
        LlmUsageReport.UsageReport report = LlmUsageReport.build(TODAY, 2, List.of(
                row("2026-11-01", "a", "old", false, 1, 999, 1),
                row("not-a-day", "a", "bad", false, 1, 999, 1),
                row("2026-11-08", "a", "m", false, 1, 1, 1)), List.of());

        assertThat(report.totals().tokensIn()).isEqualTo(1);
        assertThat(report.models()).hasSize(1);
    }
}