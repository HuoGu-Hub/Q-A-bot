package com.example.qqbot.command;

import com.example.qqbot.time.AppTime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条定时公告的完整时间安排：规则 + 一组时刻。
 *
 * <h2>为什么不用"记下这一轮已经发过哪几个"</h2>
 * 记录「已发清单」要多一张表、多一次事务，还要考虑进程重启。
 * 这里改成<b>无状态推导</b>：给定"上一次发送时刻"，直接算出下一次。
 * 每天 7:00 与 14:00 两个时刻，7:00 发完之后算出来自然是 14:00，
 * 不需要记住任何东西。
 *
 * <h2>时刻的存法</h2>
 * 一次性任务存<b>绝对时刻</b>（ISO 串），因为它要精确到某一天某一点；
 * 周期任务只存<b>钟点</b>（{@code HH:mm}），因为它的日期由规则推出来。
 * 两种形状都由 {@link #nextAfter} 按规则解释，调用方不用关心。
 *
 * @param rule   重复规则
 * @param times  时刻列表；一次性放 ISO 串，周期放 HH:mm
 * @param anchor 创建时刻。{@code 每N天} 从这一天起算，否则为 null
 */
public record RepeatSchedule(RepeatRule rule, List<String> times, Instant anchor) {

    public RepeatSchedule {
        times = times == null ? List.of() : List.copyOf(times);
    }

    /**
     * 严格晚于 {@code after} 的下一次发送时刻。
     *
     * @return null 表示没有下一次了（一次性任务的时刻都发完了）
     */
    public Instant nextAfter(Instant after) {
        if (times.isEmpty()) {
            return null;
        }
        return switch (rule.kind()) {
            case ONCE -> nextOnce(after);
            case DAILY -> nextDaily(after);
            case WEEKLY -> nextWeekly(after);
            case EVERY_N_DAYS -> nextEveryDays(after);
            case TIMES -> nextTimes(after);
            case DAYS -> nextDays(after);
        };
    }

    // ==================== 一次性 ====================

    private Instant nextOnce(Instant after) {
        Instant best = null;
        for (String raw : times) {
            Instant at;
            try {
                at = Instant.parse(raw);
            } catch (RuntimeException e) {
                continue;   // 坏数据跳过，不让一条脏记录卡住整条公告
            }
            if (at.isAfter(after) && (best == null || at.isBefore(best))) {
                best = at;
            }
        }
        return best;
    }

    // ==================== 每天 ====================

    private Instant nextDaily(Instant after) {
        ZonedDateTime base = after.atZone(AppTime.ZONE);
        List<LocalTime> slots = slots();
        // 今天还没过的时刻
        for (LocalTime slot : slots) {
            Instant candidate = base.toLocalDate().atTime(slot).atZone(AppTime.ZONE).toInstant();
            if (candidate.isAfter(after)) {
                return candidate;
            }
        }
        // 今天都过了，明天第一个
        LocalTime first = slots.get(0);
        return base.toLocalDate().plusDays(1).atTime(first).atZone(AppTime.ZONE).toInstant();
    }

    // ==================== 每周几 ====================

    private Instant nextWeekly(Instant after) {
        ZonedDateTime base = after.atZone(AppTime.ZONE);
        List<LocalTime> slots = slots();
        // 从今天起往后找 7 天，第一个匹配的星期几
        for (int dayOffset = 0; dayOffset <= 7; dayOffset++) {
            LocalDate date = base.toLocalDate().plusDays(dayOffset);
            if (date.getDayOfWeek() != rule.dayOfWeek()) {
                continue;
            }
            for (LocalTime slot : slots) {
                Instant candidate = date.atTime(slot).atZone(AppTime.ZONE).toInstant();
                if (candidate.isAfter(after)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // ==================== 共 N 条 ====================

    /**
     * 有限的「N 次」：按<b>消息条数</b>算。
     *
     * <p>一天三个时刻、写 4 次 → 今天三条 + 明天第一条就停。
     * 序号用「从 anchor 开始按天铺开的时刻序列」直接算：
     * {@code 第几条 = 相差天数 × 每天时刻数 + 当日第几个 - anchor 当日第几个 + 1}。
     * 没有额外状态，重算也不会漂。
     */
    private Instant nextTimes(Instant after) {
        List<LocalTime> slots = slots();
        int count = rule.repeatCount();
        if (slots.isEmpty() || count <= 0) {
            return null;
        }
        int perDay = slots.size();
        ZonedDateTime base = after.atZone(AppTime.ZONE);
        LocalDate anchorDate = anchor == null
                ? base.toLocalDate()
                : anchor.atZone(AppTime.ZONE).toLocalDate();
        int firstSlot = anchor == null ? 0 : slotIndex(slots, anchor);
        if (firstSlot < 0) {
            firstSlot = 0;
        }
        // anchor 可能在 after 之后（带日期的新建），从它那天开始找
        long anchorOffset = ChronoUnit.DAYS.between(base.toLocalDate(), anchorDate);
        int start = (int) Math.max(0, anchorOffset);
        for (int offset = start; offset <= start + count + 1; offset++) {
            LocalDate date = base.toLocalDate().plusDays(offset);
            long dayGap = ChronoUnit.DAYS.between(anchorDate, date);
            if (dayGap < 0) {
                continue;
            }
            for (int j = 0; j < perDay; j++) {
                Instant candidate = date.atTime(slots.get(j)).atZone(AppTime.ZONE).toInstant();
                if (!candidate.isAfter(after)) {
                    continue;
                }
                long ordinal = dayGap * perDay + j + 1 - firstSlot;
                if (ordinal > count) {
                    return null;   // 条数用完
                }
                if (ordinal >= 1) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** anchor 落在时间表的第几个时刻；不匹配返回 -1 */
    private static int slotIndex(List<LocalTime> slots, Instant at) {
        LocalTime time = at.atZone(AppTime.ZONE).toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        return slots.indexOf(time);
    }

    // ==================== 连续 N 天 ====================

    /**
     * 有限的「N 天」：从 anchor 那天起，每天发满时间表里的所有时刻，共 N 天。
     */
    private Instant nextDays(Instant after) {
        List<LocalTime> slots = slots();
        int count = rule.repeatCount();
        if (slots.isEmpty() || count <= 0) {
            return null;
        }
        ZonedDateTime base = after.atZone(AppTime.ZONE);
        LocalDate anchorDate = anchor == null
                ? base.toLocalDate()
                : anchor.atZone(AppTime.ZONE).toLocalDate();
        long anchorOffset = ChronoUnit.DAYS.between(base.toLocalDate(), anchorDate);
        int start = (int) Math.max(0, anchorOffset);
        for (int offset = start; offset <= start + count; offset++) {
            LocalDate date = base.toLocalDate().plusDays(offset);
            long gap = ChronoUnit.DAYS.between(anchorDate, date);
            if (gap < 0 || gap >= count) {
                continue;
            }
            for (LocalTime slot : slots) {
                Instant candidate = date.atTime(slot).atZone(AppTime.ZONE).toInstant();
                if (candidate.isAfter(after)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // ==================== 每 N 天 ====================

    private Instant nextEveryDays(Instant after) {
        List<LocalTime> slots = slots();
        LocalDate anchorDate = anchor == null
                ? after.atZone(AppTime.ZONE).toLocalDate()
                : anchor.atZone(AppTime.ZONE).toLocalDate();
        ZonedDateTime base = after.atZone(AppTime.ZONE);

        // 往后找 31 天足够覆盖任何一个 N（上限 30）
        for (int dayOffset = 0; dayOffset <= 31; dayOffset++) {
            LocalDate date = base.toLocalDate().plusDays(dayOffset);
            long gap = ChronoUnit.DAYS.between(anchorDate, date);
            if (gap < 0 || gap % rule.everyDays() != 0) {
                continue;
            }
            for (LocalTime slot : slots) {
                Instant candidate = date.atTime(slot).atZone(AppTime.ZONE).toInstant();
                if (candidate.isAfter(after)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** 钟点列表，排好序 */
    private List<LocalTime> slots() {
        List<LocalTime> out = new ArrayList<>(times.size());
        for (String raw : times) {
            try {
                out.add(LocalTime.parse(raw));
            } catch (RuntimeException e) {
                // 坏数据跳过
            }
        }
        out.sort(null);
        return out;
    }

    /** 回执里那半句：{@code 每天 07:00、14:00} */
    public String describeTimes() {
        if (times.isEmpty()) {
            return "";
        }
        return String.join("、", times);
    }
}
