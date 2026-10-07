package com.example.qqbot.kb;

import java.util.List;

/**
 * 聚合型提问的意图识别 —— "X有什么" 这类问题的**答案是一整个地点，不是某件物品**。
 *
 * <h2>为什么需要它（实测）</h2>
 * 问「空洞大厅有什么」，top-20 的实测排名是：
 * <pre>
 *   1. 0.9701  空洞大厅展示柜（3个小槽位和2个中槽位）
 *   2. 0.9685  空洞大厅方块
 *   ...
 *   6. 0.9581  空洞大厅展示柜（2个中槽位）
 *   7. 0.9558  ★ 空洞大厅（Hollow Halls）      ← 真正该排第一的地点块
 * </pre>
 * 地点块**没有丢**，它排第 7，被 6 条"名字里带空洞大厅"的物品挤出了前五。
 * 关键：第 1 名 0.9701 与地点块 0.9558 **只差 0.014** ——
 * 所以任何阈值都分不开它们（这又一次证明"调 min-score"不是解法），
 * 这是**意图**问题：问的是"那里有什么"，回答却给了"某个东西"。
 *
 * <h2>做法：只改顺序，不改分数</h2>
 * 识别出聚合意图后，把**已经在候选集里**的地点块提到最前。
 * 不造分、不虚高 —— 只是承认"这个提问要的是地点块"。
 * 候选集本来就是 20 条（rerank 的候选上限），所以地点块通常已经在里面。
 */
public final class PlaceIntent {

    /** 聚合型提问的标记词，按长度从长到短匹配（长标记优先，避免被短标记截断） */
    private static final List<String> AGGREGATE_MARKERS = List.of(
            "有什么东西", "里面有什么", "里有什么", "都有什么", "有什么", "有啥", "有哪些");

    /** 地点词后面常见的方位/语气助词，取地点名时要剥掉 */
    private static final String TRAILING_PARTICLES = "里内中面那这的地了";

    /**
     * 地点语料的 {@code docId} —— **由核心定义，生产者引用**（2026-10-06 从
     * {@code LocationCorpusBuilder.DOC_ID} 挪过来）。
     *
     * <p>原来定义在地图生产者里，于是核心的检索器为了读这一个字符串，
     * 反向 import 了 {@code kb.map.LocationCorpusBuilder} —— 方向反了：
     * 边界是【生产者 → 核心】单向，核心不该认识任何一个生产者
     * （ArchitectureTest 有规则盯着）。它其实是**契约**（"哪些块是地点块"），
     * 契约该由消费它的一方定义。
     */
    public static final String PLACE_DOC_ID = "地图·地点";

    private PlaceIntent() {
    }

    /** 是不是"某地有什么"这种聚合型提问 */
    public static boolean isAggregate(String query) {
        return placeTerm(query) != null;
    }

    /**
     * 从提问里取地点词。取不到返回 {@code null}。
     *
     * <pre>
     *   空洞大厅有什么   -> 空洞大厅
     *   空洞大厅里有什么 -> 空洞大厅   （剥掉方位助词"里"）
     *   在哪             -> null
     *   有什么           -> null      （没有地点词，不猜）
     * </pre>
     */
    public static String placeTerm(String query) {
        if (query == null) {
            return null;
        }
        String q = query.strip();
        int cut = -1;
        for (String marker : AGGREGATE_MARKERS) {
            int i = q.indexOf(marker);
            if (i >= 0 && (cut < 0 || i < cut)) {
                cut = i;
            }
        }
        if (cut <= 0) {
            return null;
        }
        String place = q.substring(0, cut).strip();
        // 从尾部剥掉方位/语气助词
        while (!place.isEmpty() && TRAILING_PARTICLES.indexOf(place.charAt(place.length() - 1)) >= 0) {
            place = place.substring(0, place.length() - 1).strip();
        }
        return place.isEmpty() ? null : place;
    }

    /**
     * 在候选里找"这个地点的块"，返回它的下标；找不到返回 -1。
     *
     * <p>判据三条同时成立：属于地点语料（docId 前缀）、标题含地点词、**不是第一条**（已在最前就不动）。
     */
    public static int findPlaceBlock(List<String> docIds, List<String> titles, String placeTerm, String locationDocId) {
        if (placeTerm == null || placeTerm.isBlank()) {
            return -1;
        }
        for (int i = 1; i < titles.size(); i++) {
            String docId = docIds.get(i);
            String title = titles.get(i);
            if (docId == null || title == null) {
                continue;
            }
            if (docId.startsWith(locationDocId) && title.contains(placeTerm)) {
                return i;
            }
        }
        return -1;
    }
}
