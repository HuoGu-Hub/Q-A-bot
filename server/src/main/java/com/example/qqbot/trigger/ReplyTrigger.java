package com.example.qqbot.trigger;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 概率回复的判定器。
 *
 * <p>顺序固定：群白名单 → 意图分类 → 概率 → 节奏 → 抽样 → 占位。
 * 任何一步不通过都返回 {@code reply=false}，由 Guard 的 trigger 阶段静默丢弃。
 * **保证不抛异常**：判定挂掉时按噪音处理，绝不能因为判定影响正常提问。
 */
@Component
public class ReplyTrigger {

    private static final Logger log = LoggerFactory.getLogger(ReplyTrigger.class);

    /** 闲聊从 p=0.5 开始解禁 */
    private static final double CHAT_THRESHOLD = 0.5;

    private final TriggerPolicy policy;
    private final ReplyPacer pacer;
    private final DoubleSupplier random;

    @org.springframework.beans.factory.annotation.Autowired
    public ReplyTrigger(TriggerPolicy policy, ReplyPacer pacer) {
        this(policy, pacer, ThreadLocalRandom.current()::nextDouble);
    }

    ReplyTrigger(TriggerPolicy policy, ReplyPacer pacer, DoubleSupplier random) {
        this.policy = policy;
        this.pacer = pacer;
        this.random = random;
    }

    @PostConstruct
    void logConfig() {
        log.info("[TRIGGER] 概率回复：p={} groups={} minGap={}s userCooldown={}s dailyMax={} shadow={}",
                policy.probability(), policy.enabledGroups(), policy.minGapSeconds(),
                policy.userCooldownSeconds(), policy.dailyMaxPerGroup(), policy.shadowMode());
    }

    public TriggerDecision decide(long groupId, long userId, String text,
                                  boolean hasImage, boolean atOther) {
        try {
            if (!policy.enabledForGroup(groupId)) {
                return TriggerDecision.no(ReplyIntent.NOISE, "群未启用");
            }
            ReplyIntent intent = ReplySignals.classify(text, hasImage, atOther);
            if (intent == ReplyIntent.NOISE) {
                return TriggerDecision.no(intent, "噪音");
            }
            double chance = chance(policy.probability(), intent);
            if (chance <= 0) {
                return TriggerDecision.no(intent, "概率为 0");
            }
            if (!pacer.allows(groupId, userId)) {
                return TriggerDecision.no(intent, "节奏未到");
            }
            double roll = random.getAsDouble();
            if (roll >= chance) {
                TriggerDecision miss = TriggerDecision.no(intent, "未命中");
                log.debug("[TRIGGER] 未命中 group={} user={} intent={} chance={} roll={} | {}",
                        groupId, userId, intent, fmt(chance), fmt(roll), brief(text));
                return miss;
            }
            if (policy.shadowMode()) {
                TriggerDecision would = TriggerDecision.would(intent, chance, roll);
                log.info("[TRIGGER] shadow=1 group={} user={} intent={} chance={} roll={} would=reply | {}",
                        groupId, userId, intent, fmt(chance), fmt(roll), brief(text));
                return would;
            }
            if (!pacer.tryReserve(groupId, userId)) {
                return TriggerDecision.no(intent, "并发占位失败");
            }
            TriggerDecision yes = TriggerDecision.yes(intent, chance, roll);
            log.info("[TRIGGER] group={} user={} intent={} chance={} roll={} -> reply | {}",
                    groupId, userId, intent, fmt(chance), fmt(roll), brief(text));
            return yes;
        } catch (Exception e) {
            log.warn("[TRIGGER] 判定失败，按噪音处理：{}", e.getMessage());
            return TriggerDecision.no(ReplyIntent.NOISE, "判定失败");
        }
    }

    /**
     * 概率曲线。
     *
     * <pre>
     *   呼叫 = 1，不走这里
     *   疑问 = p
     *   闲聊 = p × max(0, (p - 0.5) / 0.5)
     *   噪音 = 0
     * </pre>
     */
    public static double chance(double p, ReplyIntent intent) {
        double safe = Math.max(0.0, Math.min(1.0, p));
        if (intent == ReplyIntent.QUESTION) {
            return safe;
        }
        if (intent == ReplyIntent.CHAT) {
            if (safe <= CHAT_THRESHOLD) {
                return 0.0;
            }
            return safe * ((safe - CHAT_THRESHOLD) / (1 - CHAT_THRESHOLD));
        }
        return 0.0;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replace('\n', ' ').strip();
        return t.length() <= 80 ? t : t.substring(0, 80) + "…";
    }
}
