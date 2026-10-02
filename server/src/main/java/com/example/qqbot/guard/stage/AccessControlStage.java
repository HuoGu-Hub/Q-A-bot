package com.example.qqbot.guard.stage;

import com.example.qqbot.guard.Access;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.guard.GuardStage;
import com.example.qqbot.onebot.model.OneBotEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 准入控制：谁有资格触发机器人。
 *
 * <p>包括：用户黑名单、群黑名单、私聊策略。
 * 这是最便宜的一层，跑在最前面。
 *
 * <p><b>失败即拒绝（fail-closed）</b>：私聊策略如果写成不认识的值，
 * 一律按「关闭私聊」处理，而不是放行。安全开关不能有「猜错了就放开」的行为。
 */
@Component
public class AccessControlStage implements GuardStage {

    private static final Logger log = LoggerFactory.getLogger(AccessControlStage.class);

    /** 允许私聊的值（其余一律视为关闭） */
    private static final Set<String> PRIVATE_ALLOW_ALL = Set.of("all", "on", "true", "yes", "open");

    /** 白名单模式的值 */
    private static final Set<String> PRIVATE_WHITELIST = Set.of("whitelist", "white", "list");

    /** 明确表示关闭的值。注意 "false" 是必须的：YAML 里写 off 会被解析成布尔 false */
    private static final Set<String> PRIVATE_OFF = Set.of("off", "false", "no", "none", "disabled", "close", "");

    private final Access config;

    public AccessControlStage(Access access) {
        this.config = access;
        String policy = normalize(config.getPrivateChatPolicy());
        if (!PRIVATE_ALLOW_ALL.contains(policy)
                && !PRIVATE_WHITELIST.contains(policy)
                && !PRIVATE_OFF.contains(policy)) {
            log.warn("[GUARD] 私聊策略配置值「{}」无法识别，将按【关闭私聊】处理。"
                    + "可选值：all / whitelist / off", config.getPrivateChatPolicy());
        }
    }

    @Override
    public String name() {
        return "access";
    }

    @Override
    public GuardResult check(GuardContext ctx) {
        OneBotEvent event = ctx.event();

        // 用户黑名单：无论群聊私聊都不理
        if (event.getUserId() != null && config.getUserBlacklist().contains(event.getUserId())) {
            return GuardResult.drop(name(), "用户在黑名单里");
        }

        if (event.isGroupMessage()) {
            if (event.getGroupId() != null && config.getGroupBlacklist().contains(event.getGroupId())) {
                return GuardResult.drop(name(), "群在黑名单里");
            }
            return GuardResult.pass();
        }

        if (event.isPrivateMessage()) {
            String policy = normalize(config.getPrivateChatPolicy());
            if (PRIVATE_ALLOW_ALL.contains(policy)) {
                return GuardResult.pass();
            }
            if (PRIVATE_WHITELIST.contains(policy)
                    && config.getPrivateWhitelist().contains(event.getUserId())) {
                return GuardResult.pass();
            }
            return GuardResult.drop(name(), "私聊未开放（策略=" + policy + "）");
        }

        return GuardResult.drop(name(), "未知的消息类型");
    }

    /** 统一小写、去空格；null 视为空串 */
    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
