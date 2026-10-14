package com.example.qqbot.kb;

import dev.langchain4j.invocation.InvocationContext;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次请求的**检索预算**：时限 + 次数上限。
 *
 * <h2>为什么做在工具里，而不是钩子里</h2>
 * langchain4j 这个版本的 {@code BeforeToolExecution} 钩子是 void 返回的，
 * <b>没法替换工具结果</b>；在那里拦只能抛异常，而模型看到的是"工具坏了"，不是"别查了"。
 * 所以判定放在工具方法里：超预算就**返回一句提示**，模型据此收尾，正常作答。
 *
 * <p>工具方法通过声明 {@code InvocationContext} 参数拿到本对象
 * （langchain4j 会把上下文注入进去，见 {@code DefaultToolExecutor.prepareArguments}）。
 * 取不到就当作"不限制" —— 老调用方或测试不会因为缺参数而失败。
 */
public final class ToolBudget {

    /** 放进 InvocationParameters 的键 */
    public static final String KEY = "tool.budget";

    private final int deadlineSeconds;
    private final int maxCalls;
    private final long startedAt = System.currentTimeMillis();
    private final long deadlineAt;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile String reason = "";

    public ToolBudget(int deadlineSeconds, int maxCalls) {
        this.deadlineSeconds = Math.max(1, deadlineSeconds);
        this.maxCalls = Math.max(1, maxCalls);
        this.deadlineAt = startedAt + this.deadlineSeconds * 1000L;
    }

    /**
     * 申请一次工具调用。超时或超次数返回 false，并在 {@link #reason()} 里给出原因。
     *
     * <p>先判时限再判次数：时限是"体验"的底线，次数是"成本"的底线。
     */
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        if (now >= deadlineAt) {
            reason = "已超过 " + deadlineSeconds + " 秒";
            return false;
        }
        if (calls.get() >= maxCalls) {
            reason = "已调用 " + maxCalls + " 次";
            return false;
        }
        calls.incrementAndGet();
        return true;
    }

    /** 拒绝的原因（给模型看的那句话里会带上） */
    public String reason() {
        return reason;
    }

    public int used() {
        return calls.get();
    }

    public int maxCalls() {
        return maxCalls;
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    /** 从调用上下文里取预算；没有就返回 null（不限制） */
    public static ToolBudget of(InvocationContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            Object v = ctx.invocationParameters() == null ? null : ctx.invocationParameters().get(KEY);
            return v instanceof ToolBudget b ? b : null;
        } catch (Exception e) {
            return null;
        }
    }
}
