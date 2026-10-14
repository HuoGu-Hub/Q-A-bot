package com.example.qqbot.kb.block;

import java.util.List;

/**
 * 知识库里的一个**块** —— 块身份就是 {@link #id}。
 *
 * <h2>改造后：一个块就是一个词条</h2>
 * 词条不再是独立的一张名字表，而是块本身：{@code id + 标题 + 别名}。
 * 所以「知识库 → 词条」就是分块的目录 —— 一份文档有多少块，就多出多少条词条。
 *
 * <h2>为什么不再有"行号"</h2>
 * 旧设计里 {@code chunks.jsonl} 的第 k 行就是第 k 块，向量按行号对齐，
 * 历史检索记录也存行号（{@code {"i":770}}）。于是**任何删除都得用墓碑顶着、不能重排**，
 * 文件只增不减 —— 那套在"文档会持续增删"的场景下必然崩。
 *
 * <p>现在：**id 是身份，行号这个概念不存在**。删除就是删除，重排随便排。
 *
 * @param id        块身份。**英文标识**（如 {@code flame-altar-0}）——
 *                  用英文而不是中文标题，是因为它是数据库主键：
 *                  标题改一个字就换 id 的话，块的身份就断了
 * @param docId     属于哪份文档 / 哪个页面（用于"整份替换"和分组显示）
 * @param title     标题（中文）
 * @param body      正文（中文）
 * @param url       原文出处 —— **回答时会作为来源给群友**
 * @param tags      分类标签（分块符号里带过来的）
 * @param source    来源：{@code doc} = 文档导入，{@code manual} = 管理端手写，{@code wiki} = 抓取
 * @param retired   是否已下架（下架后不参与检索，但**保留数据**，可以恢复）
 * @param updatedAt 最后一次写入时间（ISO8601）
 * @param alias     别名（人工可改；空串表示没单独写过，展示时用标题派生兜底）。
 *                  **导入不覆盖它** —— 那是人工成果
 */
public record KbBlock(com.example.qqbot.kb.EntryId id,
                      String docId,
                      String title,
                      String body,
                      String url,
                      List<String> tags,
                      String source,
                      boolean retired,
                      String updatedAt,
                      String alias) implements com.example.qqbot.kb.doc.ImportableBlock {

    /**
     * 便利构造：不带别名。
     *
     * <p>没有别名时存空串（而不是派生值）—— 派生是读时的事，
     * 存进去会让"人工清空别名"永远做不到。
     */
    public KbBlock(com.example.qqbot.kb.EntryId id, String docId, String title, String body,
                   String url, List<String> tags, String source, boolean retired, String updatedAt) {
        this(id, docId, title, body, url, tags, source, retired, updatedAt, "");
    }

    /** 接口要求的身份方法 —— 块的 id 就是它的身份 */
    @Override
    public String key() {
        return id.value();
    }

    /** 来源：文档导入 */
    public static final String SRC_DOC = "doc";
    /** 来源：管理端手写 */
    public static final String SRC_MANUAL = "manual";

    /** 标签存成逗号分隔的字符串（标签本身不允许含逗号，见文档格式规范） */
    public String tagsLine() {
        return tags == null || tags.isEmpty() ? "" : String.join(",", tags);
    }

    /** 别名统一存空串（不存 null），SQL 层与显示逻辑都少一层判空 */
    public String aliasOrEmpty() {
        return alias == null ? "" : alias;
    }
}
