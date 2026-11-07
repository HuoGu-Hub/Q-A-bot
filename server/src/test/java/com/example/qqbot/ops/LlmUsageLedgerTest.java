package com.example.qqbot.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按日账本的口径：同一天同一模型累加、真实与估算不混行、drain 之后清零。
 */
class LlmUsageLedgerTest {

    @Test
    @DisplayName("同一天同一模型累加，不同模型分开")
    void aggregatesByDayProviderAndModel() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        ledger.record("opencode-go", "deepseek-v4.1-flash", 100, 20, false);
        ledger.record("opencode-go", "deepseek-v4.1-flash", 50, 10, false);
        ledger.record("deepseek", "deepseek-flash", 30, 5, false);

        List<LlmUsageLedger.Bucket> buckets = ledger.pending();

        assertThat(buckets).hasSize(2);
        LlmUsageLedger.Bucket opencode = buckets.stream()
                .filter(b -> b.provider().equals("opencode-go")).findFirst().orElseThrow();
        assertThat(opencode.calls()).isEqualTo(2);
        assertThat(opencode.tokensIn()).isEqualTo(150);
        assertThat(opencode.tokensOut()).isEqualTo(30);
    }

    @Test
    @DisplayName("真实值与估算值分成两行，合计时不会互相污染")
    void keepsEstimatedSeparate() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        ledger.record("opencode-go", "deepseek-v4.1-flash", 100, 20, false);
        ledger.record("opencode-go", "deepseek-v4.1-flash", 40, 8, true);

        List<LlmUsageLedger.Bucket> buckets = ledger.pending();

        assertThat(buckets).hasSize(2);
        assertThat(buckets.stream().filter(LlmUsageLedger.Bucket::estimated)
                .mapToLong(LlmUsageLedger.Bucket::tokensIn).sum()).isEqualTo(40);
        assertThat(buckets.stream().filter(b -> !b.estimated())
                .mapToLong(LlmUsageLedger.Bucket::tokensIn).sum()).isEqualTo(100);
    }

    @Test
    @DisplayName("drain 原子地取走并清空；pending 只看不取")
    void drainClearsPendingDoesNot() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        ledger.record("opencode-go", "m", 1, 2, false);

        assertThat(ledger.pending()).hasSize(1);
        assertThat(ledger.pending()).hasSize(1);
        assertThat(ledger.drain()).hasSize(1);
        assertThat(ledger.drain()).isEmpty();
    }

    @Test
    @DisplayName("没有 provider 名字的调用无法归属，直接丢弃")
    void skipsCallsWithoutProvider() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        ledger.record("", "m", 1, 2, false);
        ledger.record(null, "m", 1, 2, false);

        assertThat(ledger.pending()).isEmpty();
    }
}
