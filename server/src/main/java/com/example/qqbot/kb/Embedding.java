package com.example.qqbot.kb;

/**
 * 知识库配置的一个分组（{@code app.kb.embedding.*}）。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>知识库自己的领域词汇</b>，只是碰巧从 {@code app.kb.*} 绑定过来。
 * 留在 {@code KbProperties} 的嵌套类里，会让 {@code kb} 包看起来"依赖配置的形状"。
 * 搬迁是**纯搬运**：零语义变化、零新抽象，yml 的键一个都没动。
 */
public class Embedding {

    /**
     * 向量模型服务地址。默认硅基流动。
     *
     * <p>⚠️ **建索引和查询必须用同一个模型** —— 向量模型定义了坐标系，
     * 换了模型（哪怕只是 {@code Pro/} 前缀的版本）向量就不可比，必须整库重建。
     */
    private String baseUrl = "https://api.siliconflow.cn/v1";

    /** API Key，从环境变量 SILICONFLOW_API_KEY 读 */
    private String apiKey = "";

    private String model = "BAAI/bge-m3";

    /** 向量维度，用于校验索引文件是否和当前模型匹配 */
    private int dimensions = 1024;

    /** 批量调用时每批几条（硅基流动上限一般 32，保守取 10） */
    private int batchSize = 10;

    private int timeoutSeconds = 30;

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

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
