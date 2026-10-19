package com.example.qqbot.command;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /** TEXT / ENUM 的值。可多值时取第一个 */
    public String text(String name) {
        List<String> all = texts(name);
        return all.isEmpty() ? null : all.get(0);
    }

    /** INT 的值。可多值时取第一个 */
    public Long number(String name) {
        List<Long> all = numbers(name);
        return all.isEmpty() ? null : all.get(0);
    }

    /** MOMENT 的值。可多值时取第一个 */
    public Instant moment(String name) {
        List<Instant> all = moments(name);
        return all.isEmpty() ? null : all.get(0);
    }

    /** DATE 的值。可多值时取第一个 */
    public LocalDate date(String name) {
        List<LocalDate> all = dates(name);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 多值取法。单值参数也会返回一个元素的列表，动作侧不用分两种情况写。
     *
     * <p>返回的永远是列表而不是 null，少一层判空 —— 空列表的语义就是"没给"。
     */
    public List<Instant> moments(String name) {
        return collect(name, Instant.class);
    }

    public List<LocalDate> dates(String name) {
        return collect(name, LocalDate.class);
    }

    public List<Long> numbers(String name) {
        return collect(name, Long.class);
    }

    public List<String> texts(String name) {
        return collect(name, String.class);
    }

    /**
     * 把某个名字下的值收成列表。
     *
     * <p>单值参数在 {@code typed} 里存的是裸对象，多值参数存的是列表，
     * 这里把两种形状抹平。类型对不上的元素直接跳过，不抛异常 ——
     * 动作侧按类型取，取不到就是空，比在运行时炸掉好。
     */
    private <T> List<T> collect(String name, Class<T> type) {
        Object v = typed.get(name);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            List<T> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                if (type.isInstance(item)) {
                    out.add(type.cast(item));
                }
            }
            return List.copyOf(out);
        }
        return type.isInstance(v) ? List.of(type.cast(v)) : List.of();
    }

    /** 全部原样值，渲染 {@code {arg.x}} 用 */
    public Map<String, String> rawValues() {
        return raw;
    }

    public int size() {
        return raw.size();
    }
}
