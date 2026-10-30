package com.example.qqbot.onebot.outbound;

import com.example.qqbot.guard.Outbound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 发送队列与发送线程。
 *
 * <p>这里验的是"处理线程不被发车位堵住"这件事本身：投进去就返回，
 * 由另一条线程慢慢发。
 */
class SendQueueTest {

    private static Outbound config(int capacity) {
        Outbound out = new Outbound();
        out.setSendQueueCapacity(capacity);
        out.setAsyncSend(true);
        out.setGlobalMinIntervalMillis(0);
        out.setGroupMinIntervalMillis(0);
        return out;
    }

    private static PreparedSend item(String text) {
        return new PreparedSend(true, 100L, 0L, text, List.of(text), List.of());
    }

    @Test
    @DisplayName("★ 队列满了返回 false，由调用方同步发送，不抛异常")
    void fullQueueOverflows() {
        SendQueue queue = new SendQueue(config(2));

        assertThat(queue.offer(item("第一条"))).isTrue();
        assertThat(queue.offer(item("第二条"))).isTrue();
        assertThat(queue.offer(item("第三条"))).as("超容量").isFalse();
        assertThat(queue.backlog()).isEqualTo(2);
        assertThat(queue.overflowTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("积压条数能被读到 —— 后台靠它看水位")
    void backlogIsVisible() {
        SendQueue queue = new SendQueue(config(10));
        queue.offer(item("甲"));
        queue.offer(item("乙"));

        assertThat(queue.backlog()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 发送线程把队列里的条目逐条交给发送器")
    void workerDrainsTheQueue() throws Exception {
        SendQueue queue = new SendQueue(config(10));
        OutboundSender sender = mock(OutboundSender.class);
        SendWorker worker = new SendWorker(queue, sender, config(10));

        worker.start();
        try {
            queue.offer(item("甲"));
            queue.offer(item("乙"));

            verify(sender, timeout(Duration.ofSeconds(3).toMillis())).deliver(item("甲"));
            verify(sender, timeout(Duration.ofSeconds(3).toMillis())).deliver(item("乙"));
        } finally {
            worker.stop();
        }
    }

    @Test
    @DisplayName("一条发失败不会拖垮发送线程，后面的照发")
    void failureDoesNotKillTheWorker() throws Exception {
        SendQueue queue = new SendQueue(config(10));
        OutboundSender sender = mock(OutboundSender.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("协议端抽风"))
                .doNothing()
                .when(sender).deliver(item("坏的那条"));

        SendWorker worker = new SendWorker(queue, sender, config(10));
        worker.start();
        try {
            queue.offer(item("坏的那条"));
            queue.offer(item("好的那条"));

            verify(sender, timeout(Duration.ofSeconds(3).toMillis())).deliver(item("好的那条"));
        } finally {
            worker.stop();
        }
    }

    @Test
    @DisplayName("异步关着时发送线程不启动")
    void workerStaysOffWhenAsyncDisabled() {
        Outbound off = config(10);
        off.setAsyncSend(false);
        SendQueue queue = new SendQueue(off);
        OutboundSender sender = mock(OutboundSender.class);

        new SendWorker(queue, sender, off).start();

        queue.offer(item("没人取"));
        assertThat(queue.backlog()).isEqualTo(1);
    }
}
