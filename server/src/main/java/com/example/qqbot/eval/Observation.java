package com.example.qqbot.eval;

import com.example.qqbot.trace.KbTrace;

import java.util.List;

/**
 * 一次执行里"判定需要看到的事实"。
 *
 * <p>只放判定真的用到的字段：把整条 run 塞进来会让规则判定器悄悄依赖存储层，
 * 以后想换数据来源就得改判定器。
 */
public record Observation(String answer, boolean kbEnabled, List<KbTrace.Hit> hits,
                          double bestCosineRaw, String sources, long totalMs) {
}
