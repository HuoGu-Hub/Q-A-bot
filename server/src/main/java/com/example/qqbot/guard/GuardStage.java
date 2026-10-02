package com.example.qqbot.guard;

/**
 * 安全中间层的一环。
 *
 * <p>实现类必须是**无状态**的（线程安全），因为事件是并发处理的。
 */
public interface GuardStage {

    /** 用于日志和排查的阶段名 */
    String name();

    /** 返回非 Pass 表示拦截 */
    GuardResult check(GuardContext ctx);
}
