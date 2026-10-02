package com.example.qqbot.kb;

import java.util.List;

/**
 * 检索语料 —— **检索算法只认这个接口，不关心块是从哪来的**。
 *
 * <h2>为什么要有这层</h2>
 * 知识库曾经从「chunks.jsonl + index.bin（行号即身份）」迁到
 * 「SQLite 块表（id 即身份）」。**迁移已经完成**：旧实现连同旧语料一起删除，
 * 归档在 {@code server/data/.legacy-archive/kb-legacy-20260929.tar.gz}。
 *
 * <p>留下这层接口不是为了照顾旧实现，而是因为**检索算法必须只有一份**：
 * 向量余弦 + 术语表关键词 + RRF 融合 + 标题加成。那套算法是实测调出来的
 * （见 {@link KbRetriever} 里的各种注释），复制成两份必然漂移。
 *
 * <p>当前唯一实现是 {@link com.example.qqbot.kb.block.KbBlockIndex}（id 即身份）。
 * 检索器面向本接口，于是**换存储 = 换一个 Bean**，算法一行不用动。
 *
 * <p>{@link #entries()} 直接带上向量，而不是"先列条目、再按 id 查向量"：
 * 实现本来就是"块 + 向量"成对持有的，拆成两次查反而多一层映射
 * （旧实现里那个映射就是"行号"）。
 */
public interface KbCorpus {

    /** 有没有可检索的东西（没建库、一块都没有 → false，调用方据此降级） */
    boolean isReady();

    /** 向量维度；没有向量时返回 0 */
    int dimensions();

    /**
     * 参与检索的条目 —— **已经排除下架、且每一条都带向量**。
     *
     * <p>过滤放在实现里做，而不是让检索器每次判断：那两个条件（未下架、有向量）
     * 是"能不能被检索到"的定义，属于存储的职责。
     */
    List<Entry> entries();

    /**
     * 一条可检索的条目。
     *
     * @param id        身份。新世界是块 id（如 {@code flame-altar-0}）；
     *                  旧世界是行号的字符串形式 —— 过渡期用，随旧实现一起删
     * @param title     标题
     * @param text      正文（**已去掉下架标记**，可以直接进提示词）
     * @param url       出处
     * @param tags      标签 / 板块
     * @param curated   是不是"我们自己写过/改过的"（提示词要按出处分段）
     * @param contentAt 内容时间（ISO8601）；旧语料没有这一列时是空串
     * @param vector    向量（必有；没有向量的条目不该出现在 {@link #entries()} 里）
     * @param titleVector 标题的向量（**可以为 null**）。有了它，"用户打的就是这个物品名"
     *                  这件事才在向量侧可见 —— 正文是简介，几乎不重复标题里的名字，
     *                  所以正文向量对"名字提问"几乎无感（实测本体排第 7）。
     *                  没有这一路的条目照常参与检索，只是不吃 C 路的闸门。
     */
    record Entry(String id,
                 /**
                  * 属于哪个「页面 / 文档」。用途：术语表按页补齐、分类统计按页聚合。
                  * 旧语料里页面就是标题；新语料里是文档 slug（如 {@code flame-altar}）。
                  */
                 String docId,
                 String title,
                 String text,
                 String url,
                 List<String> tags,
                 boolean curated,
                 String contentAt,
                 float[] vector,
                 float[] titleVector) {

        /**
         * 便利构造：{@code docId} 默认取标题、没有标题向量。
         *
         * <p>旧存储里「一个页面」就是一批同标题的块，所以标题即页面；
         * 留着这个构造，既有测试和不需要标题向量的调用方就不用改。
         */
        public Entry(String id, String title, String text, String url,
                     List<String> tags, boolean curated, String contentAt, float[] vector) {
            this(id, title, title, text, url, tags, curated, contentAt, vector, null);
        }

        /**
         * 便利构造：带 {@code docId}、还没有标题向量。
         *
         * <p>"标题向量"是后加的一路，缺它不影响任何旧调用方 ——
         * 需要它的地方用 {@link #withTitleVector(float[])} 补上。
         */
        public Entry(String id, String docId, String title, String text, String url,
                     List<String> tags, boolean curated, String contentAt, float[] vector) {
            this(id, docId, title, text, url, tags, curated, contentAt, vector, null);
        }

        /** 同一条目，换一份标题向量 —— 存储层组装 / 测试造数据用 */
        public Entry withTitleVector(float[] titleVector) {
            return new Entry(id, docId, title, text, url, tags, curated, contentAt, vector, titleVector);
        }
    }
}
