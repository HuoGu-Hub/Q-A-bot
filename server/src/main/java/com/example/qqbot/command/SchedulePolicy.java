package com.example.qqbot.command;

/**
 * 定时输出的配置 —— <b>业务侧自己声明的只读视图</b>，由 {@code config.ScheduleProperties} 实现。
 *
 * <p>与 {@link CommandPolicy} 分开而不是并进去：那一个是"命令怎么工作"，
 * 这一个是"到点发送怎么工作"。两个消费者几乎没有交集，
 * 合在一起会让只关心限流的类被迫依赖九个超时与上限。
 */
public interface SchedulePolicy {

    /** 到点发送总开关 */
    boolean isEnabled();

    /** 错过多久之内还补发；超过就标记过期，不补 */
    int getGraceMinutes();

    /** 发送失败最多重试几次 */
    int getMaxAttempts();

    /** 两次重试之间的间隔 */
    int getRetryDelaySeconds();

    /** 每人最多待发几条 */
    int getMaxPendingPerUser();

    /** 一条内容的长度上限 */
    int getMaxContentChars();

    /** 最远能定到多少天以后 */
    int getMaxFutureDays();

    /** 私聊能不能用 */
    boolean isAllowPrivate();
}
