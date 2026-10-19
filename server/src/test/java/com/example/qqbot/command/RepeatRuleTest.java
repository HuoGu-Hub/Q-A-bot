package com.example.qqbot.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重复规则：{@code #重复-每天#} 那一格。
 *
 * <p>重点不是"认得出多少个写法"，而是<b>认不出来时必须报错</b> ——
 * 静默当成"一次"会让一条每天发的公告只发一次，而且没人知道为什么。
 */
class RepeatRuleTest {

    private static RepeatRule parse(String raw) throws CommandParseException {
        return RepeatRule.parse(raw);
    }

    @Test
    @DisplayName("一次")
    void once() throws Exception {
        assertThat(parse("一次").kind()).isEqualTo(RepeatRule.Kind.ONCE);
        assertThat(parse("只一次").kind()).isEqualTo(RepeatRule.Kind.ONCE);
    }

    @Test
    @DisplayName("每天与每日")
    void daily() throws Exception {
        assertThat(parse("每天").kind()).isEqualTo(RepeatRule.Kind.DAILY);
        assertThat(parse("每日").kind()).isEqualTo(RepeatRule.Kind.DAILY);
    }

    @Test
    @DisplayName("★ 每周几：中文、数字都认")
    void weekly() throws Exception {
        assertThat(parse("每周三").dayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(parse("周三").dayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(parse("每周3").dayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(parse("每周日").dayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(parse("每周天").dayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(parse("每星期一").dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("★ 每 N 天：阿拉伯数字与中文数字都认")
    void everyNDays() throws Exception {
        assertThat(parse("每3天").everyDays()).isEqualTo(3);
        assertThat(parse("每三天").everyDays()).isEqualTo(3);
        assertThat(parse("每两天").everyDays()).isEqualTo(2);
        assertThat(parse("每十天").everyDays()).isEqualTo(10);
    }

    @Test
    @DisplayName("★ 认不出来必须报错，绝不能悄悄当成一次")
    void unknownIsRejected() {
        assertThatThrownBy(() -> parse("隔三差五"))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("没看懂");
        assertThatThrownBy(() -> parse(""))
                .isInstanceOf(CommandParseException.class);
        assertThatThrownBy(() -> parse(null))
                .isInstanceOf(CommandParseException.class);
    }

    @Test
    @DisplayName("「每0天」「每99天」这种越界要拒")
    void outOfRangeIsRejected() {
        assertThatThrownBy(() -> parse("每0天")).isInstanceOf(CommandParseException.class);
        assertThatThrownBy(() -> parse("每99天")).isInstanceOf(CommandParseException.class);
        assertThatThrownBy(() -> parse("每1天")).isInstanceOf(CommandParseException.class);
    }

    @Test
    @DisplayName("「每周」后面没跟星期几要拒")
    void weeklyWithoutDayIsRejected() {
        assertThatThrownBy(() -> parse("每周")).isInstanceOf(CommandParseException.class);
        assertThatThrownBy(() -> parse("每周八")).isInstanceOf(CommandParseException.class);
    }

    @Test
    @DisplayName("回执里那句人话")
    void describe() throws Exception {
        assertThat(parse("每天").describe()).isEqualTo("每天");
        assertThat(parse("每周三").describe()).isEqualTo("每周三");
        assertThat(parse("每3天").describe()).isEqualTo("每 3 天");
        assertThat(parse("一次").describe()).isEqualTo("只发一次");
    }
}
