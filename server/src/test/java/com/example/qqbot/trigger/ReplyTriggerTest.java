package com.example.qqbot.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/** 概率档位：游戏相关严格疑问、严格疑问、松判定、闲聊 */
class ReplyTriggerTest {

    private static final long GROUP = 1L;
    private static final long USER = 2L;

    @Test
    @DisplayName("p=0 或 p<0.1：只回呼叫")
    void belowMinimumOnlyCall() {
        ReplyTrigger zero = trigger(0.0, false, 0, 0.1, "煤矿");
        assertThat(zero.decide(GROUP, USER, "有没有人知道煤矿在哪里啊", false, false).reply()).isFalse();
        assertThat(zero.decide(GROUP, USER, "煤矿", false, false).reply()).isFalse();
        assertThat(zero.decide(GROUP, USER, "今天又卡bug了", false, false).reply()).isFalse();

        ReplyTrigger low = trigger(0.05, false, 0, 0.1, "煤矿");
        TriggerDecision d = low.decide(GROUP, USER, "有没有人知道煤矿在哪里啊", false, false);
        assertThat(d.reply()).isFalse();
        assertThat(d.reason()).isEqualTo("概率过低");
    }

    @Test
    @DisplayName("0.1-0.3：必须同时是严格疑问和游戏相关")
    void gameTierRequiresBoth() {
        ReplyTrigger t = trigger(0.2, false, 0, 0.9);
        TriggerDecision hit = t.decide(GROUP, USER, "有没有人知道煤矿在哪里啊", false, false);
        assertThat(hit.reply()).isTrue();
        assertThat(hit.reason()).isEqualTo("游戏相关严格疑问");
        assertThat(hit.evidence()).contains("严格疑问").contains("煤矿");
        assertThat(hit.chance()).isEqualTo(1.0);
        assertThat(hit.roll()).isZero();

        TriggerDecision noGame = t.decide(GROUP, USER, "有没有人知道这个怎么办", false, false);
        assertThat(noGame.reply()).isFalse();
        assertThat(noGame.reason()).isEqualTo("游戏相关度不足");

        TriggerDecision loose = t.decide(GROUP, USER, "这个钥匙哪里拿", false, false);
        assertThat(loose.reply()).isFalse();
        assertThat(loose.reason()).isEqualTo("低概率只回游戏相关严格疑问");

        ReplyTrigger strongest = trigger(0.1, false, 0, 0.1);
        assertThat(strongest.decide(GROUP, USER, "有没有人知道雾锁王国的钥匙在哪", false, false).reply()).isTrue();

        ReplyTrigger genericOnly = trigger(0.1, false, 0, 0.1);
        assertThat(genericOnly.decide(GROUP, USER, "有没有人知道游戏怎么玩", false, false).reply()).isFalse();

        ReplyTrigger weak = trigger(0.3, false, 0, 0.1);
        assertThat(weak.decide(GROUP, USER, "有没有人知道开门", false, false).reply()).isTrue();

        ReplyTrigger custom = triggerAt(0.1, 0.0, false, 0, 0.1,
                List.of(), List.of("深渊"));
        assertThat(custom.decide(GROUP, USER, "有没有人知道深渊怎么去", false, false).reply()).isTrue();
    }

    @Test
    @DisplayName("关键词是显式必答，但仍受最低概率约束")
    void keywordIsExplicit() {
        ReplyTrigger t = trigger(0.2, false, 0, 0.1, "煤矿");
        TriggerDecision k = t.decide(GROUP, USER, "今天煤矿又塌了", false, false);
        assertThat(k.reply()).isTrue();
        assertThat(k.intent()).isEqualTo(ReplyIntent.KEYWORD);
        assertThat(k.evidence()).isEqualTo("关键词「煤矿」");

        ReplyTrigger tooLow = trigger(0.05, false, 0, 0.1, "煤矿");
        assertThat(tooLow.decide(GROUP, USER, "今天煤矿又塌了", false, false).reply()).isFalse();
    }

