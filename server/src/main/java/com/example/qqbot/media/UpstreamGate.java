package com.example.qqbot.media;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/**
 * 对上游的**出站闸门** —— 串行、固定间隔、撞限流就暂停。
 *
 * <h2>为什么要从"自适应漂移"改成"固定速率"</h2>
 * 原先是 300ms 起、撞一次 429 就翻倍、顺利再慢慢收回。实测这套在真实限流面前
 * 只会来回抖：对方（Cloudflare）一旦开始限流，我们就在"翻倍 → 收到 → 又撞"之间循环，
 * 日志每张图刷两条，而速度并没有变快。
 *
 * <p>现在改成：**间隔是配置里的固定值**，429 只是把间隔往上顶一点，顶到连击上限就
 * <b>整条链路暂停</b>。暂停期间一次请求都不发 —— 对方已经在说"别来了"，
 * 继续敲只会把限流窗口拉得更长。
 *
 * <h2>实测的"友好速度"</h2>
 * 2026-10-15 直接探过对方：连两个从未取过的瓦片、间隔 6 秒，仍然都是 429，
 * 响应来自 Cloudflare（{@code Server: cloudflare}），且**没有 Retry-After**。
 * 也就是说这已经不是"每秒几次"的简单速率问题，而是被记上了一段时间。
 * 所以默认间隔取 {@code 6 秒}（约 10 次/分钟），并且靠暂停来真正止损。
 *
 * <h2>为什么住在 {@code media}，而且全局只有一个</h2>
 * 地图瓦片与 wiki 配图打的是**同一个上游**。两边各开一个闸门时，各自都觉得自己很克制，
 * 合起来却正好把额度打穿 —— 2026-10-15 的 429 就是这么来的。
 * 所以闸门收在 media，由 {@code config.UpstreamConfig} 建**唯一一个** Bean，
 * 两个抓图路径共用同一把信号量、同一个间隔、同一个暂停窗口。
 */
public final class UpstreamGate {

    /** 限流统计的汇总窗口：这期间只说一句话，而不是每张图两条 */
    private static final long SUMMARY_WINDOW_MS = 5 * 60_000;

    /** 间隔的上限：撞狠了也不能慢到"一张图等一分钟" */
    public static final long MAX_GAP_MS = 30_000;

    private final Semaphore gate = new Semaphore(1);
    /** 基准间隔 —— 用取值函数而不是固定值，**配置中心改了立刻生效**，不用重启 */
    private final java.util.function.LongSupplier baseGap;
    private final java.util.function.LongSupplier pauseMs;
    private final java.util.function.IntSupplier streakLimit;
    private final long summaryWindowMs;

    private volatile long gapMs;
    private volatile long pausedUntil;
    private volatile int streak;

    /** 只在闸门内读写 */
    private long lastRequestAt;

    private long windowAt = System.currentTimeMillis();
    private long windowOk;
    private long windowLimited;

    public UpstreamGate(long baseGapMs, long pauseMs, int streakLimit) {
        this(baseGapMs, pauseMs, streakLimit, SUMMARY_WINDOW_MS);
    }

    /** 定值版（测试用）：生产走取值函数，好让配置中心改完立刻生效 */
    public UpstreamGate(long baseGapMs, long pauseMs, int streakLimit, long summaryWindowMs) {
        this(() -> baseGapMs, () -> pauseMs, () -> streakLimit, summaryWindowMs);
    }

    public UpstreamGate(java.util.function.LongSupplier baseGap, java.util.function.LongSupplier pauseMs,
                 java.util.function.IntSupplier streakLimit) {
        this(baseGap, pauseMs, streakLimit, SUMMARY_WINDOW_MS);
    }

    /** 汇总窗口可传是为了能测 —— 生产用 {@link #SUMMARY_WINDOW_MS}，测试传几十毫秒 */
    public UpstreamGate(java.util.function.LongSupplier baseGap, java.util.function.LongSupplier pauseMs,
                 java.util.function.IntSupplier streakLimit, long summaryWindowMs) {
        this.baseGap = baseGap;
        this.pauseMs = pauseMs;
        this.streakLimit = streakLimit;
        this.summaryWindowMs = Math.max(1, summaryWindowMs);
        this.gapMs = baseGapMs();
    }

    /** 当前基准间隔。每次读都取最新配置 */
    private long baseGapMs() {
        return Math.max(50, baseGap.getAsLong());
    }

    private long pauseMs() {
        return Math.max(0, pauseMs.getAsLong());
    }

    private int streakLimit() {
        return Math.max(1, streakLimit.getAsInt());
    }

    /** 一段窗口里的限流统计 */
    public record Stats(long ok, long limited, long gapMs, int streak) {
    }

    /**
     * 进闸门并补齐间隔。
     *
     * <p>睡觉时**不持锁**：闸门在 {@link #leave()} 才放行，但等待发生在锁外，
     * 否则一个慢请求会把所有请求一起卡住。
     */
    public void enter() throws IOException {
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待出站闸门被中断", e);
        }
        long wait = gapMs - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    public void leave() {
        gate.release();
    }

    /** 一次成功：清掉连击，间隔慢慢收回基准值 */
    public void onSuccess() {
        windowOk++;
        streak = 0;
        // 间隔慢慢收回**当前**基准值 —— 后台刚把基准调大时，这里也会跟着往上收
        long base = baseGapMs();
        if (gapMs > base) {
            gapMs = Math.max(base, gapMs - 500);
        } else if (gapMs < base) {
            gapMs = base;
        }
    }

    /**
     * 一次 429。
     *
     * @return true = 这次把链路推进了暂停（调用方据此只打一条 WARN）
     */
    public boolean onRateLimited() {
        windowLimited++;
        streak++;
        long pause = pauseMs();
        if (pause > 0 && streak >= streakLimit()) {
            pausedUntil = System.currentTimeMillis() + pause;
            streak = 0;
            gapMs = baseGapMs();
            return true;
        }
        gapMs = Math.min(MAX_GAP_MS, gapMs * 2);
        return false;
    }

    /**
     * 现在是不是处于暂停中。
     *
     * <p>读的时候顺手判到期并复位 —— 调用方不需要另开一个定时器来"解除暂停"。
     */
    public boolean paused() {
        if (pausedUntil == 0) {
            return false;
        }
        if (System.currentTimeMillis() < pausedUntil) {
            return true;
        }
        pausedUntil = 0;
        streak = 0;
        gapMs = baseGapMs();
        return false;
    }

    public long pauseRemainingMs() {
        return Math.max(0, pausedUntil - System.currentTimeMillis());
    }

    public long gapMs() {
        return gapMs;
    }

    public int streak() {
        return streak;
    }

    /**
     * 到点且这期间发生过限流时返回一份统计，否则 null。
     *
     * <p>调用方据此做到"**五分钟只说一句**"，而不是每张图两条日志。
     */
    public Stats summaryIfDue() {
        long now = System.currentTimeMillis();
        if (now - windowAt < summaryWindowMs) {
            return null;
        }
        Stats stats = windowLimited == 0 ? null : new Stats(windowOk, windowLimited, gapMs, streak);
        windowAt = now;
        windowOk = 0;
        windowLimited = 0;
        return stats;
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待出站间隔被中断", e);
        }
    }
}
