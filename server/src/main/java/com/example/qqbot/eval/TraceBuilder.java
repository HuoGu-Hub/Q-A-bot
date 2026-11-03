package com.example.qqbot.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次执行的 span 收集器。
 *
 * <p>执行器在拿到各阶段的耗时之后**声明式**地补 span，不在链路里到处插探针 ——
 * 代价是拿不到"工具调用内部"这种细粒度，好处是不改动生产链路的任何一行。
 * 等 {@code LlmRouter} 的 usage 与工具调用落库做完，再把来源换成实时埋点，
 * 视图与表结构都不用动。
 */
public final class TraceBuilder {

    private final List<EvalSpan> spans = new ArrayList<>();
    private int next = 1;

    /**
     * 追加一个 span。
     *
     * @param parentSeq 父节点序号，根传 0
     * @return 新 span 的序号，供子节点引用
     */
    public int add(int parentSeq, String kind, String name, String status,
                   long startOffsetMs, long durationMs, Map<String, Object> attrs, String errorCode) {
        int seq = next++;
        Map<String, Object> safe = attrs == null ? Map.of() : new LinkedHashMap<>(attrs);
        spans.add(new EvalSpan(seq, parentSeq, kind, name, status,
                Math.max(0, startOffsetMs), Math.max(0, durationMs), safe, errorCode));
        return seq;
    }

    /** 便捷重载：不带错误码 */
    public int add(int parentSeq, String kind, String name, String status,
                   long startOffsetMs, long durationMs, Map<String, Object> attrs) {
        return add(parentSeq, kind, name, status, startOffsetMs, durationMs, attrs, null);
    }

    public List<EvalSpan> spans() {
        return List.copyOf(spans);
    }
}
