package com.example.qqbot.guard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 出站节奏 —— 安全中间层 D7 的剩余项（{@link RateLimiter} 管"回不回"，这里管"发得多快"）。
 *
 * <p>解决两个观感问题：
 * <ol>
 *   <li><b>同群连发</b>：两条消息挨着发出去，看起来就是刷屏。这里给每个群一个
 *       最小间隔（默认 800ms）加随机抖动（±400ms）。</li>
 *   <li><b>长回复</b>：一条 800 字的回复在手机上是一整屏，没人看得完。超过
 *       {@code max-chars-per-message}（默认 300 字）就按<b>句子边界</b>切成几条，
 *       配合上面的间隔一条条发，读起来像人在分几段说。</li>
 * </ol>
 *
 * <p><b>为什么"先占位再睡"</b>：{@link #reserveGroupSlot} 在 {@code compute} 里
 * 决定本条消息的发车时刻并立刻把"下一班"往后推，然后调用方才 sleep。
 * 睡觉时不持锁 —— 否则一个群在等间隔，会把所有群的发送一起卡住。
 */
@Component
public class OutboundPacer {

    /** 群 → 下一条消息允许发出的最早时刻（毫秒时间戳） */
    private final Map<Long, Long> nextSlotAt = new ConcurrentHashMap<>();

    private final Outbound config;

    public OutboundPacer(Outbound outbound) {
        this.config = outbound;
    }

    /**
     * 占一个发车位。
     *
     * @return 本条消息还要等多少毫秒（0 = 立刻可发）。调用方负责 sleep。
     */
    public long reserveGroupSlot(long groupId) {
        if (!config.isEnabled() || config.getGroupMinIntervalMillis() <= 0) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long delta = config.getGroupMinIntervalMillis() + jitter();
        long[] wait = new long[1];
        nextSlotAt.compute(groupId, (key, next) -> {
            long at = (next == null || next < now) ? now : next;
            wait[0] = at - now;
            return at + delta;
        });
        if (nextSlotAt.size() > 5000) {
            // 群很多时清掉早就不活跃的条目，避免这张表无限长大
            nextSlotAt.entrySet().removeIf(e -> now - e.getValue() > 600_000L);
        }
        return Math.max(0L, wait[0]);
    }

    private long jitter() {
        long j = config.getGroupJitterMillis();
        return j <= 0 ? 0L : ThreadLocalRandom.current().nextLong(-j, j + 1);
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
            // 单句本身就超长（比如一大段没有标点的输出）→ 硬切，保证不会发出一条巨长的消息
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
