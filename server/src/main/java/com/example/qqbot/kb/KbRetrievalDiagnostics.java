package com.example.qqbot.kb;

import java.util.List;

/**
 * 一次检索的<b>过程记录</b> —— 官方管线的"只回传 Content"逼出来的一个落点。
 *
 * <p><b>为什么需要它</b>：{@code DefaultRetrievalAugmentor.augment()} 只回传
 * {@code List<Content>}，而我们需要的远不止最终那几条：
 * <ul>
 *   <li>各路候选数（{@code vectorCandidates} / {@code keywordCandidates}）—— 问答统计要看</li>
 *   <li>卡阈值<b>之前</b>的最高余弦 —— 调 min-score 全靠它，
 *       而它<b>不可能</b>从"过完阈值的结果"里反推出来</li>
 *   <li>最终命中的内部形状（带 entry 与 source）—— 官方 Content 装不下这些</li>
 * </ul>
 *
 * <p>所以它是一次检索内的可变记录本：各检索器与聚合器往里写，编排层读它并组装
 * {@link KbRetriever.Retrieval}。**一次检索一个实例**，不跨请求共享，
 * 因此不需要同步 —— 它跟着 {@link KbRetrievalContext} 走。
 */
public final class KbRetrievalDiagnostics {

    private int vectorCandidates;
    private int keywordCandidates;
    private double bestCosineRaw;

    /**
     * 本次检索的起止时刻（纳秒）。{@code 0} = 没记。
     *
     * <p><b>为什么由容器自己记</b>：官方管线驱动时检索发生在 augmentor 内部，
     * 调用方<b>没法</b>在两侧掐表 —— 它只能在模型调用返回之后拿到容器，
     * 那时掐出来的时间是"检索 + 模型"，把模型耗时算进检索耗时是错的。
     * 所以由入口适配器记开始、聚合器记结束，量的才是检索本身。
     */
    private long startedAtNanos;
    private long endedAtNanos;
    private List<KbRetriever.Hit> vectorHits = List.of();
    private List<KbRetriever.Hit> keywordHits = List.of();
    private KbRetriever.TitleGate gate = KbRetriever.TitleGate.NONE;
    private List<KbRetriever.Hit> finalHits = List.of();

    public int vectorCandidates() {
        return vectorCandidates;
    }

    public int keywordCandidates() {
        return keywordCandidates;
    }

    /** 卡阈值之前的最高余弦 */
    public double bestCosineRaw() {
        return bestCosineRaw;
    }

    public List<KbRetriever.Hit> vectorHits() {
        return vectorHits;
    }

    public List<KbRetriever.Hit> keywordHits() {
        return keywordHits;
    }

    public KbRetriever.TitleGate gate() {
        return gate;
    }

    public List<KbRetriever.Hit> finalHits() {
        return finalHits;
    }

    void recordVector(List<KbRetriever.Hit> hits, double rawBest) {
        this.vectorHits = hits == null ? List.of() : hits;
        this.vectorCandidates = this.vectorHits.size();
        this.bestCosineRaw = rawBest;
    }

    void recordKeyword(List<KbRetriever.Hit> hits) {
        this.keywordHits = hits == null ? List.of() : hits;
        this.keywordCandidates = this.keywordHits.size();
    }

    void recordGate(KbRetriever.TitleGate gate) {
        this.gate = gate == null ? KbRetriever.TitleGate.NONE : gate;
    }

    /** 入口适配器开始建上下文 —— 由 {@link KbContextQueryTransformer} 调 */
    void recordStart() {
        startedAtNanos = System.nanoTime();
        endedAtNanos = 0;
    }

    /** 检索耗时（毫秒）。起止任一没记就返回 0，不编一个数 */
    public long elapsedMs() {
        if (startedAtNanos == 0 || endedAtNanos == 0) {
            return 0;
        }
        return (endedAtNanos - startedAtNanos) / 1_000_000;
    }

    void recordFinal(List<KbRetriever.Hit> hits) {
        // 聚合器是管线最后一格，到这里检索就算走完了
        endedAtNanos = System.nanoTime();
        this.finalHits = hits == null ? List.of() : hits;
    }
}
