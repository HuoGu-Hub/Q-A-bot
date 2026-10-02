package com.example.qqbot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件处理线程池。
 *
 * <p>为什么要异步：NapCat 把事件 POST 给我们之后会等响应，
 * 所以接收事件必须「立刻返回 204，再慢慢处理」。
 *
 * <p><b>两个容易踩的坑，这里都处理了：</b>
 * <ol>
 *   <li><b>扩容陷阱</b>：Java 线程池是「core 满了先入队，队列满了才扩容到 max」。
 *       队列一大，max 就永远不生效。所以这里 core == max。</li>
 *   <li><b>拒绝策略</b>：默认的 `CallerRunsPolicy` 会在**调用线程**（也就是 Tomcat 的
 *       HTTP 线程）上执行任务，导致 204 响应被拖到模型调用结束才返回 →
 *       NapCat 上报堆积 → 雪崩。所以这里改成**丢弃 + 记录**：
 *       宁可少回一条消息，也绝不能阻塞接收通道。</li>
 * </ol>
 */
@Configuration
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean("botExecutor")
    public Executor botExecutor(AsyncProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getCorePoolSize());
        executor.setMaxPoolSize(props.getMaxPoolSize());
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setKeepAliveSeconds(props.getKeepAliveSeconds());
        executor.setAllowCoreThreadTimeOut(props.isAllowCoreThreadTimeOut());
        executor.setThreadNamePrefix("bot-");

        AtomicLong rejected = new AtomicLong();
        executor.setRejectedExecutionHandler((task, pool) -> {
            long total = rejected.incrementAndGet();
            log.error("[ASYNC] 队列已满（容量 {}），任务被丢弃。累计丢弃 {} 条。"
                            + "说明负载已超过处理能力，考虑调大 app.async.core-pool-size",
                    props.getQueueCapacity(), total);
        });

        executor.initialize();
        log.info("[ASYNC] 事件线程池：core={} max={} queue={} 空闲回收={} 队列等待上限={}s",
                props.getCorePoolSize(), props.getMaxPoolSize(), props.getQueueCapacity(),
                props.isAllowCoreThreadTimeOut(), props.getMaxQueueWaitSeconds());
        return executor;
    }
}
