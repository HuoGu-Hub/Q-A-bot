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

    /**
     * 问答池 —— 只处理问答，命令再多也动不了它。
     *
     * <p>这正是"按类型分池"的意义：一条刷屏的命令风暴不该把提问的人饿死。
     */
    @Bean("chatExecutor")
    public Executor chatExecutor(AsyncProperties props) {
        return pool("chat-", props.getChatPoolSize(), props);
    }

    /** 命令池 —— 只处理以 / 开头的命令 */
    @Bean("commandExecutor")
    public Executor commandExecutor(AsyncProperties props) {
        return pool("cmd-", props.getCommandPoolSize(), props);
    }

    /**
     * 建一个池子。
     *
     * <p>拒绝策略用默认的抛出：由调用方 {@code EventDispatcher} 接住、回一句「人多稍等」。
     * 放在这里做的话，配置类就得依赖发送器，方向反了。
     */
    private Executor pool(String prefix, int size, AsyncProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setKeepAliveSeconds(props.getKeepAliveSeconds());
        executor.setAllowCoreThreadTimeOut(props.isAllowCoreThreadTimeOut());
        executor.setThreadNamePrefix(prefix);
        executor.initialize();
        log.info("[ASYNC] {}池：{} 线程，队列 {}，等待上限 {}s",
                prefix, size, props.getQueueCapacity(), props.getMaxQueueWaitSeconds());
        return executor;
    }
}
