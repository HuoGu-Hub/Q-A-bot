package com.example.qqbot.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 触发分类的验收 —— 全是纯函数，不联网、不起 Spring。
 *
 * <p>反例直接来自群里的真实说法：反问、吐槽、问别人、复读。
 */
class ReplySignalsTest {

    @Test
    @DisplayName("疑问：问号、疑问词、求助词")
    void questions() {
        assertThat(ReplySignals.classify("雾锁的钥匙怎么拿", false, false)).isEqualTo(ReplyIntent.QUESTION);
        assertThat(ReplySignals.classify("这个在哪", false, false)).isEqualTo(ReplyIntent.QUESTION);
        assertThat(ReplySignals.classify("求推荐一套装备", false, false)).isEqualTo(ReplyIntent.QUESTION);
        assertThat(ReplySignals.classify("帮我看看这个", false, false)).isEqualTo(ReplyIntent.QUESTION);
        assertThat(ReplySignals.classify("谁知道这个怎么弄", false, false)).isEqualTo(ReplyIntent.QUESTION);
        assertThat(ReplySignals.classify("今天开服吗？", false, false)).isEqualTo(ReplyIntent.QUESTION);
        assertThat(ReplySignals.classify("有没有人知道煤矿在哪里啊", false, false)).isEqualTo(ReplyIntent.QUESTION);
    }

    @Test
    @DisplayName("严格疑问：问号、句末语气或句首疑问；埋在句中只算宽松")
    void strictQuestions() {
        assertThat(ReplySignals.isStrictQuestion("有没有人知道煤矿在哪里啊")).isTrue();
        assertThat(ReplySignals.strictQuestionEvidence("有没有人知道煤矿在哪里啊"))
                .isEqualTo("句首「有没有人」");
        assertThat(ReplySignals.strictQuestionEvidence("今天开服吗")).isEqualTo("「吗」结尾");
        assertThat(ReplySignals.questionEvidence("这个钥匙哪里拿")).isEqualTo("命中「哪里」");
        assertThat(ReplySignals.isStrictQuestion("今天开服吗？")).isTrue();
        assertThat(ReplySignals.isStrictQuestion("今天开服吗")).isTrue();
        assertThat(ReplySignals.isStrictQuestion("请问钥匙在哪")).isTrue();
        assertThat(ReplySignals.isStrictQuestion("帮我看看这个")).isTrue();
        assertThat(ReplySignals.isStrictQuestion("怎么拿钥匙")).isTrue();

        assertThat(ReplySignals.isStrictQuestion("这个钥匙哪里拿")).isFalse();
        assertThat(ReplySignals.isStrictQuestion("煤矿在哪里啊")).isFalse();
        assertThat(ReplySignals.isStrictQuestion("怎么又卡了")).isFalse();
        assertThat(ReplySignals.isStrictQuestion("不是应该这样吗")).isFalse();
    }

    @Test
    @DisplayName("严格度：句首/问号 3，语气词 2；松判定：高置信 2，泛词 1")
    void questionStrength() {
        assertThat(ReplySignals.strictQuestionStrength("有没有人知道煤矿在哪里啊")).isEqualTo(3);
        assertThat(ReplySignals.strictQuestionStrength("今天开服吗？")).isEqualTo(3);
        assertThat(ReplySignals.strictQuestionStrength("今天开服吗")).isEqualTo(2);
        assertThat(ReplySignals.strictQuestionStrength("这个钥匙哪里拿")).isZero();

        assertThat(ReplySignals.looseQuestionStrength("今天开服吗")).isEqualTo(3);
        assertThat(ReplySignals.looseQuestionStrength("这个钥匙哪里拿")).isEqualTo(2);
        assertThat(ReplySignals.looseQuestionStrength("这个是什么")).isEqualTo(1);
        assertThat(ReplySignals.looseQuestionStrength("今天真好看")).isZero();
    }

    @Test
    @DisplayName("游戏相关度：强词 4，通用词 2，弱词 1，配置词按强词")
    void gameRelevance() {
        assertThat(ReplySignals.gameRelevance("有没有人知道煤矿在哪里啊", null).score()).isEqualTo(4);
        assertThat(ReplySignals.gameRelevance("雾锁王国的钥匙在哪", null).score()).isEqualTo(4);
        assertThat(ReplySignals.gameRelevance("这个游戏怎么玩", null).score()).isEqualTo(2);
        assertThat(ReplySignals.gameRelevance("这个钥匙哪里拿", null).score()).isEqualTo(2);
        assertThat(ReplySignals.gameRelevance("有没有人知道开门", null).score()).isEqualTo(1);
        assertThat(ReplySignals.gameRelevance("今天吃什么", null).score()).isZero();
        assertThat(ReplySignals.gameRelevance("深渊怎么去", List.of("深渊")).score()).isEqualTo(4);
    }

    @Test
    @DisplayName("反问与吐槽不当疑问：怎么会、没什么、不是…吗")
    void rhetoricalIsChat() {
        assertThat(ReplySignals.classify("怎么会这样", false, false)).isEqualTo(ReplyIntent.CHAT);
        assertThat(ReplySignals.classify("没什么", false, false)).isEqualTo(ReplyIntent.CHAT);
        assertThat(ReplySignals.classify("不是应该这样吗", false, false)).isEqualTo(ReplyIntent.CHAT);
        assertThat(ReplySignals.classify("哪怕这样也行", false, false)).isEqualTo(ReplyIntent.CHAT);
    }

    @Test
    @DisplayName("问别人：即使有疑问词也降级为闲聊")
    void askedSomeoneElseIsChat() {
        assertThat(ReplySignals.classify("这个怎么弄", false, true)).isEqualTo(ReplyIntent.CHAT);
    }

    @Test
    @DisplayName("噪音：纯图、链接、复读、两个字以内、纯标点")
    void noise() {
        assertThat(ReplySignals.classify("hhhh", false, false)).isEqualTo(ReplyIntent.NOISE);
        assertThat(ReplySignals.classify("666", false, false)).isEqualTo(ReplyIntent.NOISE);
        assertThat(ReplySignals.classify("？", false, false)).isEqualTo(ReplyIntent.NOISE);
        assertThat(ReplySignals.classify("。。", false, false)).isEqualTo(ReplyIntent.NOISE);
        assertThat(ReplySignals.classify("https://example.com/a", false, false)).isEqualTo(ReplyIntent.NOISE);
        assertThat(ReplySignals.classify("图片", true, false)).isEqualTo(ReplyIntent.NOISE);
        assertThat(ReplySignals.classify("看图", true, false)).isEqualTo(ReplyIntent.NOISE);
        assertThat(ReplySignals.classify("在吗", false, false)).isEqualTo(ReplyIntent.NOISE);
        assertThat(ReplySignals.classify("/help 怎么用", false, false)).isEqualTo(ReplyIntent.NOISE);
    }

    @Test
    @DisplayName("带图的疑问仍然放行，带图的闲聊按噪音")
    void imageOnlyForQuestion() {
        assertThat(ReplySignals.classify("这个怎么去", true, false)).isEqualTo(ReplyIntent.QUESTION);
        assertThat(ReplySignals.classify("今天真好看", true, false)).isEqualTo(ReplyIntent.NOISE);
    }

    @Test
    @DisplayName("昵称空时不把每条消息都当呼叫")
    void emptyNameNeverCalls() {
        assertThat(ReplySignals.mentionsName("飘雪喵在吗", "飘雪喵")).isTrue();
        assertThat(ReplySignals.mentionsName("在吗", "")).isFalse();
        assertThat(ReplySignals.mentionsName("在吗", null)).isFalse();
    }
}
