package com.example.qqbot.kb;

/**
 * 知识库配置的一个分组（{@code app.kb.public-search.*}）。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>知识库自己的领域词汇</b>，只是碰巧从 {@code app.kb.*} 绑定过来。
 * 留在 {@code KbProperties} 的嵌套类里，会让 {@code kb} 包看起来"依赖配置的形状"。
 * 搬迁是**纯搬运**：零语义变化、零新抽象，yml 的键一个都没动。
 */
public class PublicSearch {

    /**
     * 是否允许走向量路。
     *
     * <p>开启后每搜一次会调一次外部 embedding API（费用极低，但**是外部依赖**）；
     * 关掉则只有本地关键词路 —— 零成本，但只认得术语表里有的词。
     */
    private boolean vectorEnabled = true;

    /** 每天最多多少次「走向量」的公开检索。超出后当天自动降级为纯关键词，不报错 */
    private int vectorDailyLimit = 800;

    /** 结果缓存条数：同一句话重复搜不再调 API */
    private int cacheSize = 512;

    /**
     * 相似度下限。**比群内（0.45）低** —— 群内是"喂给模型当依据"，宁缺毋滥；
     * 公开站是"按相关度排序给人看"，阈值太高就直接变成一片空白。
     */
    private double minScore = 0.32;

    /** 去重前先取多少候选（一个页面会被切成好几块，取少了去重后就不够数） */
    private int candidatePool = 40;

    /** 单次搜索最多返回几条（同页去重之后） */
    private int maxResults = 20;

    public boolean isVectorEnabled() {
        return vectorEnabled;
    }

    public void setVectorEnabled(boolean vectorEnabled) {
        this.vectorEnabled = vectorEnabled;
    }

    public int getVectorDailyLimit() {
        return vectorDailyLimit;
    }

    public void setVectorDailyLimit(int vectorDailyLimit) {
        this.vectorDailyLimit = vectorDailyLimit;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public int getCandidatePool() {
        return candidatePool;
    }

    public void setCandidatePool(int candidatePool) {
        this.candidatePool = candidatePool;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }
}
