package com.example.qqbot.eval;

import java.util.Map;

/**
 * 评测过程中的一个 span —— 过程视图的最小单位。
 *
 * <p>为什么不用现成的 {@code KbTrace}：那份摘要是扁平的，只覆盖检索，表达不了
 * "谁在谁里面、各花了多久"。过程页要的是时间轴，而时间轴必须有父子与时序。
 *
 * <p>字段刻意保持纯数据：序号即顺序，父节点用序号引用，属性进 JSON。
 * 这样落库只需要一张扁平表，回放时按 parent 重建成树。
 *
 * @param seq           同一 run 内的顺序，从 1 开始；1 号固定是根 request
 * @param parentSeq     父节点序号，根为 0
 * @param startOffsetMs 相对 run 起点的毫秒偏移
 */
public record EvalSpan(int seq, int parentSeq, String kind, String name, String status,
                       long startOffsetMs, long durationMs,
                       Map<String, Object> attrs, String errorCode) {
}
