package com.example.qqbot.command;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 一条命令这次收到的参数，<b>按名字取</b>。
 *
 * <p>为什么同时留两份值：动作要的是能算的数与时刻，模板要的是群友
 * 打进来的原样文字。{@code 21:30} 归一成 Instant 之后再想打回
 * 「21:30」就得反格式化一遍，而群友可能写的是「晚上9点半」——
 * 原样那份才是他看得懂的那份。
 */
public final class CommandArgs {

    private final Map<String, String> raw;
    private final Map<String, Object> typed;

    public CommandArgs(Map<String, String> raw, Map<String, Object> typed) {
        this.raw = Map.copyOf(raw);
        this.typed = Map.copyOf(typed);
    }

    public static CommandArgs empty() {
        return new CommandArgs(Map.of(), Map.of());
    }

    /** 这个名字这次有没有给值 */
    public boolean has(String name) {
        return raw.containsKey(name);
    }

    /** 原样值，供模板渲染 */
    public String raw(String name) {
        return raw.get(name);
    }

    /** TEXT / ENUM 的值 */
    public String text(String name) {
        Object v = typed.get(name);
        return v instanceof String s ? s : null;
    }

    /** INT 的值 */
    public Long number(String name) {
        Object v = typed.get(name);
        return v instanceof Long n ? n : null;
    }

    /** MOMENT 的值 */
    public Instant moment(String name) {
        Object v = typed.get(name);
        return v instanceof Instant i ? i : null;
    }

    /** DATE 的值 */
    public LocalDate date(String name) {
        Object v = typed.get(name);
        return v instanceof LocalDate d ? d : null;
    }

    /** 全部原样值，渲染 {@code {arg.x}} 用 */
    public Map<String, String> rawValues() {
        return raw;
    }

    public int size() {
        return raw.size();
    }
}
