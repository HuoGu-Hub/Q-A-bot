package com.example.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型相关配置，对应 application.yml 里的 app.llm.*
 *
 * <p>核心思路：<b>几乎所有厂商都兼容 OpenAI 协议</b>，所以不需要为每家写适配器，
 * 只要换 baseUrl + apiKey + modelName。这里用一张 Map 把任意多个厂商描述出来。
 *
 * <p>示例（$ 是环境变量占位）：
 * <pre>
 * app:
 *   llm:
 *     default-provider: deepseek
 *     fallback-providers: [opencode-zen]
 *     providers:
 *       deepseek:
 *         base-url: https://api.deepseek.com/v1
 *         api-key: ${DEEPSEEK_API_KEY:}
 *         model-name: deepseek-chat
 *         capabilities: [chat]
 *       opencode-zen:
 *         base-url: https://opencode.ai/zen/v1
 *         api-key: ${OPENCODE_ZEN_API_KEY:}
 *         model-name: deepseek-v4-flash-free
 *         capabilities: [chat, tools]
 * </pre>
 */
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    /** 默认用哪个 provider（没配或配错时会自动退到 fallback） */
    private String defaultProvider;

    /** 降级链：主 provider 失败时按顺序尝试 */
    private List<String> fallbackProviders = new ArrayList<>();

    /**
     * 系统提示词文件路径，按**优先级**依次尝试（第一个读到的生效）：
     *
     * <ol>
     *   <li>外部文件 —— 改完重启即生效，不用重新打包</li>
     *   <li>classpath 内置副本 —— 打包时从仓库根目录复制进来，保证 jar 自包含</li>
     * </ol>
     *
     * <p>采用 {@code AGENTS.md} 这个约定名（而不是自创的 {@code prompts/system.md}）——
     * 它是 Agent 生态的通行约定，将来迁移到正式 Agent 框架时可以无缝复用。
     */
    private String systemPromptFile = "AGENTS.md";

    /** 厂商名 → 配置 */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /** 回复风格（表现层约束，和 AGENTS.md 那份安全底座分开） */
    private ReplyStyle replyStyle = new ReplyStyle();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public List<String> getFallbackProviders() {
        return fallbackProviders;
    }

    public void setFallbackProviders(List<String> fallbackProviders) {
        this.fallbackProviders = fallbackProviders;
    }

    public String getSystemPromptFile() {
        return systemPromptFile;
    }

    public void setSystemPromptFile(String systemPromptFile) {
        this.systemPromptFile = systemPromptFile;
    }

    public ReplyStyle getReplyStyle() {
        return replyStyle;
    }

    public void setReplyStyle(ReplyStyle replyStyle) {
        this.replyStyle = replyStyle;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers;
    }

    /**
     * 回复风格 —— 表现层约束，和 AGENTS.md 那份「安全底座」刻意分开。
     *
     * <p>为什么不直接写进 AGENTS.md：
     * <ol>
     *   <li>AGENTS.md 是安全底座，改它风险大，而且它每次都进系统提示词、内容越长越贵；</li>
     *   <li>这里要能<b>热生效</b>（后台改完下一句就变），而 AGENTS.md 是启动时读一次的。</li>
     * </ol>
     *
     * <p>所以风格段是每轮现拼到系统消息里的，见
     * {@code LlmRouter#systemPromptWithStyle()}。冲突时安全规则优先，这一点在风格段的
     * 开头就写明了。
     */
    public static class ReplyStyle {

        /** 总开关。关掉 = 完全不加风格段，行为和以前一模一样 */
        private boolean enabled = true;

        /** 正文长度上限（字）。0 = 不限。这是给模型的【要求】不是硬截断 */
        private int maxChars = 0;

        /** default = 不管 / plain = 禁止 Markdown 列表标题 / list = 鼓励用短列表 */
        private String format = "default";

        /** 引用检索资料时，是否要求在结尾附上来源链接 */
        private boolean includeSources = false;

        /** 语气（自由文本）。例：轻松、简洁，像群友聊天。空 = 不指定 */
        private String tone = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public boolean isIncludeSources() {
            return includeSources;
        }

        public void setIncludeSources(boolean includeSources) {
            this.includeSources = includeSources;
        }

        public String getTone() {
            return tone;
        }

        public void setTone(String tone) {
            this.tone = tone;
        }
    }

    /** 单个厂商的配置 */
    public static class Provider {

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
}
