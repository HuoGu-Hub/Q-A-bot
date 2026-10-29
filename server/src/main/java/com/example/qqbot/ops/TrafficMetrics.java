package com.example.qqbot.ops;

import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 进程内的流量聚合 —— QPS、延迟分位、错误率。
 *
 * <h2>为什么是进程内，而不是 Prometheus</h2>
 * 服务器只有 2G 内存，再挂一个常驻抓取进程不划算（Prometheus 单机约 60–100MB，
 * 加 Grafana 是 300–400MB）。而我们要回答的问题只有四个：
 * <b>现在多少 QPS、P95 多少、错误率多少、静态请求占多少</b>。
 * 这些一分钟聚合一次就够，落进 SQLite 后历史也在，重启不丢。
 *
 * <h2>为什么延迟只采 API</h2>
 * 静态资源（HTML / JS / 字体）的耗时在 1ms 量级，混进同一个分位数里会把
 * API 的真实延迟稀释成"看起来很好"。所以：
 * <ul>
 *   <li>{@code requests} / {@code api} / {@code errors} —— 全部请求都计数</li>
 *   <li>{@code p50} / {@code p95} / {@code max} —— 只统计 API 请求</li>
 * </ul>
 *
 * <h2>为什么用锁</h2>
 * 一次请求只写一个 long，锁几乎不会被争用；而"环形数组 + 游标"手写无锁
 * 更容易在计数上出错。这里宁可简单 —— 它不是热路径，是每秒几十次的路径。
 */
@Component
public class TrafficMetrics {

    /**
     * 一分钟内的 API 样本上限。
     * 满了一分钟几百次请求是常态，4096 有十倍余量；真满了只丢延迟样本，
     * 计数一个都不丢 —— 分位数偏一点好过 QPS 偏。
     */
    private static final int CAPACITY = 4096;

    private final long[] samples = new long[CAPACITY];
    private int size;
    private long requests;
    private long api;
    private long errors;

    /** 记一次请求。{@code millis} 是本次耗时，{@code status} 是 HTTP 状态码 */
    public void record(long millis, int status, boolean isApi) {
        synchronized (this) {
            requests++;
            if (isApi) {
                api++;
                if (size < CAPACITY) {
                    samples[size++] = Math.max(0L, millis);
                }
            }
            if (status >= 500) {
                errors++;
            }
        }
    }

    /**
     * 取走这一分钟的数据并清零。
     *
     * <p>没有请求时返回 {@code null} —— 空分钟不落行，表里只留"有人来过"的分钟。
     * 前端补零比后端补空行简单。
     */
    public Snapshot drain() {
        synchronized (this) {
            if (requests == 0) {
                return null;
            }
            long[] copy = Arrays.copyOf(samples, size);
            Snapshot snapshot = new Snapshot(
                    requests, api, errors,
                    percentile(copy, 50), percentile(copy, 95), max(copy));
            size = 0;
            requests = 0;
            api = 0;
            errors = 0;
            return snapshot;
        }
    }

    private static int percentile(long[] values, int p) {
        if (values.length == 0) {
            return 0;
        }
        Arrays.sort(values);
        int idx = (int) Math.ceil(p / 100.0 * values.length) - 1;
        return (int) values[Math.max(0, Math.min(values.length - 1, idx))];
    }

    private static int max(long[] values) {
        long max = 0;
        for (long v : values) {
            max = Math.max(max, v);
        }
        return (int) max;
    }

    /** 一分钟的读数 */
    public record Snapshot(long requests, long api, long errors, int p50, int p95, int max) {
    }
}
