package com.example.qqbot.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentPlannerTest {

    /** 只实现判定用得到的那几项 */
    private static IntentPolicy policy(boolean enabled, boolean skipRetrieval) {
        return new IntentPolicy() {
            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public boolean isSkipRetrieval() {
                return skipRetrieval;
            }

            @Override
            public boolean isCorrection() {
                return true;
            }

            @Override
            public int getRecentTurns() {
                return 3;
            }

            @Override
            public int getRecentMinutes() {
                return 30;
            }
        };
    }

    @Test
    @DisplayName("总开关关掉：一律默认，与接入前一致")
    void disabledReturnsStandard() {
        TurnPlan plan = new IntentPlanner(policy(false, true)).plan("卖个萌");
        assertEquals(TurnMove.DEFAULT, plan.move());
        assertTrue(plan.retrieve());
    }

    @Test
    @DisplayName("社交 / 元问题 / 做不到：不检索")
    void skipsRetrieval() {
        IntentPlanner planner = new IntentPlanner(policy(true, true));
        assertFalse(planner.plan("卖个萌").retrieve());
        assertFalse(planner.plan("你是什么模型").retrieve());
        assertFalse(planner.plan("帮我上班").retrieve());
    }

    @Test
    @DisplayName("功能开关关掉：照常检索，判定只记账")
    void skipFlagOffKeepsRetrieval() {
        TurnPlan plan = new IntentPlanner(policy(true, false)).plan("卖个萌");
        assertEquals(TurnMove.SOCIAL, plan.move());
        assertTrue(plan.retrieve());
    }

    @Test
    @DisplayName("正常提问不受影响")
    void factsStillRetrieve() {
        TurnPlan plan = new IntentPlanner(policy(true, true)).plan("香菇在哪刷新");
        assertEquals(TurnMove.DEFAULT, plan.move());
        assertTrue(plan.retrieve());
    }
}
