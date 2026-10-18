package com.example.qqbot.command;

import com.example.qqbot.time.AppTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令尾部解析的验收测试。
 *
 * <p>这一层最容易出错的是三处：<b>形状不符的井号段</b>、<b>摘走参数后留下的空白</b>、
 * <b>变量展开与类型校验的先后</b>。用例按这三件事组织。
 */
class CommandParserTest {

    /** 固定"现在"为本地 2026-10-18 09:00，时间断言才有确定性 */
    private static final Instant NOW = ZonedDateTime.of(2026, 10, 18, 9, 0, 0, 0, AppTime.ZONE).toInstant();

    private final CommandParser parser = new CommandParser();

    // 这些夹具都做成可选：多数用例只想验"摘到什么"，不想被必填校验挡住
    private static final ParamSpec TIME = new ParamSpec(
            "time", ParamType.MOMENT, false, null, 0, "时间点", "21:30", List.of());
    private static final ParamSpec TEXT = new ParamSpec(
            "text", ParamType.TEXT, false, null, 0, "内容", "晚上打本", List.of());
    private static final ParamSpec DATE = new ParamSpec(
            "date", ParamType.DATE, false, null, 0, "日期", "2026-10-18", List.of());
    private static final ParamSpec COUNT = new ParamSpec(
            "count", ParamType.INT, false, "1", 0, "次数", "3", List.of());

    private CommandParser.Result parse(String raw, List<ParamSpec> specs) throws CommandParseException {
        return parser.parse(raw, specs, UnaryOperator.identity(), NOW);
    }

    /* ==================== 摘取 ==================== */

    @Test
    @DisplayName("没有参数表的命令：整段都是文本，井号段不摘")
    void noSpecsKeepsEverything() throws Exception {
        CommandParser.Result r = parse("#time-21:30# 文本", List.of());

        assertThat(r.args().size()).isZero();
        assertThat(r.text()).isEqualTo("#time-21:30# 文本");
    }

    @Test
    @DisplayName("摘一个参数，剩下的当文本")
    void extractsSingleParam() throws Exception {
        CommandParser.Result r = parse("#time-21:30# 文本", List.of(TIME, TEXT));

        assertThat(r.args().raw("time")).isEqualTo("21:30");
        assertThat(r.args().has("text")).as("自由文本不等于 #text# 参数").isFalse();
        assertThat(r.text()).isEqualTo("文本");
    }

    @Test
    @DisplayName("★ 值里含减号：2026-10-18 不会被切碎")
    void dashInsideValueIsKept() throws Exception {
        CommandParser.Result r = parse("#date-2026-10-18#", List.of(DATE));

        assertThat(r.args().date("date")).isEqualTo(java.time.LocalDate.of(2026, 10, 18));
        assertThat(r.args().raw("date")).isEqualTo("2026-10-18");
    }

    @Test
    @DisplayName("全角井号与全角减号都认")
    void fullWidthAccepted() throws Exception {
        CommandParser.Result r = parse("＃time－21:30＃", List.of(TIME));

        assertThat(r.args().raw("time")).isEqualTo("21:30");
    }

    @Test
    @DisplayName("★ 反斜杠转义：\\# 是字面井号，不成段")
    void escapedHashIsLiteral() throws Exception {
        CommandParser.Result r = parse("话题 \\#1 文本", List.of(TEXT));

        assertThat(r.args().has("text")).isFalse();
        assertThat(r.text()).isEqualTo("话题 #1 文本");
    }

    @Test
    @DisplayName("★ 形状不符的 #1# 留在文本里，不当参数")
    void shapelessHashStaysAsText() throws Exception {
        CommandParser.Result r = parse("#1# 打本", List.of(TEXT));

        assertThat(r.args().has("text")).isFalse();
        assertThat(r.text()).isEqualTo("#1# 打本");
    }

    @Test
    @DisplayName("没有收尾井号时整段当文本")
    void unclosedHashStaysAsText() throws Exception {
        CommandParser.Result r = parse("#time-21:30 打本", List.of(TIME));

        assertThat(r.args().has("time")).isFalse();
        assertThat(r.text()).isEqualTo("#time-21:30 打本");
    }

    /* ==================== 空白 ==================== */

    @Test
    @DisplayName("★ 摘走参数不在文本里留下多余空格")
    void extraSpacesDoNotLeakIntoText() throws Exception {
        CommandParser.Result r = parse("#time-21:30#      文本", List.of(TIME));

        assertThat(r.text()).isEqualTo("文本");
    }

    @Test
    @DisplayName("★ 值内部的空格原样保留")
    void spacesInsideValueAreKept() throws Exception {
        CommandParser.Result r = parse("#text-晚上   打本#", List.of(TEXT));

        assertThat(r.args().raw("text")).isEqualTo("晚上   打本");
    }

    @Test
    @DisplayName("参数写在文本后面一样能摘到")
    void paramsCanFollowText() throws Exception {
        CommandParser.Result r = parse("晚上打本 #time-21:30#", List.of(TIME));

        assertThat(r.args().raw("time")).isEqualTo("21:30");
        assertThat(r.text()).isEqualTo("晚上打本");
    }

    @Test
    @DisplayName("名字与值的首尾空白自动去掉")
    void nameAndValueAreTrimmed() throws Exception {
        CommandParser.Result r = parse("# time - 21:30 #", List.of(TIME));

        assertThat(r.args().raw("time")).isEqualTo("21:30");
    }

    /* ==================== 校验 ==================== */

