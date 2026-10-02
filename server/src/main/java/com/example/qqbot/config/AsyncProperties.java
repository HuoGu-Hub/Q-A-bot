package com.example.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 事件处理线程池的配置，对应 application.yml 里的 app.async.*
 *
 * <p>为什么这些参数要可配置：并发能力直接决定「50 个人同时 @ 时多久能收到回复」，
 * 而不同机器、不同模型需要的值不一样，硬编码在代码里改一次就要重新编译。
 */
@ConfigurationProperties(prefix = "app.async")
public class AsyncProperties {

    /**
     * 核心线程数。
     *
     * <p><b>注意：这个值等于 max-pool-size，是有意为之。</b>
     * Java 线程池的规则是「核心线程满了 → 先入队 → 队列满了 → 才扩容到 max」，
     * 所以如果队列很大而 max > core，max 永远不会生效（这是最经典的坑）。
     */
    private int corePoolSize = 8;

    /** 最大线程数。保持和 core-pool-size 相同，避开上面说的扩容陷阱 */
    private int maxPoolSize = 8;

    /** 等待队列容量。队列满了之后新任务会被直接丢弃（不是阻塞调用者） */
    private int queueCapacity = 100;

    /** 空闲线程的存活时间（秒） */
    private int keepAliveSeconds = 60;

    /**
     * 是否允许核心线程超时退出。
     * 打开后，空闲时线程会自己销毁，闲置成本归零。
     */
    private boolean allowCoreThreadTimeOut = true;

    /**
     * 任务在队列里等待超过这个秒数，就直接丢弃。
     *
     * <p>为什么需要它：模型一次要几秒，如果前面排了 20 个人，
     * 轮到你时可能已经过了 30 秒 —— 那时候再回复「你好」已经毫无意义，
     * 还会让人觉得机器人卡了。宁可说一句「刚才太挤了，你再说一遍」。
     */
    private int maxQueueWaitSeconds = 20;

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(int keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public boolean isAllowCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    public void setAllowCoreThreadTimeOut(boolean allowCoreThreadTimeOut) {
        this.allowCoreThreadTimeOut = allowCoreThreadTimeOut;
    }

    public int getMaxQueueWaitSeconds() {
        return maxQueueWaitSeconds;
    }

    public void setMaxQueueWaitSeconds(int maxQueueWaitSeconds) {
        this.maxQueueWaitSeconds = maxQueueWaitSeconds;
    }
}
