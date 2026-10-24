package com.example.qqbot.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 参数取值类型。
 *
 * <p>类型只影响两件事：<b>怎么校验</b>、<b>动作拿到什么形状的值</b>。
 * 它不决定参数能不能写在文本里 —— 那是渲染的事。
 *
 * <p>为什么 {@code DATE} 和 {@code MOMENT} 要分开：定时任务里「哪一天」和「几点」
 * 是两个独立的输入，只给日期时不该被硬凑成当天零点。
 */
public enum ParamType {

    /** 任意文本，超长按 {@code maxChars} 截断 */
    TEXT,
    /** 十进制整数 */
    INT,
    /** 时间点：21:30 / 明天21:30 / 10-18 21:30 / 3小时后 */
    MOMENT,
    /** 日期：2026-10-18 / 10-18 / 明天 */
    DATE,
    /** 枚举，取值必须在 {@code options} 里 */
    ENUM,
    /** 重复周期：一次 / 3次 / 每天 / 每周1-7 / 每2-30天；落库为 once / times:3 / daily / weekly:3 / every:5 */
    REPEAT;

    /**
     * 从字符串宽容地转成类型。
     *
     * <p>写错一律回落成 {@link #TEXT}，不抛异常 —— 参数表是后台填的，
     * 一个拼错的类型名不该让整条指令在运行时炸掉，退化成文本至少还能用。
     */
    @JsonCreator
    public static ParamType of(Object raw) {
        if (raw == null) {
            return TEXT;
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "int", "number", "long", "整数" -> INT;
            case "moment", "time", "datetime", "时间" -> MOMENT;
            case "date", "day", "日期" -> DATE;
            case "enum", "options", "枚举" -> ENUM;
            case "repeat", "cycle", "重复", "周期", "重复周期" -> REPEAT;
            default -> TEXT;
        };
    }

    /** 落库与出参用的稳定标识 */
    @JsonValue
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
