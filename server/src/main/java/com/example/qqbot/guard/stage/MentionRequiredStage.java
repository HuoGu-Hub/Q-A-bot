package com.example.qqbot.guard.stage;

import com.example.qqbot.guard.Access;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.guard.GuardStage;
import org.springframework.stereotype.Component;

/**
 * 触发条件：群里必须 @ 机器人。
 *
 * <p>这是一个**防炸群的关键设计**：不 @ 就完全不响应，
 * 意味着群里日常聊天的内容永远进不了大模型，既省钱也不烦人。
 *
 * <p>例外：私聊不需要 @（但私聊默认是关闭的，见 {@link AccessControlStage}）。
 * 将来的「定时消息」功能是主动推送，不经过这条流水线，不受此限制。
 *
 * <p>「有没有 @」由 {@link GuardContext#mentioned()} 带进来 —— 判定只在
 * MessageRouter 里做一次。以前这里自己再查一遍消息段，于是手打「@昵称」
 * 在指令侧能过、在 Guard 侧被拦，同一条消息两套结论。
 */
@Component
public class MentionRequiredStage implements GuardStage {

    private final Access config;

    public MentionRequiredStage(Access access) {
        this.config = access;
    }

    @Override
    public String name() {
        return "mention";
    }

    @Override
    public GuardResult check(GuardContext ctx) {
        // 判法与指令那一侧共用一处实现，见 Access.mentionSatisfied
        if (config.mentionSatisfied(ctx.event().isGroupMessage(), ctx.mentioned())) {
            return GuardResult.pass();
        }
        return GuardResult.drop(name(), "群里没有 @ 机器人");
    }
}
