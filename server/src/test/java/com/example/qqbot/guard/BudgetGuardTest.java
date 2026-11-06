package com.example.qqbot.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 主动插话只计全局，不消耗用户与群额度 */
class BudgetGuardTest {

    @Test
    @DisplayName("chargeQuota=false：只计全局，用户与群额度不动")
    void autoReplyOnlyChargesGlobal() {
        Budget config = new Budget();
        config.setPerUserPerDay(1);
        config.setPerGroupPerDay(1);
        config.setGlobalPerDay(10);
        BudgetGuard guard = new BudgetGuard(config);

        assertThat(guard.check(1L, 2L, false).verdict()).isEqualTo(BudgetGuard.Verdict.OK);
        guard.recordCall(1L, 2L, false);

        // 用户/群额度还是 0，正常提问可以过
        assertThat(guard.check(1L, 2L, true).verdict()).isEqualTo(BudgetGuard.Verdict.OK);
        guard.recordCall(1L, 2L, true);

        // 群额度先被检查到
        assertThat(guard.check(1L, 2L, true).verdict()).isEqualTo(BudgetGuard.Verdict.GROUP_LIMIT);
        // 换一个没被用的群，看到用户额度用尽
        assertThat(guard.check(1L, 9L, true).verdict()).isEqualTo(BudgetGuard.Verdict.USER_LIMIT);
        // 群额度用尽
        assertThat(guard.check(3L, 2L, true).verdict()).isEqualTo(BudgetGuard.Verdict.GROUP_LIMIT);
    }
}
