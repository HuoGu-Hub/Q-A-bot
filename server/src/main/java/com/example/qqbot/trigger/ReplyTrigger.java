package com.example.qqbot.trigger;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 概率回复的判定器。
 *
 * <p>顺序固定：群白名单 → 免@阈值 → 关键词 → 概率档位 → 意图与严格度
 * → 闲聊抽样 → 每日上限 → 影子 / 占位。
 * 任何一步不通过都返回 {@code reply=false}，由 Guard 的 trigger 阶段静默丢弃。
 * **保证不抛异常**：判定挂掉时按噪音处理，绝不能因为判定影响正常提问。
 */
@Component
public class ReplyTrigger {

    private static final Logger log = LoggerFactory.getLogger(ReplyTrigger.class);

    /** 0 < p < 0.3：只接关键词或严格疑问 */
    private static final double STRICT_MAX = 0.3;

    /** 0.3 <= p < 0.6：接所有疑问 */
    private static final double QUESTION_MAX = 0.6;

    /** 0.6 <= p < 0.8：低频闲聊；0.8 起高频 */
    private static final double LOW_CHAT_MAX = 0.8;

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
        log.info("[TRIGGER] 概率回复：p={} mentionFree={} groups={} dailyMax={} shadow={} keywords={}",
                policy.probability(), policy.mentionFreeThreshold(), policy.enabledGroups(),
                policy.dailyMaxPerGroup(), policy.shadowMode(), policy.keywords());
    }

    public TriggerDecision decide(long groupId, long userId, String text,
                                  boolean hasImage, boolean atOther) {
        try {
            if (!policy.enabledForGroup(groupId)) {
                return TriggerDecision.no(ReplyIntent.NOISE, "群未启用");
            }
            double p = clamp(policy.probability());
            if (p <= 0) {
                return TriggerDecision.no(ReplyIntent.NOISE, 0.0, 0.0, "概率为 0");
            }

            ReplyIntent intent = ReplySignals.classify(text, hasImage, atOther);

            // 主动回复概率没到阈值时，只有 @ / 昵称 / 引用这类呼叫能触发
            if (p < clamp(policy.mentionFreeThreshold())) {
                return TriggerDecision.no(intent, 0.0, 0.0, "低于免@阈值");
            }

            // 关键词是低概率档唯一的非疑问入口；p=0 时仍只回呼叫
            if (keywordHit(text)) {
                return accept(groupId, userId, text, ReplyIntent.KEYWORD, 1.0, 0.0, "关键词");
            }

            if (intent == ReplyIntent.QUESTION) {
                boolean strict = ReplySignals.isStrictQuestion(text);
                if (p < STRICT_MAX && !strict) {
                    return TriggerDecision.no(intent, 0.0, 0.0, "低概率只回关键词或严格疑问");
                }
                return accept(groupId, userId, text, intent, 1.0, 0.0,
                        strict ? "严格疑问" : "疑问");
            }

            if (p < STRICT_MAX) {
                return TriggerDecision.no(intent, 0.0, 0.0, "低概率只回关键词或严格疑问");
            }
            if (p < QUESTION_MAX) {
                return TriggerDecision.no(intent, 0.0, 0.0, "该档只回疑问");
            }
            if (intent == ReplyIntent.NOISE) {
                return TriggerDecision.no(intent, 0.0, 0.0, "噪音");
            }

            double chance = chance(p, ReplyIntent.CHAT);
            if (chance <= 0) {
                return TriggerDecision.no(intent, 0.0, 0.0, "闲聊未解禁");
            }
            if (!pacer.allows(groupId)) {
                return TriggerDecision.no(intent, chance, 0.0, "今日额度用完");
            }
            double roll = random.getAsDouble();
            if (roll >= chance) {
                log.debug("[TRIGGER] 未命中 group={} user={} intent={} chance={} roll={} | {}",
                        groupId, userId, intent, fmt(chance), fmt(roll), brief(text));
                return TriggerDecision.no(intent, chance, roll, "未命中");
            }
            return accept(groupId, userId, text, intent, chance, roll, "闲聊命中");
        } catch (Exception e) {
            log.warn("[TRIGGER] 判定失败，按噪音处理：{}", e.getMessage());
            return TriggerDecision.no(ReplyIntent.NOISE, "判定失败");
        }
    }

    /** 判定通过后的公共收口：额度、影子、并发占位、日志 */
    private TriggerDecision accept(long groupId, long userId, String text, ReplyIntent intent,
                                   double chance, double roll, String reason) {
        if (!pacer.allows(groupId)) {
            return TriggerDecision.no(intent, chance, roll, "今日额度用完");
        }
        if (policy.shadowMode()) {
            TriggerDecision would = TriggerDecision.would(intent, chance, roll, "影子模式：" + reason);
            log.info("[TRIGGER] shadow=1 group={} user={} intent={} chance={} roll={} would=reply reason={} | {}",
                    groupId, userId, intent, fmt(chance), fmt(roll), reason, brief(text));
            return would;
        }
        if (!pacer.tryReserve(groupId)) {
            return TriggerDecision.no(intent, chance, roll, "并发占位失败");
        }
        log.info("[TRIGGER] group={} user={} intent={} chance={} roll={} -> reply reason={} | {}",
                groupId, userId, intent, fmt(chance), fmt(roll), reason, brief(text));
        return TriggerDecision.yes(intent, chance, roll, reason);
    }

    /** 关键词命中：大小写不敏感，空白词忽略 */
    private boolean keywordHit(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<String> keywords = policy.keywords();
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (k != null && !k.isBlank() && t.contains(k.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 概率档位与闲聊曲线。
     *
     * <pre>
     *   p = 0          只回呼叫，不进入这里
     *   0 < p < 0.3    关键词、严格疑问
     *   0.3 <= p < 0.6 所有疑问
     *   0.6 <= p < 0.8 疑问 + 低频闲聊 10%~30%
     *   0.8 <= p <= 1  疑问 + 高频闲聊 50%~100%
     * </pre>
     */
    public static double chance(double p, ReplyIntent intent) {
        double safe = clamp(p);
        if (intent == ReplyIntent.CALL || intent == ReplyIntent.KEYWORD
                || intent == ReplyIntent.QUESTION) {
            return safe > 0 ? 1.0 : 0.0;
        }
        if (intent == ReplyIntent.CHAT) {
            if (safe < QUESTION_MAX) {
                return 0.0;
            }
            if (safe < LOW_CHAT_MAX) {
                return 0.10 + (safe - QUESTION_MAX) / (LOW_CHAT_MAX - QUESTION_MAX) * 0.20;
            }
            return 0.50 + (safe - LOW_CHAT_MAX) / (1.0 - LOW_CHAT_MAX) * 0.50;
        }
        return 0.0;
    }

    private static double clamp(double p) {
        return Math.max(0.0, Math.min(1.0, p));
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
