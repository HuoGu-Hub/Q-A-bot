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
        // 1-7 全覆盖，中文「七」与「日」「天」同义
        assertThat(parse("每周1").dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(parse("每周2").dayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(parse("每周6").dayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(parse("每周7").dayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(parse("每周七").dayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("★ 每 N 天：阿拉伯数字与中文数字都认")
    void everyNDays() throws Exception {
        assertThat(parse("每3天").everyDays()).isEqualTo(3);
        assertThat(parse("每三天").everyDays()).isEqualTo(3);
        assertThat(parse("每两天").everyDays()).isEqualTo(2);
        assertThat(parse("每十天").everyDays()).isEqualTo(10);
        assertThat(parse("每十五天").everyDays()).isEqualTo(15);
        assertThat(parse("每二十天").everyDays()).isEqualTo(20);
        assertThat(parse("每三十天").everyDays()).isEqualTo(30);
        assertThat(parse("每23天").everyDays()).isEqualTo(23);
    }

    @Test
    @DisplayName("★ 每 N 周不支持，要给出能照做的提示")
    void everyNWeeksIsRejected() {
        assertThatThrownBy(() -> parse("每两周"))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("每 N 周");
    }

    @Test
    @DisplayName("★ 落库形态 once/daily/weekly:3 转回人话，脏数据原样返回")
    void describeStored() {
        assertThat(RepeatRule.describeStored("once")).isEqualTo("只发一次");
        assertThat(RepeatRule.describeStored("daily")).isEqualTo("每天");
        assertThat(RepeatRule.describeStored("weekly:3")).isEqualTo("每周三");
        assertThat(RepeatRule.describeStored("every:5")).isEqualTo("每 5 天");
        assertThat(RepeatRule.describeStored("隔三差五")).isEqualTo("隔三差五");
        assertThat(RepeatRule.describeStored("")).isEmpty();
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
        assertThatThrownBy(() -> parse("每31天")).isInstanceOf(CommandParseException.class);
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
