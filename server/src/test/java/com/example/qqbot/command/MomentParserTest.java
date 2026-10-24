package com.example.qqbot.command;

import com.example.qqbot.time.AppTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时间参数认哪些写法。
 *
 * <p>这一层是群友最先撞上的地方：写「21点30」还是「21:30」、写「今晚」会不会
 * 被顺延到明天。全部用东八区墙上时间断言 —— 群友说的就是这个时区的时间。
 */
class MomentParserTest {

    /** 2026-10-24 09:32，星期六 */
    private static final ZonedDateTime NOW =
            ZonedDateTime.of(LocalDate.of(2026, 10, 24), LocalTime.of(9, 32), AppTime.ZONE);

    private static Instant parse(String raw) throws CommandParseException {
        return MomentParser.parseMoment(raw, NOW.toInstant());
    }

    private static ZonedDateTime at(int day, int hour, int minute) {
        return ZonedDateTime.of(LocalDate.of(2026, 10, day), LocalTime.of(hour, minute), AppTime.ZONE);
    }

    @Test
    @DisplayName("★ 只写时刻：今天没过就是今天，过了顺延到明天")
    void clockOnlyRollsToNextDay() throws Exception {
        assertThat(parse("21:30")).isEqualTo(at(24, 21, 30).toInstant());
        assertThat(parse("9:30")).isEqualTo(at(25, 9, 30).toInstant());
        assertThat(parse("0:00")).isEqualTo(at(25, 0, 0).toInstant());
    }

    @Test
    @DisplayName("★ 今天 / 今晚锚定当天，过了也不再顺延")
    void todayWordsDoNotRoll() throws Exception {
        assertThat(parse("今天21:30")).isEqualTo(at(24, 21, 30).toInstant());
        assertThat(parse("今晚21:30")).isEqualTo(at(24, 21, 30).toInstant());

        // 已经过去的今天，解析结果就是过去的时刻，由调用方拒绝，不能偷偷改成明天
        Instant past = parse("今晚8:00");
        assertThat(past).isEqualTo(at(24, 8, 0).toInstant());
    }

    @Test
    @DisplayName("明天 / 后天 / 明早 / 明晚")
    void dayWords() throws Exception {
        assertThat(parse("明天9:00")).isEqualTo(at(25, 9, 0).toInstant());
        assertThat(parse("后天20:00")).isEqualTo(at(26, 20, 0).toInstant());
        assertThat(parse("明早7:00")).isEqualTo(at(25, 7, 0).toInstant());
        assertThat(parse("明晚21:00")).isEqualTo(at(25, 21, 0).toInstant());
    }

    @Test
    @DisplayName("★ 周几：没到就是本周，过了就是下周；下周三再往后一周")
    void weekdays() throws Exception {
        assertThat(parse("周三21:30")).isEqualTo(at(28, 21, 30).toInstant());
        assertThat(parse("周日10:00")).isEqualTo(at(25, 10, 0).toInstant());
        assertThat(parse("下周三21:30")).isEqualTo(ZonedDateTime.of(
                LocalDate.of(2026, 11, 4), LocalTime.of(21, 30), AppTime.ZONE).toInstant());
    }

    @Test
    @DisplayName("★ 明确日期：不带年份且已经过了，按明年算")
    void explicitDates() throws Exception {
        assertThat(parse("12-01 21:30"))
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2026, 12, 1), LocalTime.of(21, 30), AppTime.ZONE).toInstant());
        // 10 月 18 日已经过去 → 明年
        assertThat(parse("10-18 21:30"))
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2027, 10, 18), LocalTime.of(21, 30), AppTime.ZONE).toInstant());
        assertThat(parse("2026-12-01 21:30"))
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2026, 12, 1), LocalTime.of(21, 30), AppTime.ZONE).toInstant());
        assertThat(parse("2026/12/01 21:30"))
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2026, 12, 1), LocalTime.of(21, 30), AppTime.ZONE).toInstant());
        assertThat(parse("10月18日 21:30"))
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2027, 10, 18), LocalTime.of(21, 30), AppTime.ZONE).toInstant());
    }

    @Test
    @DisplayName("相对时间：分钟后 / 小时后 / 天后，后字可省")
    void relative() throws Exception {
        Instant now = NOW.toInstant();
        assertThat(parse("30分钟")).isEqualTo(now.plusSeconds(30 * 60));
        assertThat(parse("3小时后")).isEqualTo(now.plusSeconds(3 * 3600));
        assertThat(parse("2天之后")).isEqualTo(now.plusSeconds(2 * 86400));
        assertThat(parse("3天")).isEqualTo(now.plusSeconds(3 * 86400));
    }

    @Test
    @DisplayName("全角数字与全角冒号先归一")
    void fullWidthIsNormalized() throws Exception {
        assertThat(parse("２１：３０")).isEqualTo(at(24, 21, 30).toInstant());
    }

    @Test
    @DisplayName("★ 只给日期、缺时刻要报错，不能默认零点")
    void dateWithoutClockIsRejected() {
        assertThatThrownBy(() -> parse("明天"))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("没写时间");
        assertThatThrownBy(() -> parse("2026-12-01"))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("没写时间");
    }

    @Test
    @DisplayName("「点」「半」不是有效时刻写法，报错要说清原因")
    void dotClockIsNotSupported() {
        assertThatThrownBy(() -> parse("21点30"))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("只认冒号");
        assertThatThrownBy(() -> parse("晚上9点"))
                .isInstanceOf(CommandParseException.class);
    }

    @Test
    @DisplayName("越界与不存在的日期")
    void invalidValues() {
        assertThatThrownBy(() -> parse("24:00")).isInstanceOf(CommandParseException.class);
        assertThatThrownBy(() -> parse("21:60")).isInstanceOf(CommandParseException.class);
        assertThatThrownBy(() -> parse("2-30 21:30")).isInstanceOf(CommandParseException.class)
                .hasMessageContaining("不是有效日期");
        assertThatThrownBy(() -> parse("隔三差五")).isInstanceOf(CommandParseException.class);
    }

    @Test
    @DisplayName("日期参数：今天 / 周几 / 明确日期 / N 天后")
    void parseDateForms() throws Exception {
        Instant now = NOW.toInstant();
        assertThat(MomentParser.parseDate("今天", now)).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(MomentParser.parseDate("周三", now)).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(MomentParser.parseDate("10-18", now)).isEqualTo(LocalDate.of(2027, 10, 18));
        assertThat(MomentParser.parseDate("3天后", now)).isEqualTo(LocalDate.of(2026, 10, 27));
    }
}
