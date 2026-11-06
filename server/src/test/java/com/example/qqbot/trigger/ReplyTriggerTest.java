package com.example.qqbot.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 概率曲线与判定顺序 */
class ReplyTriggerTest {

    private static final long GROUP = 1L;
    private static final long USER = 2L;

    @Test
    @DisplayName("p=0：只回呼叫，疑问与闲聊都不回")
    void pZero() {
        ReplyTrigger t = trigger(0.0, false, 0, 0.1);
        assertThat(t.decide(GROUP, USER, "雾锁的钥匙怎么拿", false, false).reply()).isFalse();
        assertThat(t.decide(GROUP, USER, "今天又卡bug了", false, false).reply()).isFalse();
    }

    @Test
    @DisplayName("p=0.3：只碰疑问，闲聊概率为 0")
    void lowProbability() {
        ReplyTrigger hit = trigger(0.3, false, 0, 0.1);
        assertThat(hit.decide(GROUP, USER, "雾锁的钥匙怎么拿", false, false).reply()).isTrue();

        ReplyTrigger miss = trigger(0.3, false, 0, 0.5);
        assertThat(miss.decide(GROUP, USER, "雾锁的钥匙怎么拿", false, false).reply()).isFalse();

        ReplyTrigger chat = trigger(0.3, false, 0, 0.1);
        assertThat(chat.decide(GROUP, USER, "今天又卡bug了", false, false).reply()).isFalse();
    }

    @Test
    @DisplayName("p=0.8：闲聊概率 0.48，0.4 命中、0.5 不中")
    void highProbabilityChat() {
        ReplyTrigger hit = trigger(0.8, false, 0, 0.4);
        assertThat(hit.decide(GROUP, USER, "今天又卡bug了", false, false).reply()).isTrue();

        ReplyTrigger miss = trigger(0.8, false, 0, 0.5);
        assertThat(miss.decide(GROUP, USER, "今天又卡bug了", false, false).reply()).isFalse();
    }

    @Test
    @DisplayName("p=1：疑问必回，仍受每日上限")
    void pOne() {
        ReplyTrigger t = trigger(1.0, false, 1, 0.99);
        assertThat(t.decide(GROUP, USER, "这个在哪", false, false).reply()).isTrue();
        TriggerDecision second = t.decide(GROUP, USER + 1, "那个在哪", false, false);
        assertThat(second.reply()).isFalse();
        assertThat(second.reason()).isEqualTo("节奏未到");
    }

    @Test
    @DisplayName("群白名单：不在名单里直接拒绝")
    void groupNotEnabled() {
        ReplyTrigger t = trigger(1.0, false, 0, 0.1);
        TriggerDecision d = t.decide(999L, USER, "这个在哪", false, false);
        assertThat(d.reply()).isFalse();
        assertThat(d.reason()).isEqualTo("群未启用");
    }

    @Test
    @DisplayName("影子模式：想回但不发送")
    void shadow() {
        ReplyTrigger t = trigger(1.0, true, 0, 0.1);
        TriggerDecision d = t.decide(GROUP, USER, "这个在哪", false, false);
        assertThat(d.reply()).isFalse();
        assertThat(d.wouldReply()).isTrue();
        assertThat(d.reason()).isEqualTo("影子模式");
    }

    @Test
    @DisplayName("噪音不参与抽样；问别人在低概率下不抢答")
    void noiseAndOther() {
        ReplyTrigger t = trigger(1.0, false, 0, 0.1);
        assertThat(t.decide(GROUP, USER, "hhhh", false, false).reply()).isFalse();

        ReplyTrigger low = trigger(0.3, false, 0, 0.1);
        assertThat(low.decide(GROUP, USER, "这个怎么弄", false, true).reply()).isFalse();
    }

    @Test
    @DisplayName("曲线：0.5 是闲聊解禁点")
    void curve() {
        assertThat(ReplyTrigger.chance(0.5, ReplyIntent.CHAT)).isZero();
        assertThat(ReplyTrigger.chance(0.75, ReplyIntent.CHAT)).isEqualTo(0.375);
        assertThat(ReplyTrigger.chance(1.0, ReplyIntent.CHAT)).isEqualTo(1.0);
        assertThat(ReplyTrigger.chance(1.0, ReplyIntent.QUESTION)).isEqualTo(1.0);
    }

    private static ReplyTrigger trigger(double p, boolean shadow, int dailyMax, double roll) {
        Policy policy = new Policy();
        policy.probability = p;
        policy.shadow = shadow;
        policy.dailyMax = dailyMax;
        ReplyPacer pacer = new ReplyPacer(policy, () -> 0L);
        return new ReplyTrigger(policy, pacer, () -> roll);
    }

    private static final class Policy implements TriggerPolicy {
        double probability;
        boolean shadow;
        int dailyMax;

        @Override public double probability() { return probability; }
        @Override public List<Long> enabledGroups() { return List.of(GROUP); }
        @Override public int minGapSeconds() { return 0; }
        @Override public int userCooldownSeconds() { return 0; }
        @Override public int dailyMaxPerGroup() { return dailyMax; }
        @Override public boolean shadowMode() { return shadow; }
    }
}
