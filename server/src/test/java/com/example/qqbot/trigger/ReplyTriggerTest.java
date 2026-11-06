package com.example.qqbot.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/** 概率档位、关键词与判定顺序 */
class ReplyTriggerTest {

    private static final long GROUP = 1L;
    private static final long USER = 2L;

    @Test
    @DisplayName("p=0：只回呼叫，疑问、关键词、闲聊都不回")
    void pZero() {
        ReplyTrigger t = trigger(0.0, false, 0, 0.1, "煤矿");
        assertThat(t.decide(GROUP, USER, "有没有人知道煤矿在哪里啊", false, false).reply()).isFalse();
        assertThat(t.decide(GROUP, USER, "煤矿", false, false).reply()).isFalse();
        assertThat(t.decide(GROUP, USER, "今天又卡bug了", false, false).reply()).isFalse();
    }

    @Test
    @DisplayName("0.2：严格疑问与关键词回复，宽松疑问与闲聊不回复")
    void lowTier() {
        ReplyTrigger strict = trigger(0.2, false, 0, 0.9);
        TriggerDecision d = strict.decide(GROUP, USER, "有没有人知道煤矿在哪里啊", false, false);
        assertThat(d.reply()).isTrue();
        assertThat(d.intent()).isEqualTo(ReplyIntent.QUESTION);
        assertThat(d.reason()).isEqualTo("严格疑问");
        assertThat(d.evidence()).isEqualTo("严格疑问：句首「有没有人」");
        assertThat(d.chance()).isEqualTo(1.0);
        assertThat(d.roll()).isZero();

        ReplyTrigger loose = trigger(0.2, false, 0, 0.1);
        assertThat(loose.decide(GROUP, USER, "这个钥匙哪里拿", false, false).reply()).isFalse();
        assertThat(loose.decide(GROUP, USER, "煤矿在哪里啊", false, false).reply()).isFalse();

        ReplyTrigger keyword = trigger(0.2, false, 0, 0.1, "煤矿");
        TriggerDecision k = keyword.decide(GROUP, USER, "今天煤矿又塌了", false, false);
        assertThat(k.reply()).isTrue();
        assertThat(k.intent()).isEqualTo(ReplyIntent.KEYWORD);
        assertThat(k.reason()).isEqualTo("关键词");
        assertThat(k.evidence()).isEqualTo("关键词「煤矿」");

        ReplyTrigger chat = trigger(0.2, false, 0, 0.1);
        assertThat(chat.decide(GROUP, USER, "今天又卡bug了", false, false).reply()).isFalse();
    }

    @Test
    @DisplayName("0.3 起疑问全部放行，闲聊仍不接")
    void middleTier() {
        ReplyTrigger t = trigger(0.4, false, 0, 0.1);
        assertThat(t.decide(GROUP, USER, "这个钥匙哪里拿", false, false).reply()).isTrue();
        assertThat(t.decide(GROUP, USER, "煤矿在哪里啊", false, false).reply()).isTrue();
        TriggerDecision chat = t.decide(GROUP, USER, "今天又卡bug了", false, false);
        assertThat(chat.reply()).isFalse();
        assertThat(chat.reason()).isEqualTo("该档只回疑问");

        ReplyTrigger bound = trigger(0.3, false, 0, 0.1);
        assertThat(bound.decide(GROUP, USER, "这个钥匙哪里拿", false, false).reply()).isTrue();
    }

    @Test
    @DisplayName("0.6-0.8：低频闲聊，10% 起")
    void lowChatTier() {
        ReplyTrigger hit = trigger(0.6, false, 0, 0.05);
        TriggerDecision d = hit.decide(GROUP, USER, "今天又卡bug了", false, false);
        assertThat(d.reply()).isTrue();
        assertThat(d.intent()).isEqualTo(ReplyIntent.CHAT);
        assertThat(d.chance()).isCloseTo(0.10, offset(1e-9));
        assertThat(d.reason()).isEqualTo("闲聊命中");

        ReplyTrigger miss = trigger(0.6, false, 0, 0.20);
        TriggerDecision m = miss.decide(GROUP, USER, "今天又卡bug了", false, false);
        assertThat(m.reply()).isFalse();
        assertThat(m.reason()).isEqualTo("未命中");
        assertThat(m.chance()).isCloseTo(0.10, offset(1e-9));
        assertThat(m.roll()).isEqualTo(0.20);
        assertThat(m.evidence()).isEqualTo("低频率闲聊");

        assertThat(hit.decide(GROUP, USER, "这个钥匙哪里拿", false, false).reply()).isTrue();
    }

    @Test
    @DisplayName("0.8-1：高频闲聊，50% 起")
    void highChatTier() {
        ReplyTrigger hit = trigger(0.8, false, 0, 0.40);
        TriggerDecision d = hit.decide(GROUP, USER, "今天又卡bug了", false, false);
        assertThat(d.reply()).isTrue();
        assertThat(d.chance()).isCloseTo(0.50, offset(1e-9));

        ReplyTrigger miss = trigger(0.8, false, 0, 0.60);
        TriggerDecision m = miss.decide(GROUP, USER, "今天又卡bug了", false, false);
        assertThat(m.reply()).isFalse();
        assertThat(m.reason()).isEqualTo("未命中");
        assertThat(m.chance()).isCloseTo(0.50, offset(1e-9));
    }

