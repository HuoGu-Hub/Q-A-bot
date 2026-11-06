package com.example.qqbot.guard.stage;

import com.example.qqbot.guard.Access;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.guard.GuardStage;
import com.example.qqbot.trigger.TriggerDecision;
import org.springframework.stereotype.Component;

/**
 * 触发条件：群里要不要回这条消息。
 *
 * <p>它替代原来的 {@code MentionRequiredStage}，把「必须 @」和「概率回复」
 * 收成同一条判定链：
 * <ul>
 *   <li>「必须 @」打开：没 @ 直接丢弃，与接入概率回复之前完全一致；</li>
 *   <li>「必须 @」关闭：@、正文昵称、引用机器人由路由层判定为呼叫，直接放行；
 *       其余消息看 {@link TriggerDecision}，通过才放行。</li>
 * </ul>
 *
 * <p><b>为什么不直接用 {@link Access#mentionSatisfied}</b>：概率模式下这个开关
 * 恒为 true，用它就永远不会丢弃。这里必须读原始开关，再叠加判定结果。
 *
 * <p>判定本身在 {@code MessageRouter} 里算，这里只执行。这样丢弃仍然计入
 * Guard 的入站与拦截指标，不会出现「路由层悄悄丢消息、看板看不到」的缺口。
 */
@Component
public class ReplyTriggerStage implements GuardStage {

    private final Access config;

    public ReplyTriggerStage(Access config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "trigger";
    }

    @Override
    public GuardResult check(GuardContext ctx) {
        if (!ctx.event().isGroupMessage()) {
            return GuardResult.pass();
        }
        if (config.isRequireMentionInGroup()) {
            return ctx.mentioned() ? GuardResult.pass() : GuardResult.drop(name(), "群里没有 @ 机器人");
        }
        if (ctx.mentioned()) {
            return GuardResult.pass();
        }
        TriggerDecision trigger = ctx.trigger();
        if (trigger != null && trigger.reply()) {
            return GuardResult.pass();
        }
        if (trigger != null && trigger.wouldReply()) {
            return GuardResult.drop(name(), "影子模式：只记录");
        }
        return GuardResult.drop(name(),
                trigger == null ? "没有命中呼叫" : "概率回复未命中：" + trigger.reason());
    }
}
