package com.example.qqbot.onebot.outbound;

import com.example.qqbot.guard.Outbound;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 发送线程 —— 整个进程里**只有一个**，按节奏把队列里的消息一条条发出去。
 *
 * <h2>为什么只要一个</h2>
 * 发送的瓶颈是节奏，不是并发。多开几个只会互相抢发车位，等待期间还白占线程。
 * 一个线程在发车位上睡觉不烧 CPU，这正是我们要的。
 *
 * <h2>为什么用裸线程而不是线程池</h2>
 * 这个循环是"取一条、发一条"的长期任务，投进线程池等于永久占用一个工作线程，
 * 还会让池子的统计失真。它就是要一条自己的线程。
 */
@Component
public class SendWorker {

    private static final Logger log = LoggerFactory.getLogger(SendWorker.class);

    private final SendQueue queue;
    private final OutboundSender sender;
    private final Outbound config;

    private Thread thread;

    public SendWorker(SendQueue queue, OutboundSender sender, Outbound config) {
        this.queue = queue;
        this.sender = sender;
        this.config = config;
    }

    @PostConstruct
    void start() {
        if (!config.isAsyncSend()) {
            log.info("[OUT] 发送队列未启用（async-send=false），回复同步发送");
            return;
        }
        thread = new Thread(this::loop, "outbound-sender");
        thread.setDaemon(true);
        thread.start();
        log.info("[OUT] 发送线程已启动，队列容量 {}，账号级间隔 {} 毫秒",
                config.getSendQueueCapacity(), config.getGlobalMinIntervalMillis());
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                PreparedSend send = queue.take();
                sender.deliver(send);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 一条发失败不能拖垮整个发送线程，否则后面全堵
                log.error("[OUT] 发送失败，继续下一条", e);
            }
        }
    }

    @PreDestroy
    void stop() {
        if (thread != null) {
            thread.interrupt();
        }
    }
}
