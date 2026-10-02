package com.example.qqbot.guard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Guard 可观测（安全中间层 D7 / T7）—— 拦截了多少、拦在哪一层、谁在刷。
 *
 * <p>为什么光有日志不够：{@code log.info("[GUARD] 拦截 ...")} 只在出事的那一刻有用，
 * 事后想回答「今天拦截率多少」「哪一层最常拦」只能翻日志。而
 * {@code qa_stat.guard_action} 只覆盖了被记录下来的那部分问答。
 *
 * <p>这里全部是<b>进程内计数</b>：便宜、无锁竞争、重启归零。它回答的是
 * 「现在运行得怎么样」，长期趋势仍然看 qa_stat。
 */
@Component
public class GuardMetrics {

    private final AtomicLong inbound = new AtomicLong();
    private final AtomicLong passed = new AtomicLong();

    /** 层名 → 拦截次数（Drop + Reply 都算拦截） */
    private final Map<String, AtomicLong> blockedByStage = new ConcurrentHashMap<>();
    /** 层名 → 其中「回固定话术」的次数 */
    private final Map<String, AtomicLong> repliedByStage = new ConcurrentHashMap<>();
    /** 用户 → 被拦次数（用来找刷屏的人） */
    private final Map<Long, AtomicLong> blockedUsers = new ConcurrentHashMap<>();

    private final long startedAt = System.currentTimeMillis();

    public void inbound() {
        inbound.incrementAndGet();
    }

    public void passed() {
        passed.incrementAndGet();
    }

    public void blocked(String stage, GuardResult result, GuardContext ctx) {
        bump(blockedByStage, stage);
        if (result instanceof GuardResult.Reply) {
            bump(repliedByStage, stage);
        }
        if (ctx != null && ctx.userId() != null) {
            blockedUsers.computeIfAbsent(ctx.userId(), k -> new AtomicLong()).incrementAndGet();
            if (blockedUsers.size() > 5000) {
                // 被拦的人很多时，只留下还有动静的，避免这张表无限长大
                blockedUsers.entrySet().removeIf(e -> e.getValue().get() <= 1);
            }
        }
    }

    /** 快照，直接作为后台接口的响应体 */
    public Map<String, Object> snapshot() {
        long in = inbound.get();
        long pass = passed.get();
        long blocked = blockedByStage.values().stream().mapToLong(AtomicLong::get).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inbound", in);
        out.put("passed", pass);
        out.put("blocked", blocked);
        out.put("blockRate", in == 0 ? 0.0 : (double) blocked / in);
        out.put("byStage", toSortedMap(blockedByStage));
        out.put("replyByStage", toSortedMap(repliedByStage));
        out.put("topBlockedUsers", topUsers(5));
        out.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
        return out;
    }

    private List<Map<String, Object>> topUsers(int limit) {
        List<Map.Entry<Long, AtomicLong>> entries = new ArrayList<>(blockedUsers.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<Long, AtomicLong> e) -> e.getValue().get()).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, AtomicLong> e : entries) {
            if (out.size() >= limit || e.getValue().get() <= 0) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", e.getKey());
            row.put("count", e.getValue().get());
            out.add(row);
        }
        return out;
    }

    private static void bump(Map<String, AtomicLong> map, String key) {
        map.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    private static Map<String, Long> toSortedMap(Map<String, AtomicLong> map) {
        Map<String, Long> out = new LinkedHashMap<>();
        map.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicLong>comparingByValue(
                        Comparator.comparingLong(AtomicLong::get)).reversed())
                .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }
}
