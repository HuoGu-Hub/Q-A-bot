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
 */
public record ParamSpec(
        String name,
        ParamType type,
        boolean required,
        @JsonProperty("default") String defaultValue,
        int maxChars,
        String description,
        String example,
        List<String> options) {

    /** 文本参数的默认长度上限 —— 与出站单条上限同一个量级，防止一条参数把群刷屏 */
    public static final int DEFAULT_MAX_CHARS = 200;

    public ParamSpec {
        name = name == null ? "" : name.trim();
        type = type == null ? ParamType.TEXT : type;
        maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 便捷构造：不带默认值与候选值 */
    public static ParamSpec of(String name, String type, boolean required, String example) {
        return new ParamSpec(name, ParamType.of(type), required, null, 0, null, example, List.of());
    }

    public boolean hasDefault() {
        return defaultValue != null && !defaultValue.isBlank();
    }
}
