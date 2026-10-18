package com.example.qqbot.command;

/**
 * 参数解析失败。
 *
 * <p>带上 {@link #field()} 是为了回执能指名道姓地说「是 #time# 这一项不对」，
 * 而不是笼统一句「格式错误」—— 群友不知道改哪儿。
 */
public class CommandParseException extends Exception {

    private final String field;

    public CommandParseException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** 出问题的参数名；不是某一个参数的问题时为 null */
    public String field() {
        return field;
    }
}
