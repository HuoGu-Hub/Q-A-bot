package com.example.qqbot.qa;

import java.util.List;

/**
 * 一次问答要落库的全部内容。
 *
 * <p>分成三组，对应两层存储：
 * <ul>
 *   <li><b>派生层</b>（永久）：时间、群、用户、检索指标、耗时、标注</li>
 *   <li><b>原始层</b>（有保留期）：{@code question} / {@code quoteText} / {@code answer}</li>
 *   <li><b>关键词</b>（永久）：从问题里抽出来的游戏名词</li>
 * </ul>
 */
public record QaRecord(
        String ts,
        long groupId,
        long userId,
        long messageId,

        // ---- 原始层：到期会删 ----
        String question,
        String quoteText,
        String answer,

        // ---- 派生层：永久保留 ----
        int imageCount,
        boolean kbEnabled,
        int hitCount,
        double topScore,
        /** 最高**余弦相似度** —— ⚠️ 卡完 min-score 阈值之后的值，未命中时恒为 0 */
        double bestCosine,
        /** 卡阈值**之前**的最高余弦 —— 调 min-score 看这个：未命中时能区分"库里没资料"和"差一点被挡" */
        double bestCosineRaw,
        String sources,
        String retrievedJson,
        String guardAction,
        long retrieveMs,
        long llmMs,
        long totalMs,
        String model,

        // ---- 关键词：永久保留 ----
        List<Keyword> keywords
) {

    /**
     * 一个问题里命中的游戏名词。
     *
     * @param zh   中文名（如「废料杯」）
     * @param en   术语表里对应的英文（如 Scrap Cup）
     * @param inKb 这个英文名是否出现在本次检索到的资料里 —— 用来区分
     *             "术语表里有但知识库没检索到"和"检索到了但没答好"
     */
    public record Keyword(String zh, String en, boolean inKb) {
    }
}
