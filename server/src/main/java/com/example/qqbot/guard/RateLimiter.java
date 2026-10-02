package com.example.qqbot.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 滑动窗口限流器（内存版）。
 *
 * <p>为什么用滑动窗口而不是固定窗口：固定窗口在边界处能通过双倍请求
 * （比如 12:00:59 和 12:01:01 各一次，实际是 2 秒内 2 次），不符合「每分钟 1 次」的直觉。
 *
 * <p>为什么用「一次性检查全部并记录」而不是「逐个检查」：
 * 多个维度（用户 + 群）必须**原子地**一起判定。否则会出现
 * 「用户的额度已经扣了，但群额度不够，这次请求被拒」这种莫名其妙的计数错误。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** 超过这个时间没有任何请求的 key 会被清理，防止内存无限增长 */
    private static final long STALE_MILLIS = 60 * 60 * 1000L;

    private final Object lock = new Object();
    private final Map<String, Deque<Long>> windows = new HashMap<>();
    private long lastSweep = System.currentTimeMillis();

    /** 一个限流维度，例如「用户 100000001 每分钟 1 次」 */
    public record Bucket(String key, long windowMillis, int limit) {
    }

    /**
     * 所有维度都通过才放行，并且**原子地**一起记录。
     *
     * @return true = 放行；false = 至少有一个维度超限（此时不会记录任何维度）
     */
    public boolean tryAcquireAll(List<Bucket> buckets) {
        if (buckets.isEmpty()) {
            return true;
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            // 先全部检查
            for (Bucket b : buckets) {
                if (countWithin(b.key(), now - b.windowMillis()) >= b.limit()) {
                    return false;
                }
            }
            // 再全部记录
            for (Bucket b : buckets) {
                windows.computeIfAbsent(b.key(), k -> new ArrayDeque<>()).addLast(now);
            }
            sweepIfNeeded(now);
            return true;
        }
    }

    /**
     * 只查询、不记录。用于「判断到底是哪一维度超限了」，好决定回哪句提示。
     * 注意：判断结果和随后的 tryAcquireAll 之间理论上有竞态，但只影响提示文案，不影响安全性。
     */
    public boolean wouldExceed(Bucket bucket) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            return countWithin(bucket.key(), now - bucket.windowMillis()) >= bucket.limit();
        }
    }

    /** 统计窗口内的次数，并顺手把过期的记录扔掉 */
    private int countWithin(String key, long cutoff) {
        Deque<Long> queue = windows.get(key);
        if (queue == null) {
            return 0;
        }
        while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
            queue.pollFirst();
        }
        if (queue.isEmpty()) {
            windows.remove(key);
            return 0;
        }
        return queue.size();
    }

    /** 定期清理长期不活跃的 key */
    private void sweepIfNeeded(long now) {
        if (now - lastSweep < 60_000L) {
            return;
        }
        lastSweep = now;
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Deque<Long>> e : windows.entrySet()) {
            Deque<Long> q = e.getValue();
            if (q.isEmpty() || now - q.peekLast() > STALE_MILLIS) {
                dead.add(e.getKey());
            }
        }
        dead.forEach(windows::remove);
        if (!dead.isEmpty()) {
            log.debug("[GUARD] 限流器清理了 {} 个不活跃的 key", dead.size());
        }
    }

    /** 供测试使用 */
    public void reset() {
        synchronized (lock) {
            windows.clear();
        }
    }

    public int trackedKeys() {
        synchronized (lock) {
            return windows.size();
        }
    }
}
