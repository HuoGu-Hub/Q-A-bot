package com.example.qqbot.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多值参数：{@code #时间-7:00、14:00、21:30#}。
 *
 * <p>要钉住两件事：<b>只有声明了可多值的参数才会被切开</b>（否则文本里的顿号会莫名消失），
 * 以及<b>切开之后每个值都要单独过校验</b>。
 */
class ParamMultiValueTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private static ParamSpec moments() {
        return ParamSpec.multi("时间", ParamType.MOMENT, true, null, 0, "每天几点", "21:30");
    }

    private static ParamSpec plainText() {
        return ParamSpec.of("备注", "text", false, "随便写");
    }

    private CommandParser.Result parse(String raw, ParamSpec... specs) throws CommandParseException {
        return new CommandParser().parse(raw, List.of(specs), s -> s, NOW);
    }

    // ==================== 切分 ====================

    @Test
    @DisplayName("★ 三种分隔符都认")
    void threeSeparatorsWork() throws Exception {
        assertThat(parse("#时间-7:00、14:00#", moments()).args().moments("时间")).hasSize(2);
        assertThat(parse("#时间-7:00，14:00#", moments()).args().moments("时间")).hasSize(2);
        assertThat(parse("#时间-7:00,14:00#", moments()).args().moments("时间")).hasSize(2);
    }

    @Test
    @DisplayName("★ 可多值的参数切成几个就是几个，逐个按类型转")
    void eachPieceIsConverted() throws Exception {
        CommandParser.Result r = parse("#时间-7:00、14:00、21:30#", moments());

        assertThat(r.args().moments("时间")).hasSize(3);
        // 三个都是真时刻，不是字符串
        assertThat(r.args().moments("时间")).allMatch(i -> i instanceof Instant);
    }

    @Test
    @DisplayName("★ 不可多值的参数里，顿号仍然是顿号")
    void plainTextKeepsCommas() throws Exception {
        CommandParser.Result r = parse("#备注-苹果、香蕉# 你好", plainText());

        assertThat(r.args().text("备注")).isEqualTo("苹果、香蕉");
        assertThat(r.text()).isEqualTo("你好");
    }

    @Test
    @DisplayName("空片段被丢掉，手抖多打一个逗号不算错")
    void emptyPiecesAreDropped() throws Exception {
        assertThat(parse("#时间-7:00、、14:00#", moments()).args().moments("时间")).hasSize(2);
    }

    @Test
    @DisplayName("值两端的空白被去掉")
    void piecesAreTrimmed() throws Exception {
        CommandParser.Result r = parse("#时间- 7:00 、 14:00 #", moments());

        assertThat(r.args().moments("时间")).hasSize(2);
    }

    @Test
    @DisplayName("只有一个值也返回单元素列表，动作侧不用分两种情况")
    void singleValueStillReturnsList() throws Exception {
        assertThat(parse("#时间-21:30#", moments()).args().moments("时间")).hasSize(1);
    }

    @Test
    @DisplayName("没给值就是空列表，不是 null")
    void missingMeansEmptyList() throws Exception {
        CommandParser.Result r = parse("只有文本", plainText());

        assertThat(r.args().moments("时间")).isEmpty();
        assertThat(r.args().text("备注")).isNull();
    }

    // ==================== 校验 ====================

    @Test
    @DisplayName("★ 哪个值不对就指名道姓说是哪一个")
    void badPieceIsNamed() {
        assertThatThrownBy(() -> parse("#时间-7:00、25:99#", moments()))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("25:99");
    }

    @Test
    @DisplayName("★ 超过十个值直接拒绝")
    void tooManyValuesRejected() {
        String many = "#时间-" + "21:30、".repeat(10) + "21:30#";   // 11 个

        assertThatThrownBy(() -> parse(many, moments()))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("最多 10 个");
    }

    @Test
    @DisplayName("正好十个值可以通过")
    void exactlyTenIsAllowed() throws Exception {
        String ten = "#时间-" + "21:30、".repeat(9) + "21:30#";

        assertThat(parse(ten, moments()).args().moments("时间")).hasSize(10);
    }

    @Test
    @DisplayName("只有逗号、一个值都没有 → 报错，不静默变成空")
    void onlySeparatorsRejected() {
        assertThatThrownBy(() -> parse("#时间-、、#", moments()))
                .isInstanceOf(CommandParseException.class);
    }

    // ==================== 与原样值的关系 ====================

    @Test
    @DisplayName("{arg.时间} 显示的是群友打的那一串，不是拆开后的某一个")
    void rawKeepsTheOriginal() throws Exception {
        CommandParser.Result r = parse("#时间-7:00、14:00#", moments());

        assertThat(r.args().raw("时间")).isEqualTo("7:00、14:00");
    }

    // ==================== 落库形状 ====================

    @Test
    @DisplayName("★ ParamSpec 能带着 multiple 原样存进库再读出来")
    void specRoundTripsThroughJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(moments());
        assertThat(json).contains("\"multiple\":true");

        ParamSpec back = mapper.readValue(json, ParamSpec.class);
        assertThat(back.multiple()).isTrue();
        assertThat(back.name()).isEqualTo("时间");
        assertThat(back.type()).isEqualTo(ParamType.MOMENT);
    }

    @Test
    @DisplayName("老参数表里没有 multiple 字段，读回来是单值")
    void legacyJsonWithoutMultipleDefaultsToSingle() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"name\":\"time\",\"type\":\"moment\",\"required\":true}";

        ParamSpec spec = mapper.readValue(json, ParamSpec.class);

        assertThat(spec.multiple()).isFalse();
        assertThat(spec.type()).isEqualTo(ParamType.MOMENT);
    }
}
