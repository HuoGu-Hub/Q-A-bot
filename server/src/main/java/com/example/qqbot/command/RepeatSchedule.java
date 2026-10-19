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
