package com.example.qqbot.guard;

import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.guard.stage.AccessControlStage;
import com.example.qqbot.guard.stage.ContentGateStage;
import com.example.qqbot.guard.stage.InboundWordStage;
import com.example.qqbot.guard.stage.MentionRequiredStage;
import com.example.qqbot.guard.stage.RateLimitStage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 安全中间层的编排器：按顺序跑完所有 stage，任意一个返回非 Pass 就立即中断。
 *
 * <p>顺序是精心安排的，从最便宜到最贵：
 * <pre>
 *   1. access        谁有资格（纯内存判断，零成本）
 *   2. mention       有没有 @ 我（零成本，挡掉群里绝大部分消息）
 *   3. rate-limit    频率够不够（零成本，挡掉刷屏）
 *   4. content-gate  有没有文字内容（零成本，纯表情直接回兜底词）
 *   5. inbound-words 有没有敏感词（零成本，命中直接拒绝）
 *   --------------- 到这里都还没花一分钱 ---------------
 *   6. 调用大模型
 * </pre>
 *
 * <p>注意第 5 步在调模型之前：这样攻击者没法用敏感内容或注入话术烧你的 token。
 */
@Component
public class GuardPipeline {

    private static final Logger log = LoggerFactory.getLogger(GuardPipeline.class);

    private final GuardProperties properties;
    private final List<GuardStage> stages;
    private final GuardMetrics metrics;

    public GuardPipeline(GuardProperties properties,
                         GuardMetrics metrics,
                         AccessControlStage accessControl,
                         MentionRequiredStage mentionRequired,
                         RateLimitStage rateLimit,
                         ContentGateStage contentGate,
                         InboundWordStage inboundWords) {
        this.properties = properties;
        this.metrics = metrics;
        // 顺序就是执行顺序，改这里就能调整优先级
        this.stages = List.of(
                accessControl,
                mentionRequired,
                rateLimit,
                contentGate,
                inboundWords);
    }

    /** 启动时把生效的规则打出来，方便一眼确认配置对不对 */
    @PostConstruct
    void logEffectiveRules() {
        if (!properties.isEnabled()) {
            log.warn("[GUARD] 安全中间层已【关闭】（app.guard.enabled=false），所有限制都不生效！");
            return;
        }
        GuardProperties.Access access = properties.getAccess();
        GuardProperties.RateLimit rate = properties.getRateLimit();
        log.info("[GUARD] 安全中间层已启用");
        log.info("[GUARD]   顺序        ：access -> mention -> rate-limit -> content-gate -> inbound-words");
        log.info("[GUARD]   群聊必须 @  ：{}", access.isRequireMentionInGroup());
        log.info("[GUARD]   私聊策略    ：{}", access.getPrivateChatPolicy());
        log.info("[GUARD]   限流        ：每群 {} 条/分钟，每人在群内 {} 次/{} 秒，超限处理={}",
                rate.getPerGroupPerMinute(), rate.getPerUserPerMinute(),
                rate.getPerUserWindowSeconds(), rate.getOnLimit());
        log.info("[GUARD]   限流提示    ：每用户冷却 {} 秒，每群每分钟最多 {} 条提示",
                rate.getNotifyCooldownSeconds(), rate.getNotifyGroupPerMinute());
        if (rate.getNotifyCooldownSeconds() > rate.getPerUserWindowSeconds()) {
            log.warn("[GUARD]   ⚠️ 提示冷却({}秒) 比 限流窗口({}秒) 还长：被限流的人可能【一次提示都收不到】。"
                            + "建议把 notify-cooldown-seconds 调到 ≤ {}",
                    rate.getNotifyCooldownSeconds(), rate.getPerUserWindowSeconds(),
                    rate.getPerUserWindowSeconds());
        }
        log.info("[GUARD]   无文字消息  ：回兜底话术「{}」", properties.getContentGate().getNoTextReply());
        log.info("[GUARD]   应急开关    ：{}", properties.isKillSwitch() ? "【已打开，机器人不回复】" : "正常");
    }

    /**
     * 入站判定。
     *
     * @return Pass 表示放行去调模型；Drop 表示静默丢弃；Reply 表示回固定话术（不调模型）
     */
    public GuardResult check(GuardContext ctx) {
        metrics.inbound();
        // 应急开关优先级最高
        if (properties.isKillSwitch()) {
            log.warn("[GUARD] 全局应急开关已打开，丢弃所有消息");
            metrics.blocked("kill-switch", GuardResult.drop("kill-switch", "全局应急开关已打开"), ctx);
            return GuardResult.drop("kill-switch", "全局应急开关已打开");
        }
        if (!properties.isEnabled()) {
            metrics.passed();
            return GuardResult.pass();
        }

        for (GuardStage stage : stages) {
            GuardResult result = stage.check(ctx);
            if (result instanceof GuardResult.Pass) {
                continue;
            }
            log.info("[GUARD] 拦截 stage={} reason={} group={} user={}",
                    stage.name(), describe(result), ctx.groupId(), ctx.userId());
            metrics.blocked(stage.name(), result, ctx);
            return result;
        }
        metrics.passed();
        return GuardResult.pass();
    }

    private String describe(GuardResult result) {
        if (result instanceof GuardResult.Drop drop) {
            return drop.reason();
        }
        if (result instanceof GuardResult.Reply reply) {
            return reply.reason();
        }
        return "pass";
    }
}
