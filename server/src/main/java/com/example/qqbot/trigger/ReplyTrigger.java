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
 * <p>顺序固定：群白名单 → 概率下限 → 免@阈值 → 关键词 → 概率档位
 * → 游戏相关 / 严格度 / 疑问强度 → 闲聊抽样 → 每日上限 → 影子 / 占位。
 * 任何一步不通过都返回 {@code reply=false}，由 Guard 的 trigger 阶段静默丢弃。
 * **保证不抛异常**：判定挂掉时按噪音处理，绝不能因为判定影响正常提问。
 */
@Component
public class ReplyTrigger {

    private static final Logger log = LoggerFactory.getLogger(ReplyTrigger.class);

    /** 低于这个值不参与主动回复，只回呼叫 */
    private static final double MIN_ACTIVE = 0.10;

    /** 0.1 <= p <= 0.3：严格疑问 + 游戏相关 */
    private static final double GAME_MAX = 0.30;

    /** 0.3 < p <= 0.6：严格疑问，不要求游戏相关 */
    private static final double STRICT_MAX = 0.60;

    /** 0.6 < p <= 0.8：松判定疑问 */
    private static final double LOOSE_MAX = 0.80;

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
        log.info("[TRIGGER] 概率回复：p={} mentionFree={} groups={} dailyMax={} shadow={} keywords={} gameWords={}/{}/{}",
                policy.probability(), policy.mentionFreeThreshold(), policy.enabledGroups(),
                policy.dailyMaxPerGroup(), policy.shadowMode(), policy.keywords(),
                policy.gameStrongKeywords().size(), policy.gameGenericKeywords().size(),
                policy.gameWeakKeywords().size());
    }

    public TriggerDecision decide(long groupId, long userId, String text,
                                  boolean hasImage, boolean atOther) {
        try {
            if (!policy.enabledForGroup(groupId)) {
                return TriggerDecision.no(ReplyIntent.NOISE, 0.0, 0.0,
                        "群未启用", "群不在白名单");
            }
            double p = clamp(policy.probability());
            if (p <= 0) {
                return TriggerDecision.no(ReplyIntent.NOISE, 0.0, 0.0,
                        "概率为 0", "主动回复概率为 0");
            }
            if (p < MIN_ACTIVE) {
                return TriggerDecision.no(ReplyIntent.NOISE, 0.0, 0.0,
                        "概率过低", "p=" + fmt(p) + " < " + fmt(MIN_ACTIVE));
            }

            ReplyIntent intent = ReplySignals.classify(text, hasImage, atOther);

            // 主动回复概率没到阈值时，只有 @ / 昵称 / 引用这类呼叫能触发
            double threshold = clamp(policy.mentionFreeThreshold());
            if (p < threshold) {
                return TriggerDecision.no(intent, 0.0, 0.0, "低于免@阈值",
                        "p=" + fmt(p) + " < 免@阈值 " + fmt(threshold));
            }

            // 关键词是运营显式指定的必答词，不受档位形态限制
            String keyword = matchedKeyword(text);
            if (keyword != null) {
                return accept(groupId, userId, text, ReplyIntent.KEYWORD, 1.0, 0.0,
                        "关键词", "关键词「" + keyword + "」");
            }

            if (p <= GAME_MAX) {
                return gameTier(groupId, userId, text, intent, p);
            }
            if (p <= STRICT_MAX) {
                return strictTier(groupId, userId, text, intent, p);
            }
            if (p <= LOOSE_MAX) {
                return looseTier(groupId, userId, text, intent, p);
            }
            return chatTier(groupId, userId, text, intent, p);
        } catch (Exception e) {
            log.warn("[TRIGGER] 判定失败，按噪音处理：{}", e.getMessage());
            return TriggerDecision.no(ReplyIntent.NOISE, 0.0, 0.0, "判定失败", "判定异常");
        }
    }

    /** 0.1-0.3：严格疑问，且必须能看出与游戏相关 */
    private TriggerDecision gameTier(long groupId, long userId, String text,
                                     ReplyIntent intent, double p) {
        if (intent != ReplyIntent.QUESTION) {
            return TriggerDecision.no(intent, 0.0, 0.0,
                    "低概率只回游戏相关严格疑问", "不是疑问");
        }
        String strict = ReplySignals.strictQuestionEvidence(text);
        if (strict == null) {
            return TriggerDecision.no(intent, 0.0, 0.0,
                    "低概率只回游戏相关严格疑问", "不是严格疑问");
        }
        double t = (p - MIN_ACTIVE) / (GAME_MAX - MIN_ACTIVE);
        double required = 3.5 - 2.5 * Math.max(0.0, Math.min(1.0, t));
        ReplySignals.GameMatch game = ReplySignals.gameRelevance(text,
                policy.gameStrongKeywords(), policy.gameGenericKeywords(), policy.gameWeakKeywords());
        if (game.score() < required) {
            return TriggerDecision.no(intent, 0.0, 0.0, "游戏相关度不足",
                    "游戏相关度 " + game.score() + " < " + fmt(required));
        }
        return accept(groupId, userId, text, intent, 1.0, 0.0,
                "游戏相关严格疑问",
                "严格疑问：" + strict + " · 游戏词「" + game.term() + "」");
    }

    /** 0.3-0.6：严格疑问；0.3-0.4 要求句首/问号，0.5 起放宽到句末语气词 */
    private TriggerDecision strictTier(long groupId, long userId, String text,
                                       ReplyIntent intent, double p) {
        if (intent != ReplyIntent.QUESTION) {
            return TriggerDecision.no(intent, 0.0, 0.0,
                    "该档只回严格疑问", "不是疑问");
        }
        int strength = ReplySignals.strictQuestionStrength(text);
        if (strength == 0) {
            return TriggerDecision.no(intent, 0.0, 0.0,
                    "该档只回严格疑问", "不是严格疑问");
        }
        double t = (p - GAME_MAX) / (STRICT_MAX - GAME_MAX);
        double required = 3.0 - 1.5 * Math.max(0.0, Math.min(1.0, t));
        if (strength < required) {
            return TriggerDecision.no(intent, 0.0, 0.0, "严格度不足",
                    "严格度 " + strength + " < " + fmt(required));
        }
        return accept(groupId, userId, text, intent, 1.0, 0.0,
                "严格疑问", "严格疑问：" + ReplySignals.strictQuestionEvidence(text));
    }

    /** 0.6-0.8：松判定疑问；低端只认高置信疑问词，高端才认「什么/啥」这类泛词 */
    private TriggerDecision looseTier(long groupId, long userId, String text,
                                      ReplyIntent intent, double p) {
        if (intent != ReplyIntent.QUESTION) {
            return TriggerDecision.no(intent, 0.0, 0.0,
                    "该档只回疑问", "不是疑问");
        }
        int strength = ReplySignals.looseQuestionStrength(text);
        if (strength == 0) {
            return TriggerDecision.no(intent, 0.0, 0.0,
                    "该档只回疑问", "不是疑问");
        }
        double t = (p - STRICT_MAX) / (LOOSE_MAX - STRICT_MAX);
        double required = 2.0 - Math.max(0.0, Math.min(1.0, t));
        if (strength < required) {
            return TriggerDecision.no(intent, 0.0, 0.0, "疑问强度不足",
                    "疑问强度 " + strength + " < " + fmt(required));
        }
        String strict = ReplySignals.strictQuestionEvidence(text);
        String evidence = strict != null
                ? "严格疑问：" + strict
                : "疑问：" + ReplySignals.questionEvidence(text);
        return accept(groupId, userId, text, intent, 1.0, 0.0, "松判定疑问", evidence);
    }

    /** 0.8-1：疑问仍必答；只有闲聊按概率抽样 */
    private TriggerDecision chatTier(long groupId, long userId, String text,
                                     ReplyIntent intent, double p) {
        if (intent == ReplyIntent.QUESTION) {
            String strict = ReplySignals.strictQuestionEvidence(text);
            String evidence = strict != null
                    ? "严格疑问：" + strict
                    : "疑问：" + ReplySignals.questionEvidence(text);
            return accept(groupId, userId, text, intent, 1.0, 0.0, "疑问", evidence);
        }
        if (intent == ReplyIntent.NOISE) {
            return TriggerDecision.no(intent, 0.0, 0.0, "噪音", "没有可接的文字");
        }

        double chance = chance(p, ReplyIntent.CHAT);
        String tier = "高频率闲聊";
        if (!pacer.allows(groupId)) {
            return TriggerDecision.no(intent, chance, 0.0, "今日额度用完", tier);
        }
        double roll = random.getAsDouble();
        if (roll >= chance) {
            log.debug("[TRIGGER] 未命中 group={} user={} intent={} chance={} roll={} | {}",
                    groupId, userId, intent, fmt(chance), fmt(roll), brief(text));
            return TriggerDecision.no(intent, chance, roll, "未命中", tier);
        }
        return accept(groupId, userId, text, intent, chance, roll, "闲聊命中", tier);
    }

    /** 判定通过后的公共收口：额度、影子、并发占位、日志 */
    private TriggerDecision accept(long groupId, long userId, String text, ReplyIntent intent,
                                   double chance, double roll, String reason, String evidence) {
        if (!pacer.allows(groupId)) {
            return TriggerDecision.no(intent, chance, roll, "今日额度用完", evidence);
        }
        if (policy.shadowMode()) {
            TriggerDecision would = TriggerDecision.would(intent, chance, roll,
                    "影子模式：" + reason, evidence);
            log.info("[TRIGGER] shadow=1 group={} user={} intent={} chance={} roll={} would=reply reason={} evidence={} | {}",
                    groupId, userId, intent, fmt(chance), fmt(roll), reason, evidence, brief(text));
            return would;
        }
        if (!pacer.tryReserve(groupId)) {
            return TriggerDecision.no(intent, chance, roll, "并发占位失败", evidence);
        }
        log.info("[TRIGGER] group={} user={} intent={} chance={} roll={} -> reply reason={} evidence={} | {}",
                groupId, userId, intent, fmt(chance), fmt(roll), reason, evidence, brief(text));
        return TriggerDecision.yes(intent, chance, roll, reason, evidence);
    }

    /** 关键词命中：大小写不敏感，空白词忽略；返回命中的词 */
    private String matchedKeyword(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<String> keywords = policy.keywords();
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (k != null && !k.isBlank() && t.contains(k.trim().toLowerCase(Locale.ROOT))) {
                return k.trim();
            }
        }
        return null;
    }

    /**
     * 概率档位与闲聊曲线。
     *
     * <pre>
     *   p = 0            只回呼叫，不进入这里
     *   0 < p < 0.1      只回呼叫
     *   0.1 <= p <= 0.3  严格疑问 + 游戏相关
     *   0.3 < p <= 0.6   严格疑问
     *   0.6 < p <= 0.8   松判定疑问
     *   0.8 < p <= 1     疑问 + 闲聊 5%~100%，二次曲线
     * </pre>
     */
    public static double chance(double p, ReplyIntent intent) {
        double safe = clamp(p);
        if (intent == ReplyIntent.CALL || intent == ReplyIntent.KEYWORD
                || intent == ReplyIntent.QUESTION) {
            return safe > 0 ? 1.0 : 0.0;
        }
        if (intent == ReplyIntent.CHAT) {
            if (safe <= LOOSE_MAX) {
                return 0.0;
            }
            double t = (safe - LOOSE_MAX) / (1.0 - LOOSE_MAX);
            // 二次曲线：0.8 只轻轻接话，接近 1 才完全放开
            return 0.05 + 0.95 * t * t;
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
