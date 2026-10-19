package com.example.qqbot.onebot.outbound;

import com.example.qqbot.guard.Outbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 发送队列 —— 处理线程把消息放进来就走，发送线程按节奏一条条取。
 *
 * <h2>为什么这个队列可以"不丢"</h2>
 * 它不会被处理拖慢：进出都很快，只有发车那一步慢，而慢是有节奏、可预期的。
 * 真正丢消息的风险在接收队列那一侧，不在这里。
 *
 * <p>内存队列终究要有个盖子。满了之后<b>只降级实时回复</b>：
 * 定时任务走的是数据库队列，跟这个内存队列不是一回事，不受影响。
 */
@Component
public class SendQueue {

    private static final Logger log = LoggerFactory.getLogger(SendQueue.class);

    private final BlockingQueue<PreparedSend> queue;
    private final AtomicLong dropped = new AtomicLong();

    public SendQueue(Outbound config) {
        int capacity = Math.max(1, config.getSendQueueCapacity());
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * 投一条待发消息。
     *
     * @return false = 队列满了，这条实时回复被降级丢弃
     */
    public boolean offer(PreparedSend send) {
        if (queue.offer(send)) {
            return true;
        }
        long total = dropped.incrementAndGet();
        log.warn("[OUT] 发送队列已满（容量 {}），本条实时回复被丢弃。累计丢弃 {} 条。"
                        + "定时任务不受影响，它们排在数据库里。", queue.size(), total);
        return false;
    }

    /** 发送线程用：取不到就阻塞等着 */
    public PreparedSend take() throws InterruptedException {
        return queue.take();
    }

    /** 当前积压条数，后台与日志都用它 */
    public int backlog() {
        return queue.size();
    }

    public long droppedTotal() {
        return dropped.get();
    }
}
