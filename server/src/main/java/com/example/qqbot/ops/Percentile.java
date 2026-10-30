package com.example.qqbot.ops;

import java.util.Arrays;

/**
 * 分位数计算 —— 流量与模型调用共用同一份实现。
 *
 * <p>为什么不各写一份：分位数的下标差一位（{@code ceil(p/100*n)-1} 还是 {@code floor}），
 * P95 就会报成"等于中位数"或者"比最大值还大"。两处各写一份，迟早只改一处。
 *
 * <p>入参数组会被排序 —— 调用方传进来的都是 {@code Arrays.copyOf} 出来的快照，
 * 不是活着的采集缓冲。
 */
final class Percentile {

    private Percentile() {
    }

    static int of(long[] values, int p) {
        if (values.length == 0) {
            return 0;
        }
        Arrays.sort(values);
        int idx = (int) Math.ceil(p / 100.0 * values.length) - 1;
        return (int) values[Math.max(0, Math.min(values.length - 1, idx))];
    }

    static int max(long[] values) {
        long max = 0;
        for (long v : values) {
            max = Math.max(max, v);
        }
        return (int) max;
    }
}
