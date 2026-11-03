package com.example.qqbot.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计价口径：价目按每百万 token 元，成本按微元整数。
 * 1000 入、500 出，单价 10 / 20 → 1000×10 + 500×20 = 20000 微元 = 0.02 元。
 */
class EvalPricingTest {

    @Test
    @DisplayName("微元换算不产生浮点尾差")
    void convertsToMicroYuan() {
        assertThat(EvalPricing.tokensCost(1000, 500, 10, 20)).isEqualTo(20_000L);
        assertThat(EvalPricing.tokensCost(1_000_000, 0, 3, 15)).isEqualTo(3_000_000L);
        assertThat(EvalPricing.tokensCost(0, 0, 3, 15)).isZero();
    }

    @Test
    @DisplayName("单价为 0 时成本就是 0，不是负数")
    void zeroPrices() {
        assertThat(EvalPricing.tokensCost(1234, 567, 0, 0)).isZero();
    }
}
