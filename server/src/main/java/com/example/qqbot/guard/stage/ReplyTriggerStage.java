package com.example.qqbot.guard.stage;

import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.guard.GuardStage;
import com.example.qqbot.trigger.TriggerDecision;
import org.springframework.stereotype.Component;

/**
 * 触发条件：群里要不要回这条消息。
 *
 * <p>呼叫由路由层先认出：@、正文昵称、引用机器人都直接放行；
 * 不是呼叫的消息看 {@link TriggerDecision}，通过才放行。p=0 时非呼叫一律丢弃，
 * 所以「群里必须 @」不再需要第二个开关。
 *
 * <p>判定本身在 {@code MessageRouter} 里算，这里只执行。这样丢弃仍然计入
 * Guard 的入站与拦截指标，不会出现「路由层悄悄丢消息、看板看不到」的缺口。
 */
@Component
public class ReplyTriggerStage implements GuardStage {

    @Override
    public String name() {
        return "trigger";
    }

    @Override
    public GuardResult check(GuardContext ctx) {
        if (!ctx.event().isGroupMessage() || ctx.mentioned()) {
            return GuardResult.pass();
        }
        TriggerDecision trigger = ctx.trigger();
        if (trigger == null) {
            return GuardResult.drop(name(), "没有命中呼叫");
        }
        if (trigger.reply()) {
            return GuardResult.pass();
        }
        if (trigger.wouldReply()) {
            return GuardResult.drop(name(), "影子模式：只记录");
        }
        return GuardResult.drop(name(), "概率回复未命中：" + trigger.reason());
    }
}
