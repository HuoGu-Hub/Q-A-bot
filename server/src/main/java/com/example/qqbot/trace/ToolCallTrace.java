package com.example.qqbot.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 一次请求里的工具调用记录 —— 谁调了、传了什么、返回多长、花了多久。
 *
 * <h2>为什么是"开始与结束"两段</h2>
 * 授权钩子只能拿到调用前，返回值与耗时只有真正执行时才看得到。
 * 所以授权只负责记"要调"，执行器包一层负责记"调完了、成功还是失败"。
 *
 * <p>与 {@link LlmUsage} 同一套约定：只有评测执行器显式 {@link #begin()}
 * 之后才记，否则群聊线程池上的列表会跟着线程一直长。
 */
public final class ToolCallTrace {

    private ToolCallTrace() {
    }

    /**
     * @param status      ok / error / denied / unknown
     * @param latencyMs   执行耗时，未执行为 null
     * @param resultChars 返回内容长度，失败为 null
     * @param error       失败原因，成功为 null
     */
    public record Call(int index, String name, String args, String status, Long latencyMs,
                       Integer resultChars, String error) {
    }

    /** 可变条目：授权先建，执行后补全 */
    private static final class Entry {
        final int index;
        final String name;
        final String args;
        String status = "unknown";
        Long latencyMs;
        Integer resultChars;
        String error;

        Entry(int index, String name, String args) {
            this.index = index;
            this.name = name;
            this.args = args;
        }

        Call freeze() {
            return new Call(index, name, args, status, latencyMs, resultChars, error);
        }
    }

    private static final ThreadLocal<List<Entry>> ACTIVE = new ThreadLocal<>();

    public static void begin() {
        ACTIVE.set(new ArrayList<>());
    }

    public static List<Call> end() {
        List<Entry> entries = ACTIVE.get();
        ACTIVE.remove();
        if (entries == null) {
            return List.of();
        }
        return entries.stream().map(Entry::freeze).toList();
    }

    /** 授权放行：先占一个位置，执行器随后补结果 */
    public static void pending(String name, String args) {
        List<Entry> entries = ACTIVE.get();
        if (entries == null || entries.size() >= 32) {
            return;
        }
        entries.add(new Entry(entries.size() + 1, safe(name), safe(args)));
    }

    /** 授权拒绝：记一条 denied，不当成没发生 */
    public static void denied(String name, String args, String reason) {
        List<Entry> entries = ACTIVE.get();
        if (entries == null || entries.size() >= 32) {
            return;
        }
        Entry e = new Entry(entries.size() + 1, safe(name), safe(args));
        e.status = "denied";
        e.error = reason;
        entries.add(e);
    }

    /**
     * 包住一次真正的工具执行：记结果长度与耗时，异常也记下来再抛。
     *
     * <p>找的是**最后一个还没结果的同名条目** —— 模型可能连续调同一个工具，
     * 按名字从后往前配对比"按顺序猜"更不容易错位。
     */
    public static String run(String name, String args, Supplier<String> body) {
        long start = System.nanoTime();
        try {
            String out = body.get();
            settle(name, args, "ok", out, null, start);
            return out;
        } catch (RuntimeException e) {
            settle(name, args, "error", null, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()), start);
            throw e;
        }
    }

    private static void settle(String name, String args, String status, String out, String error,
                               long start) {
        List<Entry> entries = ACTIVE.get();
        if (entries == null) {
            return;
        }
        long ms = Math.max(0, (System.nanoTime() - start) / 1_000_000);
        Entry target = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.name.equals(safe(name)) && "unknown".equals(e.status)) {
                target = e;
                break;
            }
        }
        if (target == null) {
            if (entries.size() >= 32) {
                return;
            }
            target = new Entry(entries.size() + 1, safe(name), safe(args));
            entries.add(target);
        }
        target.status = status;
        target.latencyMs = ms;
        target.resultChars = out == null ? null : out.length();
        target.error = error;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
