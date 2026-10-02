package com.example.qqbot.kb.doc;

/**
 * 导入比对真正需要的最小接口：**身份 + 标题 + 正文**。
 *
 * <p>为什么要它：比对的两边来自不同的层 ——
 * 一边是**解析出来的块**（{@link ChunkMarkup.Block}，来自文档），
 * 另一边是**库里已有的块**（{@code KbBlock}，来自数据库）。
 * 如果 {@link ChunkImportPlan} 直接依赖解析器的类型，两边就接不上；
 * 抽成接口之后，比对逻辑对"块从哪来"一无所知。
 */
public interface ImportableBlock {

    /** 身份：有 id 用 id，没有就用标题 */
    String key();

    String title();

    String body();
}
