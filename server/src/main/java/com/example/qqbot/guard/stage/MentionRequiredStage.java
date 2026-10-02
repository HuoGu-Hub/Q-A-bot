package com.example.qqbot.guard.stage;

import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.guard.GuardStage;
import com.example.qqbot.onebot.BotIdentity;
import com.example.qqbot.onebot.codec.MessageCodec;
import org.springframework.stereotype.Component;

/**
 * 触发条件：群里必须 @ 机器人。
 *
 * <p>这是一个**防炸群的关键设计**：不 @ 就完全不响应，
 * 意味着群里日常聊天的内容永远进不了大模型，既省钱也不烦人。
 *
 * <p>例外：私聊不需要 @（但私聊默认是关闭的，见 {@link AccessControlStage}）。
 * 将来的「定时消息」功能是主动推送，不经过这条流水线，不受此限制。
 */
@Component
public class MentionRequiredStage implements GuardStage {

    private final GuardProperties.Access config;
    private final BotIdentity identity;
    private final MessageCodec codec;

    public MentionRequiredStage(GuardProperties properties, BotIdentity identity, MessageCodec codec) {
        this.config = properties.getAccess();
        this.identity = identity;
        this.codec = codec;
    }

    @Override
    public String name() {
        return "mention";
    }

    @Override
    public GuardResult check(GuardContext ctx) {
        if (!ctx.event().isGroupMessage()) {
            return GuardResult.pass();
        }
        if (!config.isRequireMentionInGroup()) {
            return GuardResult.pass();
        }
        long selfId = identity.getSelfId();
        if (codec.isMentioned(ctx.event(), selfId)) {
            return GuardResult.pass();
        }
        return GuardResult.drop(name(), "群里没有 @ 机器人");
    }
}
