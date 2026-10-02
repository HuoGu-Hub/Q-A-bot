package com.example.qqbot.command;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指令的独立限流。
 *
 * <p><b>为什么不复用问答的限流器</b>：问答限流是"每人每分钟 1 次"，
 * 如果指令也受它管，那 §/help§ 一天就只能用一次 —— 毫无意义。
 *
 * <p><b>但也不能不限</b>：否则有人刷 §/list§ 就能刷屏。
 * 所以给指令单独一个更宽松的配额（默认每人 10 次/分钟）。
 */
@Component
public class CommandRateLimiter {

    private final CommandPolicy props;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** 最多跟踪多少用户，超了清空（防内存打爆） */
    private static final int MAX_TRACKED = 5000;

    public CommandRateLimiter(CommandPolicy props) {
        this.props = props;
    }

    /**
     * @param key 一般是 "用户ID@群ID" —— 指令按人按群限流
     * @return true = 放行
     */
    public boolean allow(String key) {
        int limit = props.getRateLimitPerMinute();
        if (limit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (hits.size() > MAX_TRACKED) {
            hits.clear();
        }
        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000L) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    public int remaining(String key) {
        Deque<Long> w = hits.get(key);
        if (w == null) {
            return props.getRateLimitPerMinute();
        }
        synchronized (w) {
            return Math.max(0, props.getRateLimitPerMinute() - w.size());
        }
    }
}
