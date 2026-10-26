package com.example.qqbot.distill;

import com.example.qqbot.persistence.ChatRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 分批器 —— 纯函数，不碰数据库，方便单测。
 *
 * <p>先按会话间隙断开，再按条数 / 字数 / 天切分。相邻批次可以重叠几条，
 * 避免一句问答被切断；重叠带来的重复由蒸馏侧去重兜底。
 */
public final class DistillBatchPlanner {

    /** 一张图的占位估算：提示词里会写图片编号和描述，按 30 字算 */
    public static final int IMAGE_CHARS = 30;

    public record Options(String mode, int maxMessages, int maxChars,
                          int maxGapMinutes, int overlap, int maxImages, int maxBatches) {
    }

    public record Batch(int seq, long messageFrom, long messageTo,
                        int messageCount, int charCount) {
    }

    /** 分批结果。{@code messageCount}/{@code charCount} 是去重后的总量，不是各批之和 */
    public record Plan(List<Batch> batches, int messageCount, int charCount) {
    }

    private DistillBatchPlanner() {
    }

    public static Plan plan(List<ChatRepository.MessageRef> messages, Options options) {
        if (messages == null || messages.isEmpty()) {
            return new Plan(List.of(), 0, 0);
        }
        Options o = normalize(options);
        List<Batch> batches = new ArrayList<>();
        int totalChars = 0;
        for (ChatRepository.MessageRef m : messages) {
            totalChars += charsOf(m);
        }

        int n = messages.size();
        int i = 0;
        int seq = 1;
        while (i < n) {
            int start = i;
            int end = i;
            int chars = 0;
            int images = 0;
            Instant prev = null;
            while (end < n) {
                ChatRepository.MessageRef m = messages.get(end);
                Instant t = ChatTime.parse(m.ts());
                if (end > start) {
                    if (gapTooLarge(prev, t, o.maxGapMinutes())) {
                        break;
                    }
                    // 图片也要计入批次硬上限：100 条消息里超过 6 张图时继续拆批，
                    // 让第 7 张之后的图片进入下一批，而不是被 Worker 直接丢掉
                    if (m.imageCount() > 0 && images + m.imageCount() > o.maxImages()) {
                        break;
                    }
                    if ("day".equals(o.mode()) && !sameDay(prev, t)) {
                        break;
                    }
                    if (end - start >= o.maxMessages()) {
                        break;
                    }
                    if (chars + charsOf(m) > o.maxChars()) {
                        break;
                    }
                }
                chars += charsOf(m);
                images += Math.max(0, m.imageCount());
                prev = t;
                end++;
            }
            if (end == start) {
                // 单条超长消息：至少成一批，不能卡死
                end = start + 1;
                chars = charsOf(messages.get(start));
            }
            batches.add(new Batch(seq++, messages.get(start).id(), messages.get(end - 1).id(),
                    end - start, chars));
            if (batches.size() > o.maxBatches()) {
                throw new IllegalArgumentException("分批太多，已超过单任务上限 " + o.maxBatches()
                        + " 批；请缩小时间范围或调大每批字数");
            }
            if (end >= n) {
                break;
            }
            int next = end - o.overlap();
            i = Math.max(next, start + 1);
        }
        return new Plan(List.copyOf(batches), n, totalChars);
    }

    private static boolean gapTooLarge(Instant prev, Instant current, int maxGapMinutes) {
        if (prev == null || current == null || maxGapMinutes <= 0) {
            return false;
        }
        return Duration.between(prev, current).toMinutes() > maxGapMinutes;
    }

    private static boolean sameDay(Instant a, Instant b) {
        if (a == null || b == null) {
            return true;
        }
        return a.atZone(ChatTime.ZONE).toLocalDate().equals(b.atZone(ChatTime.ZONE).toLocalDate());
    }

    private static int charsOf(ChatRepository.MessageRef m) {
        return Math.max(0, m.chars()) + Math.max(0, m.imageCount()) * IMAGE_CHARS;
    }

    private static Options normalize(Options o) {
        String mode = o.mode() == null || o.mode().isBlank() ? "count" : o.mode().trim().toLowerCase();
        if (!mode.equals("count") && !mode.equals("chars") && !mode.equals("day")) {
            throw new IllegalArgumentException("batchMode 只能是 count / chars / day");
        }
        int maxMessages = Math.max(1, o.maxMessages());
        int maxChars = Math.max(200, o.maxChars());
        int maxGap = Math.max(0, o.maxGapMinutes());
        int overlap = Math.max(0, Math.min(o.overlap(), maxMessages - 1));
        int maxImages = Math.max(1, o.maxImages());
        int maxBatches = Math.max(1, o.maxBatches());
        return new Options(mode, maxMessages, maxChars, maxGap, overlap, maxImages, maxBatches);
    }
}
