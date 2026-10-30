package com.example.qqbot.ops;

import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 进程内的大模型调用聚合 —— 次数、失败、token、延迟。
 *
 * <h2>为什么记在进程内</h2>
 * 和 {@link TrafficMetrics} 同一个理由：服务器内存紧张，不再挂一个指标服务。
 * 每分钟落一行到 SQLite，历史就在库里。
 *
 * <h2>为什么按「调用」而不是按「回答」计数</h2>
 * 一次群聊回答可能包含多轮工具调用，每一轮都是一次真实的计费请求。
 * 按回答计数会系统性低估成本 —— 而成本正是这块要回答的问题。
 */
@Component
public class LlmMetrics {

    /** 一分钟内的调用样本上限。一次回答 2~4 轮，一分钟几十次是常态 */
    private static final int CAPACITY = 4096;

    private final long[] samples = new long[CAPACITY];
    private int size;
    private long calls;
    private long failures;
    private long tokensIn;
    private long tokensOut;

    /**
     * 记一次模型调用。
     *
     * @param millis   这一次往返的耗时
     * @param ok       是否成功（失败包含超时、空回复、厂商报错）
     * @param inTokens 输入 token；厂商没回用量时传 0
     * @param outTokens 输出 token
     */
    public void record(long millis, boolean ok, long inTokens, long outTokens) {
        synchronized (this) {
            calls++;
            if (!ok) {
                failures++;
            }
            this.tokensIn += Math.max(0L, inTokens);
            this.tokensOut += Math.max(0L, outTokens);
            if (size < CAPACITY) {
                samples[size++] = Math.max(0L, millis);
            }
        }
    }

    /** 取走这一分钟的读数并清零；没有调用时返回 {@code null} */
    public Snapshot drain() {
        synchronized (this) {
            if (calls == 0) {
                return null;
            }
            long[] copy = Arrays.copyOf(samples, size);
            Snapshot snapshot = new Snapshot(calls, failures, tokensIn, tokensOut,
                    Percentile.of(copy, 50), Percentile.of(copy, 95), Percentile.max(copy));
            size = 0;
            calls = 0;
            failures = 0;
            tokensIn = 0;
            tokensOut = 0;
            return snapshot;
        }
    }

    /** 一分钟的读数 */
    public record Snapshot(long calls, long failures, long tokensIn, long tokensOut,
                           int p50, int p95, int max) {
    }
}
