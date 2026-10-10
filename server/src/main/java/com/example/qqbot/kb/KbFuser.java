package com.example.qqbot.kb;

import java.util.List;

/**
 * 融合器 —— 把多路候选合成一张表。
 *
 * <p>两路的分数量纲完全不同（余弦 0~1 vs 词频累加），所以按<b>排名</b>融合。
 * 标题加成也在这里：用户打的是物品名时，物品页必须压过"正文提到它的页"。
 */
public interface KbFuser {

    /** 按排名融合两路候选，取前 {@code limit} 条 */
    List<KbRetriever.Hit> fuse(List<KbRetriever.Hit> byVector, List<KbRetriever.Hit> byKeyword,
                               int limit, List<String> terms);

    /**
     * 把闸门命中的那一条提到第 1 位。
     *
     * <p>其余条目的相对顺序保持不变（不做任何分数混合）：这样"多一路"带来的唯一变化
     * 就是"这一条排最前"，口语提问即使被误触发，代价也只是首条换了。
     * 它可能压根不在融合结果里 —— 那就补进来占第 1 位，并挤掉最后一个。
     */
    List<KbRetriever.Hit> pin(List<KbRetriever.Hit> fused, KbRetriever.TitleGate gate, int limit);
}
