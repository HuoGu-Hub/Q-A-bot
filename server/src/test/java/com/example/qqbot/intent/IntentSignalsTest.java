package com.example.qqbot.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 规则层的正负样本。
 *
 * <p>负样本不是凑数的：三条都来自真实日志，都是"光认关键词就会判错"的形状。
 * 它们必须一直绿着 —— 改词表时先看这里。
 */
class IntentSignalsTest {

    @Test
    @DisplayName("社交：要完整称呼形态，光有「主人」不算")
    void socialNeedsFullForm() {
        assertEquals(TurnMove.SOCIAL, IntentSignals.classify("叫我主人"));
        assertEquals(TurnMove.SOCIAL, IntentSignals.classify("卖个萌"));
        assertEquals(TurnMove.SOCIAL, IntentSignals.classify("你是废物！"));
        // 误伤回归：这是在问地名，不是在使唤人
        assertEquals(TurnMove.DEFAULT, IntentSignals.classify("你说一下长守镇的主人是谁"));
    }

    @Test
    @DisplayName("元问题：必须以「你是 / 你有」开头")
    void metaNeedsLeadingSubject() {
        assertEquals(TurnMove.META, IntentSignals.classify("你是什么模型？"));
        assertEquals(TurnMove.META, IntentSignals.classify("你现在有啥能力"));
        // 误伤回归："是谁" 出现在句尾不算问 bot 自己
        assertEquals(TurnMove.DEFAULT, IntentSignals.classify("你说说碧潭飘雪是谁"));
    }

    @Test
    @DisplayName("自我修正：改口之后按上半句判必然错，直接放弃")
    void selfCorrectionWins() {
        assertEquals(TurnMove.DEFAULT,
                IntentSignals.classify("看看腿，我的意思是看看雾锁里面功能性性价比最好的腿部装备"));
    }

    @Test
    @DisplayName("纠正：区分「你又说错了」和「我说错了」")
    void correctionVersusSelfBlame() {
        assertEquals(TurnMove.CORRECT, IntentSignals.classify("你又说错了，独立npc还有收藏家和春节商人"));
        assertEquals(TurnMove.CORRECT, IntentSignals.classify("不是185吗"));
        assertEquals(TurnMove.DEFAULT, IntentSignals.classify("我说错了，是那个蓝色的"));
    }

    @Test
    @DisplayName("做不到的请求：帮着上班、生成图片、下单")
    void outOfScope() {
        assertEquals(TurnMove.OUT_OF_SCOPE, IntentSignals.classify("帮我上班"));
        assertEquals(TurnMove.OUT_OF_SCOPE, IntentSignals.classify("生成碧潭飘雪女仆装图片"));
        assertEquals(TurnMove.OUT_OF_SCOPE, IntentSignals.classify("帮我给碧潭飘雪点一杯奶茶"));
        // "帮我查一下"是在范围内的，不能被"帮我"带走
        assertEquals(TurnMove.DEFAULT, IntentSignals.classify("帮我查一下香菇在哪刷新"));
    }

    @Test
    @DisplayName("正常提问一律默认，不抢现有链路")
    void factsStayDefault() {
        assertEquals(TurnMove.DEFAULT, IntentSignals.classify("香菇在哪刷新"));
        assertEquals(TurnMove.DEFAULT, IntentSignals.classify("近战双手武器推荐"));
        assertEquals(TurnMove.DEFAULT, IntentSignals.classify(""));
        assertEquals(TurnMove.DEFAULT, IntentSignals.classify(null));
    }
}
