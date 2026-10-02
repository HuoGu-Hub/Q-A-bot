package com.example.qqbot.kb;

/**
 * 重排（cross-encoder）配置 —— **检索质量的最后一道，也是唯一会用绝对分说"不"的一道**。
 *
 * <p><b>为什么必须有它</b>（2026-10-02 黄金集实测）：双塔（bi-encoder）余弦在中文短句上
 * **基线高、方差小**，几乎没有区分度 —— 实测"该答"的 entity 类最低 0.548，
 * 而"库里根本没有"的 gap 类最高 0.559，**分布完全重叠**。
 * 所以"调高 min-score"这条路是死的：调到 0.55 能挡掉 14/15 的闲聊，
 * 但会连带把 colloquial 类全灭（0.503~0.556）。换 cross-encoder 才有区分度：
 * 同一个黄金集上，闲聊的 top 分 ≤0.002，而真实问题 ≥0.04。
 *
 * <p>它同时修掉另一个实测缺陷：**同族变体压过本体**。
 * 重排把「藏红花」抬到「藏红花幼苗」前、把「连锁闪电」抬到「永恒连锁闪电」前（4/4 修复）。
 * *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>知识库自己的领域词汇</b>，只是碰巧从 {@code app.kb.*} 绑定过来。
 * 留在 {@code KbProperties} 的嵌套类里，会让 {@code kb} 包看起来"依赖配置的形状"。
 * 搬迁是**纯搬运**：零语义变化、零新抽象，yml 的键一个都没动。
 */
public class Rerank {

    /** 总开关。关掉就退回纯 RRF 排序（老行为） */
    private boolean enabled = true;

    private String baseUrl = "https://api.siliconflow.cn/v1";

    /** 与 embedding 共用同一个 key（同一家服务），默认读同一个环境变量 */
    private String apiKey = "";

    /**
     * 重排模型。与 bge-m3 同源（BAAI），中文效果实测够用。
     * ⚠️ 它只做**排序与判废**，不参与建索引 —— 换它不需要重建向量库。
     */
    private String model = "BAAI/bge-reranker-v2-m3";

    /** 把融合后的前 N 条送去重排。N 必须 > top-k，否则重排没有翻盘空间 */
    private int candidateLimit = 20;

    /**
     * 重排分下限：**最高分都低于它 = 库里其实没有相关资料**，宁可一条都不给。
     *
     * <p>0.02 的来由（黄金集实测）：闲聊 top 分 0.0004 / 0.0017 / 0.0022，
     * 而所有**已验证正确**的真实问题 ≥ 0.04（氨液腺 0.596、雾锁铁矿 0.773、
     * 藏红花 0.135、珍珠 0.613、连锁闪电 0.664、咖啡烘焙机 0.824）。
     * 设成 0 就等于只要重排不空就照给（回滚开关）。
     */
    private double minScore = 0.02;

    private int timeoutSeconds = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
