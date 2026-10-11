package com.example.qqbot.guard.stage;

import com.example.qqbot.guard.Access;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.guard.GuardStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 准入控制：谁有资格触发机器人。
 *
 * <p>包括：用户黑名单、群黑名单、私聊策略。
 * 这是最便宜的一层，跑在最前面。
 *
 * <p><b>判定本身不在这里</b> —— 它在 {@link Access#denial}。因为指令路径
 * （{@code router.MessageRouter}）必须在 Guard 之前做同一件事、得到同一个结论。
 * 这一层只负责把它接进流水线，以及把拒绝原因变成 {@link GuardResult}。
 *
 * <p><b>失败即拒绝（fail-closed）</b>：私聊策略如果写成不认识的值，
 * 一律按「关闭私聊」处理，而不是放行。安全开关不能有「猜错了就放开」的行为。
 */
@Component
public class AccessControlStage implements GuardStage {

    private static final Logger log = LoggerFactory.getLogger(AccessControlStage.class);

    private final Access config;

    public AccessControlStage(Access access) {
        this.config = access;
        if (!access.isRecognizedPrivateChatPolicy()) {
            log.warn("[GUARD] 私聊策略配置值「{}」无法识别，将按【关闭私聊】处理。"
                    + "可选值：all / whitelist / off", access.getPrivateChatPolicy());
        }
    }

    @Override
    public String name() {
        return "access";
    }

    @Override
    public GuardResult check(GuardContext ctx) {
        // 判定在 Access 里 —— 指令路径用的是同一个方法，两处不可能给出不同结论
        return config.denial(ctx.event())
                .map(reason -> GuardResult.drop(name(), reason))
                .orElseGet(GuardResult::pass);
    }
}
