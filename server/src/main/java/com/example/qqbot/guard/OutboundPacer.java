package com.example.qqbot.guard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 出站节奏：两道闸 + 长回复切分。
 *
 * <h2>两道闸为什么不分开睡</h2>
 * 如果先睡群级、再睡账号级，两条间隔会叠加，实际发车间隔变成两者之和。
 * 正确做法是<b>取两者中较晚的那个时刻</b>，一次睡到点，两条时间线同时推进。
 *
 * <h2>为什么"先占位再睡"</h2>
 * {@link #reserve} 在锁内决定本次的发车时刻并立刻把下一次推后，然后调用方才 sleep。
 * 睡觉时<b>不持锁</b>，否则一个群在等间隔会把所有群的发送一起卡住。
 */
@Component
public class OutboundPacer {

    /** 群 → 该群下一条消息允许发出的最早时刻（毫秒时间戳） */
    private final Map<Long, Long> nextGroupSlotAt = new ConcurrentHashMap<>();

    /** 账号级：下一条消息允许发出的最早时刻。跨群共用这一条时间线 */
    private final AtomicLong nextGlobalSlotAt = new AtomicLong(0);

    private final Outbound config;

    public OutboundPacer(Outbound outbound) {
        this.config = outbound;
    }

    /**
     * 占一个发车位，两道闸一起算。
     *
     * @return 本条消息还要等多少毫秒（0 = 立刻可发）。调用方负责 sleep。
     */
    public synchronized long reserve(boolean group, long groupId) {
        if (!config.isEnabled()) {
            return 0L;
        }
        long now = System.currentTimeMillis();

        // 本次最早能发的时刻：现在、账号级下一班、该群下一班，三者取最晚
        long at = Math.max(now, nextGlobalSlotAt.get());
        if (group) {
            Long groupNext = nextGroupSlotAt.get(groupId);
            if (groupNext != null) {
                at = Math.max(at, groupNext);
            }
        }

        // 两条时间线同时推进到"本次 + 各自的间隔"
        long globalDelta = interval(config.getGlobalMinIntervalMillis(),
                config.getGlobalJitterMinMillis(), config.getGlobalJitterMaxMillis());
        nextGlobalSlotAt.set(at + globalDelta);
        if (group) {
            long groupDelta = interval(config.getGroupMinIntervalMillis(),
                    config.getGroupJitterMinMillis(), config.getGroupJitterMaxMillis());
            nextGroupSlotAt.put(groupId, at + groupDelta);
        }

        if (nextGroupSlotAt.size() > 5000) {
            // 群很多时清掉早就不活跃的条目，避免这张表无限长大
            nextGroupSlotAt.entrySet().removeIf(e -> now - e.getValue() > 600_000L);
        }
        return Math.max(0L, at - now);
    }

    /** 基础间隔加区间抖动。间隔 <= 0 表示这道闸关着。包级可见：便于单独验证区间边界 */
    static long interval(long base, long jitterMin, long jitterMax) {
        if (base <= 0) {
            return 0L;
        }
        long lo = Math.min(jitterMin, jitterMax);
        long hi = Math.max(jitterMin, jitterMax);
        long jitter = (lo == hi) ? lo : ThreadLocalRandom.current().nextLong(lo, hi + 1);
        return Math.max(0L, base + jitter);
    }

    /**
     * 长回复按句子切分。
     *
     * <p>故意<b>不</b>在英文句点 {@code .} 上切 —— 那会把网址和版本号切开
     * （{@code https://a.b/c} 会变成两条）。中文句号/问号/叹号/分号/换行足够用。
     *
     * @return 至少一条；不超限时原样返回单元素列表
     */
    public List<String> split(String text) {
        int max = config.getMaxCharsPerMessage();
        if (text == null || text.isEmpty() || max <= 0 || text.length() <= max) {
            return List.of(text == null ? "" : text);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int end = nextSentenceEnd(text, i);
            String sentence = text.substring(i, end);
            i = end;

            if (current.length() > 0 && current.length() + sentence.length() > max) {
                parts.add(current.toString().strip());
                current.setLength(0);
            }
            while (sentence.length() > max) {
                if (current.length() > 0) {
                    parts.add(current.toString().strip());
                    current.setLength(0);
                }
                parts.add(sentence.substring(0, max).strip());
                sentence = sentence.substring(max);
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            parts.add(current.toString().strip());
        }
        parts.removeIf(String::isEmpty);
        return parts.isEmpty() ? List.of(text) : parts;
    }

    /** 找到从 {@code from} 开始的第一句话的结束位置（含标点） */
    private static int nextSentenceEnd(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '…' || c == '\n'
                    || c == '!' || c == '?' || c == ';') {
                return i + 1;
            }
        }
        return text.length();
    }
}
