package com.example.qqbot.kb;

/**
 * 一次检索的参数 —— <b>群内问答与公开站搜索的差别全在这几个值里</b>。
 *
 * <h2>为什么是一个对象，而不是一堆位置参数</h2>
 * 原先 {@link KbRetriever} 有四个 {@code retrieve} 重载层层转发，最终签名里躺着两个布尔：
 * {@code retrieve(query, allowEmbedding, topK, minScore, allowRerank)}。
 * 读调用点必须回签名查"这两个 true/false 各是什么"，而且每加一个开关就多一层重载。
 * 收成一个对象之后，调用点写的是它<b>想要什么</b>，不是它<b>传了哪几个值</b>。
 *
 * @param topK          取前几条
 * @param minScore      余弦阈值（低于它的资料宁可不要）
 * @param allowEmbedding 是否允许走向量路。每次都要请求外部模型 API，
 *                       所以公开接口不能直接开 —— 要走
 *                       {@link com.example.qqbot.publicapi.PublicSearchService}，
 *                       那里有结果缓存与按天配额兜底
 * @param allowRerank   是否允许送 cross-encoder 重排。公开站明确关掉它：
 *                       多一次外部调用，在按天配额下不划算
 * @param expandSiblings 命中一块时是否把**同一份资料的其它块**补进注入内容。
 *                       群内问答要（给模型的资料必须完整）；公开站搜索不要 ——
 *                       那里是按标题去重后给人看的条目清单，补进去只会多出重复行。
 */
public record RetrievalOptions(int topK, double minScore, boolean allowEmbedding, boolean allowRerank,
                               boolean expandSiblings) {

    /**
     * 兼容旧的四参写法（公开站、测试）：默认**不**补全兄弟块 ——
     * 展示型检索要的是"一条条目一行"，不是"把整份资料铺开"。
     */
    public RetrievalOptions(int topK, double minScore, boolean allowEmbedding, boolean allowRerank) {
        this(topK, minScore, allowEmbedding, allowRerank, false);
    }

    /** 两条召回与重排都开、并补全同文档兄弟块 —— 群内问答的默认口径 */
    public static RetrievalOptions of(int topK, double minScore) {
        return new RetrievalOptions(topK, minScore, true, true, true);
    }

    /** 关掉重排，其余不动 */
    public RetrievalOptions withoutRerank() {
        return new RetrievalOptions(topK, minScore, allowEmbedding, false, expandSiblings);
    }

    /** 纯本地关键词路，不打外部调用 */
    public RetrievalOptions keywordOnly() {
        return new RetrievalOptions(topK, minScore, false, allowRerank, expandSiblings);
    }
}
