package com.example.qqbot.command;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 一条命令的参数声明。
 *
 * <p>它是这条命令的<b>契约</b>：解析、校验、帮助文档、模板取值、后台编辑
 * 全从它来。所以它必须能原样落库、原样读回，中间不做任何形状转换。
 *
 * @param name         井号里的变量名，中英皆可。不能含减号、井号、空白
 * @param type         取值类型
 * @param required     缺了是否算失败
 * @param defaultValue 可选参数的默认值；为空表示没有默认值
 * @param maxChars     文本长度上限，仅对 TEXT 有意义；≤0 时取默认值
 * @param description  帮助里那一行说明
 * @param example      用法提示与帮助里的样例
 * @param options      ENUM 的候选值
 * @param multiple     能不能一次给多个值。开了之后 {@code #时间-7:00、14:00#}
 *                     会被切成两个值逐个校验；不开就整段当一个值，
 *                     所以文本参数里的顿号仍然是顿号
 */
public record ParamSpec(
        String name,
        ParamType type,
        boolean required,
        @JsonProperty("default") String defaultValue,
        int maxChars,
        String description,
        String example,
        List<String> options,
        boolean multiple) {

    /** 文本参数的默认长度上限 —— 与出站单条上限同一个量级，防止一条参数把群刷屏 */
    public static final int DEFAULT_MAX_CHARS = 200;

    /** 一个参数最多收几个值。再多就该拆成两条命令了 */
    public static final int MAX_VALUES = 10;

    public ParamSpec {
        name = name == null ? "" : name.trim();
        type = type == null ? ParamType.TEXT : type;
        maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 老写法的兼容构造：单值 */
    public ParamSpec(String name, ParamType type, boolean required, String defaultValue,
                     int maxChars, String description, String example, List<String> options) {
        this(name, type, required, defaultValue, maxChars, description, example, options, false);
    }

    /** 便捷构造：可多值、无候选值 */
    public static ParamSpec multi(String name, ParamType type, boolean required, String defaultValue,
                                  int maxChars, String description, String example) {
        return new ParamSpec(name, type, required, defaultValue, maxChars, description, example,
                List.of(), true);
    }

    /** 便捷构造：不带默认值与候选值 */
    public static ParamSpec of(String name, String type, boolean required, String example) {
        return new ParamSpec(name, ParamType.of(type), required, null, 0, null, example, List.of());
    }

    public boolean hasDefault() {
        return defaultValue != null && !defaultValue.isBlank();
    }
}
