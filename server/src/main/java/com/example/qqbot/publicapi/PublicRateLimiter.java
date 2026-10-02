package com.example.qqbot.publicapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公开接口的按 IP 限流。
 *
 * <p><b>为什么必须有</b>：公开站要上公网，任何人都能打。
 * 没有限流的话，一个人写个脚本循环调检索接口，就能把机器人的 CPU 和
 * 向量模型的额度全烧掉。
 *
 * <p>算法用**滑动窗口计数**：比固定窗口准（不会在窗口边界被双倍放量），
 * 比令牌桶简单（不需要后台补充线程）。
 *
 * <p>内存风险：每个 IP 一个双端队列。为了防"海量伪造 IP 打爆内存"，
 * 超过上限时**整体清空** —— 宁可短暂放宽限流，也不能 OOM。
 */
@Component
public class PublicRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PublicRateLimiter.class);

    /** 每分钟每 IP 的最大请求数 */
    private static final int LIMIT_PER_MINUTE = 60;

    /** 窗口长度 */
    private static final long WINDOW_MS = 60_000L;

    /** 最多跟踪多少个 IP，超了就清空重来（防内存打爆） */
    private static final int MAX_TRACKED_IPS = 5000;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * 尝试放行一次请求。
     *
     * @return true = 允许；false = 超限
     */
    public boolean allow(String ip) {
        String key = ip == null || ip.isBlank() ? "unknown" : ip;
        long now = System.currentTimeMillis();

        if (hits.size() > MAX_TRACKED_IPS) {
            log.warn("[PUBLIC] 跟踪的 IP 数超过 {}，清空限流状态（防内存打爆）", MAX_TRACKED_IPS);
            hits.clear();
        }

        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= LIMIT_PER_MINUTE) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /** 剩余配额（用于返回限流响应头） */
    public int remaining(String ip) {
        Deque<Long> window = hits.get(ip == null ? "unknown" : ip);
        if (window == null) {
            return LIMIT_PER_MINUTE;
        }
        synchronized (window) {
            return Math.max(0, LIMIT_PER_MINUTE - window.size());
        }
    }

    public int limit() {
        return LIMIT_PER_MINUTE;
    }
}
