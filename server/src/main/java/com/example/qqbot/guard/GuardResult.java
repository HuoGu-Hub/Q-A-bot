package com.example.qqbot.guard;

/**
 * 安全中间层的判定结果。
 *
 * <p>用 sealed interface：新增一种结果时，所有 switch 会编译报错，
 * 强制你去处理，不会漏掉分支。
 */
public sealed interface GuardResult {

    /** 放行，继续走后面的流程 */
    record Pass() implements GuardResult {
    }

    /** 静默丢弃：什么都不回（黑名单、被限流、没 @ 机器人…） */
    record Drop(String stage, String reason) implements GuardResult {
    }

    /** 回复固定话术，**不调用大模型**（兜底词、敏感词拒绝） */
    record Reply(String stage, String reason, String text) implements GuardResult {
    }

    static GuardResult pass() {
        return new Pass();
    }

    static GuardResult drop(String stage, String reason) {
        return new Drop(stage, reason);
    }

    static GuardResult reply(String stage, String reason, String text) {
        return new Reply(stage, reason, text);
    }
}
