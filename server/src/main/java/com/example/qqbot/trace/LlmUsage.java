package com.example.qqbot.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次调用链里模型用量的收集器。
 *
 * <h2>为什么是"打开才记"</h2>
 * 群聊请求跑在固定线程池上，若默认收集，列表会跟着线程一直长下去 ——
 * 那是一个不会报错、只会慢慢吃内存的泄漏。所以只有评测执行器显式 {@link #begin()}
 * 之后才记，且必须在 finally 里 {@link #end()}。
 *
 * <h2>为什么不放在 llm 包</h2>
 * 记录方是 {@code LlmRouter}，读取方是评测执行器。放在中性的 trace 包，
 * 谁都不用为了一个计数器反向依赖另一个业务包。
 */
public final class LlmUsage {

    private LlmUsage() {
    }

    /** 一次模型往返的用量 */
    public record Call(String provider, String model, long tokensIn, long tokensOut) {
    }

    private static final ThreadLocal<List<Call>> ACTIVE = new ThreadLocal<>();

    /** 开始收集：评测执行一次用例之前调用 */
    public static void begin() {
        ACTIVE.set(new ArrayList<>());
    }

    /** 结束收集并取走结果；没开始时返回空表 */
    public static List<Call> end() {
        List<Call> calls = ACTIVE.get();
        ACTIVE.remove();
        return calls == null ? List.of() : List.copyOf(calls);
    }

    /** 记一次往返。没有开始收集时什么也不做 */
    public static void record(String provider, String model, long tokensIn, long tokensOut) {
        List<Call> calls = ACTIVE.get();
        if (calls == null) {
            return;
        }
        // 单次回答最多几轮工具调用，32 足够；超出说明调用链异常，保留前 32 条即可
        if (calls.size() < 32) {
            calls.add(new Call(provider, model, Math.max(0, tokensIn), Math.max(0, tokensOut)));
        }
    }

    public static long totalIn(List<Call> calls) {
        return calls.stream().mapToLong(Call::tokensIn).sum();
    }

    public static long totalOut(List<Call> calls) {
        return calls.stream().mapToLong(Call::tokensOut).sum();
    }
}
