package com.example.qqbot.kb;

import java.util.List;

/**
 * 重排器 —— 用 cross-encoder 给候选重新打分。
 *
 * <p><b>它是唯一会用绝对分说"这次库里没有资料"的一道</b>：双塔余弦在中文短句上
 * 基线高、方差小，"该答"与"库里根本没有"两类完全重叠，拿它卡阈值是死路。
 *
 * <p><b>失败语义是 fail-open</b>：重排挂了就保持原顺序照常回答（最多排序差一点），
 * 绝不因为"锦上添花的一层"失败而不回答。
 */
public interface KbReranker {

    /** 这一路现在能不能用（没配 key、被关掉都算不能） */
    boolean isAvailable();

    /**
     * 重排。
     *
     * @param candidates 候选池，**必须多于最终要的条数**，否则重排没有翻盘空间
     * @param gateHit    C 路闸门是否命中。命中说明"用户就是在问这个实体名"，
     *                   此时不允许"一条都不给" —— 那种情况下丢掉资料是明确的错
     * @return 重排后的结果；判定"没有资料"时返回空列表
     */
    List<KbRetriever.Hit> rerank(String query, List<KbRetriever.Hit> candidates, int topK, boolean gateHit);
}
