package com.example.qqbot.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>模型层自己的形状</b>，只是碰巧从 {@code app.llm.*} 绑定过来：
 * {@code Provider} 是「一个厂商的接入参数」，{@code ReplyStyle} 是回复风格
 * （{@code LlmRouter.describeReplyStyle()} 直接把它翻成给模型的提示词）。
 * 留在 {@code LlmProperties} 的嵌套类里，会让 {@code llm} 包看起来"依赖配置的形状"。
 * 搬迁是**纯搬运**：零语义变化，yml 的键一个都没动。
 */
public class Provider {

    /** 接口地址。OpenAI 兼容的厂商都是 <域名>/v1 这种形式 */
    private String baseUrl;

    /** API Key。留空表示这个厂商不可用，启动时会跳过 */
    private String apiKey;

    /** 模型名，例如 deepseek-chat、qwen-vl-max */
    private String modelName;

    /**
     * 能力标签，用于按需选模型。
     * chat   = 普通对话（必须有）
     * vision = 能看图（M4 用）
     * tools  = 支持函数调用（M5 用）
     */
    private List<String> capabilities = new ArrayList<>(List.of("chat"));

    private Double temperature = 0.7;

    private Integer timeoutSeconds = 60;

    /** LangChain4j 自身的重试次数（不含我们的降级链） */
    private Integer maxRetries = 1;

    /**
     * 单次回复的最大 token 数。
     * 0 或留空 = 不设置，用服务商的默认值。
     *
     * <p>⚠️ 对<b>推理模型</b>（如 deepseek-v4.1-flash）必须给够：
     * 「思考」消耗的 token 也算在这里面。给太小会出现
     * 「思考完就没预算写答案」→ content 返回空字符串 → 机器人发空白消息。
     */
    private Integer maxTokens = 0;

    /**
     * 推理强度：low / medium / high（留空 = 不传这个参数）。
     *
     * <p>只对<b>推理模型</b>有意义。实测 OpenCode Go 接受这个参数，
     * 但模型端执行得不严格：复杂问题思考 token 约降 20%，简单问题甚至可能反效果。
     * 设成 low 至少不会更差，而且是个标准参数，换模型也能用。
     *
     * <p>注意：非推理模型（如 glm-5.3-flash）收到这个参数可能报错，
     * 所以只有明确需要时才配。
     */
    private String reasoningEffort;

    /** 是否把请求/响应打到日志里（排查问题时有用，但会暴露内容，默认关） */
    private Boolean logRequests = false;

    /**
     * 额外的请求头。
     * 有些厂商有特殊要求，比如 OpenCode Go 要求自定义 User-Agent
     * 并带上 x-opencode-session 头（官方文档的防滥用要求）。
     */
    private Map<String, String> headers = new LinkedHashMap<>();

    /** 复制一份（测试连通性时用，避免动到原对象） */
    public Provider copy() {
        Provider c = new Provider();
        c.baseUrl = baseUrl;
        c.apiKey = apiKey;
        c.modelName = modelName;
        c.capabilities = new java.util.ArrayList<>(capabilities);
        c.temperature = temperature;
        c.timeoutSeconds = timeoutSeconds;
        c.maxRetries = maxRetries;
        c.maxTokens = maxTokens;
        c.reasoningEffort = reasoningEffort;
        c.logRequests = logRequests;
        c.headers = new java.util.LinkedHashMap<>(headers);
        return c;
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

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public Boolean getLogRequests() {
        return logRequests;
    }

    public void setLogRequests(Boolean logRequests) {
        this.logRequests = logRequests;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public boolean hasCapability(String capability) {
        return capabilities != null && capabilities.contains(capability);
    }
}
