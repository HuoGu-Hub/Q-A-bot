package com.example.qqbot.command;

import com.example.qqbot.time.AppTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下一次什么时候发。
 *
 * <p>这里全部用"东八区的墙上时间"来断言，因为群友写的就是墙上时间：
 * 他说「每天 21:30」，指的是北京时间 21:30，不是 UTC 13:30。
 */
class RepeatScheduleTest {

    /** 2026-10-20 是星期二 */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private static Instant at(int hour, int minute) {
        return ZonedDateTime.of(TODAY, LocalTime.of(hour, minute), AppTime.ZONE).toInstant();
    }

    private static Instant at(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), AppTime.ZONE).toInstant();
    }

    private static RepeatSchedule daily(String... times) {
        return new RepeatSchedule(RepeatRule.daily(), List.of(times), at(0, 0));
    }

    // ==================== 每天 ====================

    @Test
    @DisplayName("★ 一天三次：上午发完后，下一次是当天下午的那一趟")
    void dailySlotsWithinTheSameDay() {
        RepeatSchedule s = daily("07:00", "14:00", "21:30");

        assertThat(s.nextAfter(at(8, 0))).isEqualTo(at(14, 0));
        assertThat(s.nextAfter(at(15, 0))).isEqualTo(at(21, 30));
    }

    @Test
    @DisplayName("★ 当天最后一趟发完，下一次是明天第一趟")
    void dailyRollsToTomorrow() {
        RepeatSchedule s = daily("07:00", "21:30");

        assertThat(s.nextAfter(at(22, 0))).isEqualTo(at(TODAY.plusDays(1), 7, 0));
    }

    @Test
    @DisplayName("正好卡在发车时刻上，下一次要往后推 —— 不能原地重复发")
    void dailyDoesNotRepeatTheSameInstant() {
        RepeatSchedule s = daily("07:00", "14:00");

        assertThat(s.nextAfter(at(7, 0))).isEqualTo(at(14, 0));
    }

    @Test
    @DisplayName("时刻乱序写入也不影响：内部会排好序")
    void dailySortsSlots() {
        RepeatSchedule s = daily("21:30", "07:00", "14:00");

        assertThat(s.nextAfter(at(8, 0))).isEqualTo(at(14, 0));
    }

    // ==================== 每周几 ====================

    @Test
    @DisplayName("★ 每周三：今天是周二，下一次是明天")
    void weeklyFromTheDayBefore() throws Exception {
        RepeatSchedule s = new RepeatSchedule(
                RepeatRule.parse("每周三"), List.of("21:30"), null);

        assertThat(s.nextAfter(at(10, 0))).isEqualTo(at(TODAY.plusDays(1), 21, 30));
    }

    @Test
    @DisplayName("★ 每周二且今天的时刻已过 → 下周二，不是本周")
    void weeklyRollsToNextWeek() throws Exception {
        RepeatSchedule s = new RepeatSchedule(
                RepeatRule.parse("每周二"), List.of("21:30"), null);

        assertThat(s.nextAfter(at(23, 0))).isEqualTo(at(TODAY.plusDays(7), 21, 30));
    }

    @Test
    @DisplayName("每周二且今天还没到点 → 就是今天")
    void weeklyStaysTodayWhenNotPassed() throws Exception {
        RepeatSchedule s = new RepeatSchedule(
                RepeatRule.parse("每周二"), List.of("21:30"), null);

        assertThat(s.nextAfter(at(10, 0))).isEqualTo(at(21, 30));
    }

    // ==================== 每 N 天 ====================

    @Test
    @DisplayName("★ 每 3 天：从创建那天起算，中间的日子跳过")
    void everyNDaysSkipsMiddleDays() throws Exception {
        RepeatSchedule s = new RepeatSchedule(
                RepeatRule.parse("每3天"), List.of("07:00"), at(6, 0));

        // 创建日当天 06:00 → 当天 07:00
        assertThat(s.nextAfter(at(6, 0))).isEqualTo(at(7, 0));
        // 当天 07:00 已过 → 第 3 天
        assertThat(s.nextAfter(at(8, 0))).isEqualTo(at(TODAY.plusDays(3), 7, 0));
        // 第 3 天之后 → 第 6 天
        assertThat(s.nextAfter(at(TODAY.plusDays(3), 8, 0)))
                .isEqualTo(at(TODAY.plusDays(6), 7, 0));
    }

    // ==================== 共 N 次 ====================

    @Test
    @DisplayName("★ 共 3 次：连续三天，发完就没有下一次")
    void timesFiresFixedDaysThenStops() throws Exception {
        Instant anchor = at(21, 30);
        RepeatSchedule s = new RepeatSchedule(
                RepeatRule.parse("3次"), List.of("21:30"), anchor);

        assertThat(s.nextAfter(at(9, 0))).isEqualTo(at(21, 30));
        assertThat(s.nextAfter(at(21, 30))).isEqualTo(at(TODAY.plusDays(1), 21, 30));
        assertThat(s.nextAfter(at(TODAY.plusDays(1), 21, 30)))
                .isEqualTo(at(TODAY.plusDays(2), 21, 30));
        assertThat(s.nextAfter(at(TODAY.plusDays(2), 21, 30))).isNull();
    }

    @Test
    @DisplayName("★ 第一次在明天：从明天起算 3 天，不是从今天")
    void timesAnchorsAtFirstMoment() throws Exception {
        Instant anchor = at(TODAY.plusDays(1), 21, 30);
        RepeatSchedule s = new RepeatSchedule(
                RepeatRule.parse("3次"), List.of("21:30"), anchor);

        assertThat(s.nextAfter(at(23, 0))).isEqualTo(at(TODAY.plusDays(1), 21, 30));
        assertThat(s.nextAfter(at(TODAY.plusDays(3), 22, 0))).isNull();
    }

    @Test
    @DisplayName("★ 4 次 = 4 条：一天三个时刻，发完第二天的第一条就停")
    void timesCountsMessagesNotDays() throws Exception {
        RepeatSchedule s = new RepeatSchedule(
                RepeatRule.parse("4次"), List.of("07:00", "12:00", "21:30"), at(7, 0));

        assertThat(s.nextAfter(at(6, 0))).isEqualTo(at(7, 0));
        assertThat(s.nextAfter(at(7, 0))).isEqualTo(at(12, 0));
        assertThat(s.nextAfter(at(12, 0))).isEqualTo(at(21, 30));
        assertThat(s.nextAfter(at(21, 30))).isEqualTo(at(TODAY.plusDays(1), 7, 0));
        assertThat(s.nextAfter(at(TODAY.plusDays(1), 7, 0))).isNull();
    }

    @Test
    @DisplayName("★ 连续 2 天：每天两个时刻，共发四条")
    void daysCountsWholeDays() throws Exception {
        RepeatSchedule s = new RepeatSchedule(
                RepeatRule.parse("2天"), List.of("07:00", "21:30"), at(7, 0));

        assertThat(s.nextAfter(at(6, 0))).isEqualTo(at(7, 0));
        assertThat(s.nextAfter(at(7, 0))).isEqualTo(at(21, 30));
        assertThat(s.nextAfter(at(21, 30))).isEqualTo(at(TODAY.plusDays(1), 7, 0));
        assertThat(s.nextAfter(at(TODAY.plusDays(1), 21, 30))).isNull();
    }

    // ==================== 一次性 ====================

    @Test
    @DisplayName("★ 一次性多个时刻：按顺序一个个发，发完就没有下一次")
    void onceFiresEachInstantThenStops() {
        Instant first = at(7, 0);
        Instant second = at(21, 30);
        RepeatSchedule s = new RepeatSchedule(RepeatRule.once(),
                List.of(first.toString(), second.toString()), null);

        assertThat(s.nextAfter(at(0, 0))).isEqualTo(first);
        assertThat(s.nextAfter(first)).isEqualTo(second);
        assertThat(s.nextAfter(second)).as("都发完了").isNull();
    }

    @Test
    @DisplayName("一次性任务的时刻已经全部过去 → 没有下一次")
    void onceInThePastHasNoNext() {
        RepeatSchedule s = new RepeatSchedule(RepeatRule.once(),
                List.of(at(7, 0).toString()), null);

        assertThat(s.nextAfter(at(8, 0))).isNull();
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("没有时刻的公告永远不触发，而不是每轮都触发")
    void emptyTimesNeverFires() {
        RepeatSchedule s = new RepeatSchedule(RepeatRule.daily(), List.of(), null);

        assertThat(s.nextAfter(at(0, 0))).isNull();
    }

    @Test
    @DisplayName("坏掉的时刻串被跳过，不影响其它好的")
    void brokenTimeIsSkipped() {
        RepeatSchedule s = new RepeatSchedule(RepeatRule.daily(),
                List.of("坏掉", "14:00"), null);

        assertThat(s.nextAfter(at(8, 0))).isEqualTo(at(14, 0));
    }
}
