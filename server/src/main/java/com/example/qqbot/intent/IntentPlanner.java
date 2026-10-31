package com.example.qqbot.intent;

import java.util.EnumSet;
import java.util.Set;

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
 * <p>配置是每轮现读的：后台改了开关，下一条消息就变，不用重启。
 */
@Component
public class IntentPlanner {

    private static final Logger log = LoggerFactory.getLogger(IntentPlanner.class);

    /** 这几类不给模型看资料：资料只会把一句该短的回答撑长 */
    private static final Set<TurnMove> NO_RETRIEVAL =
            EnumSet.of(TurnMove.SOCIAL, TurnMove.META, TurnMove.OUT_OF_SCOPE);

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
            boolean skip = policy.isSkipRetrieval() && NO_RETRIEVAL.contains(move);
            return new TurnPlan(move, !skip, false, skip ? "这类问题不用资料" : "默认");
        } catch (Exception e) {
            log.warn("[INTENT] 判定失败，按默认处理：{}", e.getMessage());
            return TurnPlan.standard();
        }
    }
}