    @Test
    @DisplayName("★ 没声明过的参数名要报出来，并列出可用的")
    void unknownParamIsRejected() {
        assertThatThrownBy(() -> parse("#count-3#", List.of(TIME, TEXT)))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("没有 #count# 这个参数")
                .hasMessageContaining("#time#");
    }

    @Test
    @DisplayName("同一个参数给两次要报错，不静默取后者")
    void duplicateParamIsRejected() {
        assertThatThrownBy(() -> parse("#count-1# #count-2#", List.of(COUNT)))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("给了两次");
    }

    @Test
    @DisplayName("缺必填参数要报错")
    void missingRequiredIsRejected() {
        assertThatThrownBy(() -> parse("文本", List.of(TIME, new ParamSpec(
                "who", ParamType.TEXT, true, null, 0, null, null, List.of()))))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("缺少必填参数");
    }

    @Test
    @DisplayName("可选参数走默认值")
    void defaultIsApplied() throws Exception {
        CommandParser.Result r = parse("文本", List.of(COUNT));

        assertThat(r.args().number("count")).isEqualTo(1L);
    }

    @Test
    @DisplayName("整数不是数字要报哪一项不对")
    void intValidation() {
        assertThatThrownBy(() -> parse("#count-abc#", List.of(COUNT)))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("不是整数")
                .hasMessageContaining("例：3");
    }

    @Test
    @DisplayName("时间不合法要报出来")
    void momentValidation() {
        assertThatThrownBy(() -> parse("#time-25:99#", List.of(TIME)))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("不是有效时间");
    }

    @Test
    @DisplayName("★ 只写时刻且已过 → 顺延到明天")
    void pastClockRollsToTomorrow() throws Exception {
        CommandParser.Result r = parse("#time-08:00#", List.of(TIME));

        ZonedDateTime at = r.args().moment("time").atZone(AppTime.ZONE);
        assertThat(at.toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 19));
        assertThat(at.getHour()).isEqualTo(8);
    }

    @Test
    @DisplayName("★ 相对时间：3小时后")
    void relativeMoment() throws Exception {
        CommandParser.Result r = parse("#time-3小时后#", List.of(TIME));

        assertThat(r.args().moment("time")).isEqualTo(NOW.plusSeconds(3 * 3600));
    }

    @Test
    @DisplayName("枚举只接受候选值")
    void enumValidation() {
        ParamSpec mode = new ParamSpec("mode", ParamType.ENUM, true, null, 0, "类型", "daily",
                List.of("daily", "once"));

        assertThatThrownBy(() -> parse("#mode-weekly#", List.of(mode)))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("只能是 daily / once");
    }

    @Test
    @DisplayName("文本参数超长按上限截断")
    void textIsTruncated() throws Exception {
        ParamSpec shortText = new ParamSpec("text", ParamType.TEXT, true, null, 3, null, null, List.of());
        CommandParser.Result r = parse("#text-一二三四五#", List.of(shortText));

        assertThat(r.args().raw("text")).isEqualTo("一二三");
    }

    /* ==================== 变量展开 ==================== */

    @Test
    @DisplayName("★ 变量展开发生在类型校验之前：#time-{time}# 先变成 21:30 再按时间解析")
    void variableExpandedBeforeTypeCheck() throws Exception {
        UnaryOperator<String> expand = v -> v.replace("{time}", "21:30");
        CommandParser.Result r = parser.parse("#time-{time}#", List.of(TIME), expand, NOW);

        assertThat(r.args().moment("time"))
                .isEqualTo(ZonedDateTime.of(2026, 10, 18, 21, 30, 0, 0, AppTime.ZONE).toInstant());
        assertThat(r.args().raw("time")).as("模板保留展开后的原样文字").isEqualTo("21:30");
    }

    @Test
    @DisplayName("★ 展开只做一次：展开出来的 {...} 不再被扫一遍")
    void expansionIsSinglePass() throws Exception {
        UnaryOperator<String> expand = v -> v.equals("{a}") ? "{b}" : "不该被再展开";
        CommandParser.Result r = parser.parse("#text-{a}#", List.of(TEXT), expand, NOW);

        assertThat(r.args().raw("text")).isEqualTo("{b}");
    }

    /* ==================== 帮助行的组装 ==================== */

    @Test
    @DisplayName("参数按名字取，顺序无所谓")
    void orderDoesNotMatter() throws Exception {
        CommandParser.Result a = parse("#time-21:30# #date-2026-10-20# 文本", List.of(TIME, DATE, TEXT));
        CommandParser.Result b = parse("#date-2026-10-20# 文本 #time-21:30#", List.of(TIME, DATE, TEXT));

        assertThat(a.args().rawValues()).isEqualTo(b.args().rawValues());
        assertThat(a.text()).isEqualTo(b.text()).isEqualTo("文本");
    }

    @Test
    @DisplayName("井号段里出现 Map 里没有的名字时不吞异常")
    void parseExceptionCarriesField() {
        try {
            parse("#count-abc#", List.of(COUNT));
        } catch (CommandParseException e) {
            assertThat(e.field()).isEqualTo("count");
            return;
        }
        throw new AssertionError("应当抛 CommandParseException");
    }

    @Test
    @DisplayName("CommandArgs 两份值都拿得到")
    void argsExposeBothShapes() throws Exception {
        CommandParser.Result r = parse("#date-2026-10-18#", List.of(DATE));

        Map<String, String> raw = r.args().rawValues();
        assertThat(raw).containsEntry("date", "2026-10-18");
        assertThat(r.args().date("date")).isEqualTo(java.time.LocalDate.of(2026, 10, 18));
    }
}
