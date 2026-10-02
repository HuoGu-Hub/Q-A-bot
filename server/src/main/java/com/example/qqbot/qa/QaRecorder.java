package com.example.qqbot.qa;

import com.example.qqbot.config.QaProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 问答记录的异步写入器。
 *
 * <p><b>本类的唯一职责：让"记录"这件事永远不拖慢"回答"。</b>
 *
 * <pre>
 *   回答线程 ──offer()──▶ 有界队列 ──▶ 单线程 worker ──▶ SQLite（批量事务）
 *      │                      │
 *   立即返回              满了就丢弃并计数
 * </pre>
 *
 * <p>三条硬性要求（设计文档 D2）：
 * <ol>
 *   <li><b>异步</b>：绝不在回答线程里碰数据库</li>
 *   <li><b>有界队列</b>：宁可丢记录，也不能积压内存</li>
 *   <li><b>吞异常</b>：写库失败只记日志 —— 记录系统挂了，机器人必须照常回答</li>
 * </ol>
 */
@Component
public class QaRecorder {

    private static final Logger log = LoggerFactory.getLogger(QaRecorder.class);

    private final QaProperties props;
    private final QaStore store;

    /** 追问标记的来源。用 setter 注入，避免 QaCollector ↔ QaRecorder 构造循环依赖 */
    private volatile QaCollector followUpSource;

    public void setFollowUpSource(QaCollector source) {
        this.followUpSource = source;
    }

    private BlockingQueue<QaRecord> queue;
    private Thread worker;
    private volatile boolean running;

    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public QaRecorder(QaProperties props, QaStore store) {
        this.props = props;
        this.store = store;
    }

    @PostConstruct
    void start() {
        if (!props.isEnabled()) {
            return;
        }
        queue = new ArrayBlockingQueue<>(Math.max(1, props.getQueueCapacity()));
        running = true;
        worker = new Thread(this::loop, "qa-recorder");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /**
     * 投递一条记录。<b>本方法保证立即返回、绝不抛异常。</b>
     *
     * <p>队列满时丢弃并计数：宁可少记一条，也不能让回答线程等。
     */
    public void record(QaRecord record) {
        if (!props.isEnabled() || record == null || queue == null) {
            return;
        }
        if (!queue.offer(record)) {
            long total = dropped.incrementAndGet();
            log.warn("[QA] 记录队列已满（容量 {}），本条被丢弃。累计丢弃 {} 条",
                    props.getQueueCapacity(), total);
        }
    }

    private void loop() {
        List<QaRecord> batch = new ArrayList<>();
        while (running) {
            try {
                QaRecord first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, Math.max(1, props.getFlushBatchSize()) - 1);
                store.insertBatch(batch);
                recorded.addAndGet(batch.size());

                // 顺带把"被追问"的上一条标记掉。放在这里而不是投递路径上：
                // 投递必须立即返回，不能碰数据库。
                if (followUpSource != null) {
                    for (Long id : followUpSource.drainFollowUps()) {
                        store.markFollowUp(id);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 兜底：worker 线程绝不能因为异常死掉，否则记录会静默停止
                log.warn("[QA] 记录线程异常（继续运行）：{}", e.getMessage());
            }
        }
    }

    /** 已成功落库的条数 */
    public long recordedCount() {
        return recorded.get();
    }

    /** 因队列满被丢弃的条数 */
    public long droppedCount() {
        return dropped.get();
    }

    /** 当前积压条数（自检用） */
    public int backlog() {
        return queue == null ? 0 : queue.size();
    }
}
