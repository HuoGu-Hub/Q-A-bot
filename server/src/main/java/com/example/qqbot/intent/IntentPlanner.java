package com.example.qqbot.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把关键词判成一份 {@link TurnPlan}。
 *
 * <p>它只回答两个问题：**这次要不要检索**、**这次是不是纠正**。
 * "该怎么写"是 {@code system.md} 的事，不在这里决定 —— 判"是不是建议类"
 * 的误伤率太高，值得为之多一层的是"查不查"，不是"怎么写"。
 *
 * <p>三类跳过检索的意图各有独立开关，互不影响：社交整活、关于机器人自己、
 * 做不到的请求。关掉哪一类，哪一类就照常检索。
 *
 * <p>配置是每轮现读的：后台改了开关，下一条消息就变，不用重启。
 */
@Component
public class IntentPlanner {

    private static final Logger log = LoggerFactory.getLogger(IntentPlanner.class);

    private final IntentPolicy policy;

    public IntentPlanner(IntentPolicy policy) {
        this.policy = policy;
    }

    /**
     * 判一轮。**保证不抛异常**：判不出来就是默认，绝不能因为判定挂掉一条正常提问。
     */
    public TurnPlan plan(String question) {
        try {
            if (policy == null || !policy.isEnabled()) {
                return TurnPlan.standard();
            }
            TurnMove move = IntentSignals.classify(question);
            boolean skip = switch (move) {
                case SOCIAL -> policy.isSkipSocial();
                case META -> policy.isSkipMeta();
                case OUT_OF_SCOPE -> policy.isSkipOutOfScope();
                default -> false;
            };
            // 是不是真的能纠正，还要看有没有上一轮 —— 那件事只有 ChatService 知道
            boolean correcting = policy.isCorrection() && move == TurnMove.CORRECT;

            String reason;
            if (skip) {
                reason = switch (move) {
                    case SOCIAL -> "社交整活不查资料";
                    case META -> "关于自己不查资料";
                    case OUT_OF_SCOPE -> "做不到的请求不查资料";
                    default -> "这类问题不用资料";
                };
            } else if (correcting) {
                reason = "对上一轮的纠正";
            } else {
                reason = "默认";
            }
            return new TurnPlan(move, !skip, correcting, reason);
        } catch (Exception e) {
            log.warn("[INTENT] 判定失败，按默认处理：{}", e.getMessage());
            return TurnPlan.standard();
        }
    }
}