    @Test
    @DisplayName("0.3-0.6：严格疑问；低端要句首/问号，高端放宽到语气词")
    void strictTier() {
        ReplyTrigger t = trigger(0.4, false, 0, 0.1);
        assertThat(t.decide(GROUP, USER, "有没有人知道这个怎么办", false, false).reply()).isTrue();

        TriggerDecision particle = t.decide(GROUP, USER, "今天开服吗", false, false);
        assertThat(particle.reply()).isFalse();
        assertThat(particle.reason()).isEqualTo("严格度不足");

        ReplyTrigger half = trigger(0.5, false, 0, 0.1);
        assertThat(half.decide(GROUP, USER, "今天开服吗", false, false).reply()).isTrue();

        ReplyTrigger boundary = trigger(0.6, false, 0, 0.1);
        assertThat(boundary.decide(GROUP, USER, "今天开服吗", false, false).reply()).isTrue();

        TriggerDecision loose = t.decide(GROUP, USER, "这个钥匙哪里拿", false, false);
        assertThat(loose.reply()).isFalse();
        assertThat(loose.reason()).isEqualTo("该档只回严格疑问");
    }

    @Test
    @DisplayName("0.6-0.8：松判定；低端只认高置信疑问词")
    void looseTier() {
        ReplyTrigger t = trigger(0.61, false, 0, 0.1);
        assertThat(t.decide(GROUP, USER, "这个钥匙哪里拿", false, false).reply()).isTrue();

        TriggerDecision ambiguous = trigger(0.7, false, 0, 0.1)
                .decide(GROUP, USER, "这个是什么", false, false);
        assertThat(ambiguous.reply()).isFalse();
        assertThat(ambiguous.reason()).isEqualTo("疑问强度不足");

        assertThat(trigger(0.8, false, 0, 0.1)
                .decide(GROUP, USER, "这个是什么", false, false).reply()).isTrue();

        TriggerDecision chat = trigger(0.8, false, 0, 0.1)
                .decide(GROUP, USER, "今天又卡bug了", false, false);
        assertThat(chat.reply()).isFalse();
        assertThat(chat.reason()).isEqualTo("该档只回疑问");
    }

    @Test
    @DisplayName("0.8-1：疑问必答，闲聊按二次曲线抽样")
    void chatTier() {
        ReplyTrigger low = trigger(0.81, false, 0, 0.01);
        TriggerDecision hit = low.decide(GROUP, USER, "今天又卡bug了", false, false);
        assertThat(hit.reply()).isTrue();
        assertThat(hit.chance()).isCloseTo(0.052375, offset(1e-9));
        assertThat(hit.evidence()).isEqualTo("高频率闲聊");

        ReplyTrigger mid = trigger(0.9, false, 0, 0.29);
        TriggerDecision miss = mid.decide(GROUP, USER, "今天又卡bug了", false, false);
        assertThat(miss.reply()).isFalse();
        assertThat(miss.chance()).isCloseTo(0.2875, offset(1e-9));
        assertThat(miss.roll()).isEqualTo(0.29);

        assertThat(mid.decide(GROUP, USER, "这个钥匙哪里拿", false, false).reply()).isTrue();

        ReplyTrigger top = trigger(1.0, false, 1, 0.99);
        assertThat(top.decide(GROUP, USER, "今天又卡bug了", false, false).reply()).isTrue();
        TriggerDecision second = top.decide(GROUP, USER + 1, "今天又卡bug了", false, false);
        assertThat(second.reply()).isFalse();
        assertThat(second.reason()).isEqualTo("今日额度用完");
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
        assertThat(d.reason()).startsWith("影子模式：");
    }

    @Test
    @DisplayName("噪音不参与抽样；问别人不抢答")
    void noiseAndOther() {
        ReplyTrigger t = trigger(1.0, false, 0, 0.1);
        TriggerDecision noise = t.decide(GROUP, USER, "hhhh", false, false);
        assertThat(noise.reply()).isFalse();
        assertThat(noise.reason()).isEqualTo("噪音");

        ReplyTrigger low = trigger(0.2, false, 0, 0.1);
        assertThat(low.decide(GROUP, USER, "这个怎么弄", false, true).reply()).isFalse();
    }