    @Test
    @DisplayName("免@阈值：p 低于阈值时非呼叫消息一律不回")
    void mentionThreshold() {
        ReplyTrigger low = triggerAt(0.2, 0.5, false, 0, 0.1);
        TriggerDecision d = low.decide(GROUP, USER, "有没有人知道煤矿在哪里啊", false, false);
        assertThat(d.reply()).isFalse();
        assertThat(d.reason()).isEqualTo("低于免@阈值");
        assertThat(d.evidence()).startsWith("p=0.20 < 免@阈值");
        assertThat(d.intent()).isEqualTo(ReplyIntent.QUESTION);

        ReplyTrigger at = triggerAt(0.5, 0.5, false, 0, 0.1);
        assertThat(at.decide(GROUP, USER, "有没有人知道煤矿在哪里啊", false, false).reply()).isTrue();

        ReplyTrigger keyword = triggerAt(0.2, 0.5, false, 0, 0.1, "煤矿");
        assertThat(keyword.decide(GROUP, USER, "煤矿", false, false).reply()).isFalse();
    }

    @Test
    @DisplayName("p=1：闲聊也必回，仍受每日上限")
    void pOne() {
        ReplyTrigger t = trigger(1.0, false, 1, 0.99);
        assertThat(t.decide(GROUP, USER, "今天又卡bug了", false, false).reply()).isTrue();
        TriggerDecision second = t.decide(GROUP, USER + 1, "今天又卡bug了", false, false);
        assertThat(second.reply()).isFalse();
        assertThat(second.reason()).isEqualTo("今日额度用完");
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
    @DisplayName("影子模式：想回但不发送，原因保留档位")
    void shadow() {
        ReplyTrigger t = trigger(1.0, true, 0, 0.1);
        TriggerDecision d = t.decide(GROUP, USER, "这个在哪", false, false);
        assertThat(d.reply()).isFalse();
        assertThat(d.wouldReply()).isTrue();
        assertThat(d.reason()).startsWith("影子模式：");
    }

    @Test
    @DisplayName("噪音不参与抽样；问别人在低档不抢答")
    void noiseAndOther() {
        ReplyTrigger t = trigger(1.0, false, 0, 0.1);
        TriggerDecision noise = t.decide(GROUP, USER, "hhhh", false, false);
        assertThat(noise.reply()).isFalse();
        assertThat(noise.reason()).isEqualTo("噪音");

        ReplyTrigger low = trigger(0.2, false, 0, 0.1);
        assertThat(low.decide(GROUP, USER, "这个怎么弄", false, true).reply()).isFalse();
    }

    @Test
    @DisplayName("曲线：档位单调，关键词与疑问必答")
    void curve() {
        assertThat(ReplyTrigger.chance(0.0, ReplyIntent.QUESTION)).isZero();
        assertThat(ReplyTrigger.chance(0.2, ReplyIntent.QUESTION)).isEqualTo(1.0);
        assertThat(ReplyTrigger.chance(0.2, ReplyIntent.KEYWORD)).isEqualTo(1.0);
        assertThat(ReplyTrigger.chance(0.2, ReplyIntent.CHAT)).isZero();
        assertThat(ReplyTrigger.chance(0.59, ReplyIntent.CHAT)).isZero();
        assertThat(ReplyTrigger.chance(0.6, ReplyIntent.CHAT)).isCloseTo(0.10, offset(1e-9));
        assertThat(ReplyTrigger.chance(0.8, ReplyIntent.CHAT)).isCloseTo(0.50, offset(1e-9));
        assertThat(ReplyTrigger.chance(1.0, ReplyIntent.CHAT)).isEqualTo(1.0);
        assertThat(ReplyTrigger.chance(1.0, ReplyIntent.NOISE)).isZero();
    }

    private static ReplyTrigger trigger(double p, boolean shadow, int dailyMax, double roll,
                                        String... keywords) {
        return triggerAt(p, 0.0, shadow, dailyMax, roll, keywords);
    }

    private static ReplyTrigger triggerAt(double p, double threshold, boolean shadow,
                                          int dailyMax, double roll, String... keywords) {
        Policy policy = new Policy();
        policy.probability = p;
        policy.mentionFree = threshold;
        policy.shadow = shadow;
        policy.dailyMax = dailyMax;
        policy.keywords = List.of(keywords);
        return new ReplyTrigger(policy, new ReplyPacer(policy), () -> roll);
    }

    private static final class Policy implements TriggerPolicy {
        double probability;
        boolean shadow;
        int dailyMax;
        double mentionFree;
        List<String> keywords = List.of();

        @Override public double probability() { return probability; }
        @Override public List<Long> enabledGroups() { return List.of(GROUP); }
        @Override public int dailyMaxPerGroup() { return dailyMax; }
        @Override public boolean shadowMode() { return shadow; }
        @Override public double mentionFreeThreshold() { return mentionFree; }
        @Override public List<String> keywords() { return keywords; }
    }
}
