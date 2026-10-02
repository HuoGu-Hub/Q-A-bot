package com.example.qqbot.logs;

import com.example.qqbot.config.LogProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志的内存环形缓冲 —— 管理后台「实时日志」页的数据源。
 *
 * <p><b>为什么用环形缓冲而不是 List</b>：日志写入是高频路径，
 * {@link ArrayDeque} 的 §addLast§ + §pollFirst§ 都是 O(1)，
 * 不会因为"攒了几万条再裁剪"而周期性卡顿。
 *
 * <p><b>容量固定</b>：满了丢最旧的。这里刻意不提供"无限保留"——
 * 那是文件该干的事，内存里留最近 2000 条足够定位问题。
 *
 * <p>本类是**多线程安全**的：写入来自各个业务线程（logback appender），
 * 读取来自 HTTP 请求线程。
 */
@Component
public class LogBuffer {

    private final LogProperties props;
    private final Deque<LogEntry> entries;
    private final AtomicLong totalWritten = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /** 每个 SSE 订阅者的游标（下一次该读哪条） */
    private final AtomicLong sequence = new AtomicLong();

    public LogBuffer(LogProperties props) {
        this.props = props;
        this.entries = new ArrayDeque<>(Math.max(64, props.getBufferSize()));
    }

    /**
     * 一条日志。
     *
     * @param seq     全局递增序号 —— SSE 客户端靠它做断点续传
     * @param ts      时间
     * @param level   级别
     * @param message 正文（可能含换行）
     * @param throwable 异常摘要（只留前若干行，完整堆栈去文件里看）
     */
    public record LogEntry(long seq, String ts, String level,
                           String message, String throwable) {
    }

    /** 追加一条。**本方法必须极快** —— 它在每条日志的写入路径上 */
    public void append(String level, String message, String throwable) {
        LogEntry e = new LogEntry(
                sequence.incrementAndGet(),
                Instant.now().toString(),
                level,
                message,
                throwable);

        synchronized (entries) {
            entries.addLast(e);
            // 超容量就丢最旧的
            while (entries.size() > props.getBufferSize()) {
                entries.pollFirst();
                dropped.incrementAndGet();
            }
        }
        totalWritten.incrementAndGet();
    }

    /**
     * 取最近的日志（新的在后）。
     *
     * @param limit       最多多少条
     * @param minLevel    最低级别（null = 不过滤）
     * @param afterSeq    只要 seq 大于它的（断点续传）
     */
    public List<LogEntry> recent(int limit, String minLevel, long afterSeq) {
        List<LogEntry> snapshot;
        synchronized (entries) {
            snapshot = new ArrayList<>(entries);
        }
        int levelRank = rank(minLevel);
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry e : snapshot) {
            if (e.seq() <= afterSeq) {
                continue;
            }
            if (levelRank > 0 && rank(e.level()) < levelRank) {
                continue;
            }
            out.add(e);
        }
        // limit<=0 或超出时，取最新的 limit 条
        if (limit > 0 && out.size() > limit) {
            out = out.subList(out.size() - limit, out.size());
        }
        return out;
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public long totalWritten() {
        return totalWritten.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public long currentSeq() {
        return sequence.get();
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    public int capacity() {
        return props.getBufferSize();
    }

    /** 级别排序：数字越大越严重。未知级别按 INFO 处理 */
    public static int rank(String level) {
        if (level == null) {
            return 0;
        }
        return switch (level.toUpperCase()) {
            case "TRACE" -> 1;
            case "DEBUG" -> 2;
            case "INFO" -> 3;
            case "WARN" -> 4;
            case "ERROR" -> 5;
            default -> 3;
        };
    }
}
