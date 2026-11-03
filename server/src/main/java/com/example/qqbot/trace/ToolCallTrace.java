package com.example.qqbot.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次请求里模型调用了哪些工具。
 *
 * <p>与 {@link LlmUsage} 同一套"打开才记"的约定：群聊线程池长期存活，
 * 默认收集会跟着线程一直长。评测执行器显式 begin 之后才记。
 *
 * <p>只记得了"调了什么、传了什么"，拿不到返回值与耗时 ——
 * 框架只提供了执行前钩子。结果留空而不是编一个：Agent 维度先能回答
 * "工具选对没有"，"返回对不对"要等执行器包装。
 */
public final class ToolCallTrace {

    private ToolCallTrace() {
    }

    public record Call(int index, String name, String args) {
    }

    private static final ThreadLocal<List<Call>> ACTIVE = new ThreadLocal<>();

    public static void begin() {
        ACTIVE.set(new ArrayList<>());
    }

    public static List<Call> end() {
        List<Call> calls = ACTIVE.get();
        ACTIVE.remove();
        return calls == null ? List.of() : List.copyOf(calls);
    }

    public static void record(String name, String args) {
        List<Call> calls = ACTIVE.get();
        if (calls == null || calls.size() >= 32) {
            return;
        }
        calls.add(new Call(calls.size() + 1, name == null ? "" : name, args == null ? "" : args));
    }
}
