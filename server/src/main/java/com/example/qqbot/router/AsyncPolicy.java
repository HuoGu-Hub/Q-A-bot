package com.example.qqbot.router;

/**
 * 事件处理线程池里**业务侧真正需要的那一项** —— 由 {@code config.AsyncProperties} 实现。
 *
 * <h2>⚠️ 这个接口是「窄」的，而且是有意的</h2>
 * 和 {@code CommandPolicy} / {@code KbPolicy} 那种"照抄整个配置类的公开面"不同，
 * 这里只声明 **1 个方法**。原因是：{@code AsyncProperties} 有 6 个字段，
 * 而其中 <b>5 个只被装配层的 {@code config.AsyncConfig} 用来构造线程池</b>，
 * 只有 {@code max-queue-wait-seconds} 被业务代码读过（{@code MessageRouter} 等队列时）。
 *
 * <p>这正是本模式想表达的语义：<b>接口声明的是「业务需要什么」，不是「配置类有什么」</b>。
 * 装配层当然可以拿到全部 6 项（它就在 {@code config} 里），业务侧只该看到它用得上的那一项。
 *
 * <p>⚠️ 接口而不是 record：配置是热生效的（{@code SettingsService} 就地改字段、
 * 各 Bean 长期持引用实时读），写成快照就再也看不到后台的修改，而且不报错。
 *
 * <p>完整理由与粒度说明见 {@code command.CommandPolicy}（债务⑥ 的模板样例）。
 */
public interface AsyncPolicy {

    /**
     * 排队等线程超过这么多秒就放弃（回一句"太忙了"），而不是无限期挂着。
     *
     * <p>为什么需要：QQ 的事件回调有超时，等太久不如早点告诉用户"现在人多"。
     */
    int getMaxQueueWaitSeconds();
}