    @Test
    @DisplayName("曲线：疑问不抽样，闲聊 0.8 后按二次曲线放开")
    void curve() {
        assertThat(ReplyTrigger.chance(0.0, ReplyIntent.QUESTION)).isZero();
        assertThat(ReplyTrigger.chance(0.2, ReplyIntent.QUESTION)).isEqualTo(1.0);
        assertThat(ReplyTrigger.chance(0.2, ReplyIntent.KEYWORD)).isEqualTo(1.0);
        assertThat(ReplyTrigger.chance(0.8, ReplyIntent.CHAT)).isZero();
        assertThat(ReplyTrigger.chance(0.8001, ReplyIntent.CHAT)).isGreaterThan(0.0);
        assertThat(ReplyTrigger.chance(0.9, ReplyIntent.CHAT)).isCloseTo(0.2875, offset(1e-9));
        assertThat(ReplyTrigger.chance(1.0, ReplyIntent.CHAT)).isEqualTo(1.0);
        assertThat(ReplyTrigger.chance(1.0, ReplyIntent.NOISE)).isZero();
    }

    @Test
    @DisplayName("边界：0.3 切游戏，0.6 切松判定")
    void boundaries() {
        ReplyTrigger atGame = trigger(0.3, false, 0, 0.1);
        assertThat(atGame.decide(GROUP, USER, "有没有人知道这个怎么办", false, false).reply()).isFalse();

        ReplyTrigger justOverGame = trigger(0.31, false, 0, 0.1);
        assertThat(justOverGame.decide(GROUP, USER, "有没有人知道这个怎么办", false, false).reply()).isTrue();

        ReplyTrigger atStrict = trigger(0.6, false, 0, 0.1);
        assertThat(atStrict.decide(GROUP, USER, "这个钥匙哪里拿", false, false).reply()).isFalse();

        ReplyTrigger justOverStrict = trigger(0.61, false, 0, 0.1);
        assertThat(justOverStrict.decide(GROUP, USER, "这个钥匙哪里拿", false, false).reply()).isTrue();
    }

    private static ReplyTrigger trigger(double p, boolean shadow, int dailyMax, double roll,
                                        String... keywords) {
        return triggerAt(p, 0.0, shadow, dailyMax, roll, List.of(keywords), List.of());
    }

    private static ReplyTrigger triggerAt(double p, double threshold, boolean shadow,
                                          int dailyMax, double roll, String... keywords) {
        return triggerAt(p, threshold, shadow, dailyMax, roll, List.of(keywords), List.of());
    }

    private static ReplyTrigger triggerAt(double p, double threshold, boolean shadow,
                                          int dailyMax, double roll, List<String> keywords,
                                          List<String> gameKeywords) {
        Policy policy = new Policy();
        policy.probability = p;
        policy.mentionFree = threshold;
        policy.shadow = shadow;
        policy.dailyMax = dailyMax;
        policy.keywords = keywords;
        policy.gameKeywords = gameKeywords;
        return new ReplyTrigger(policy, new ReplyPacer(policy), () -> roll);
    }

    private static final class Policy implements TriggerPolicy {
        double probability;
        boolean shadow;
        int dailyMax;
        double mentionFree;
        List<String> keywords = List.of();
        List<String> gameKeywords = List.of();

        @Override public double probability() { return probability; }
        @Override public List<Long> enabledGroups() { return List.of(GROUP); }
        @Override public int dailyMaxPerGroup() { return dailyMax; }
        @Override public boolean shadowMode() { return shadow; }
        @Override public double mentionFreeThreshold() { return mentionFree; }
        @Override public List<String> keywords() { return keywords; }
        @Override public List<String> gameKeywords() { return gameKeywords; }
    }
}
