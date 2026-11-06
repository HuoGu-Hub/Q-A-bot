package com.example.qqbot.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
