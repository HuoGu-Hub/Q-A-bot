package com.example.qqbot.config;

import com.example.qqbot.llm.LlmPolicy;
import com.example.qqbot.llm.Provider;
import com.example.qqbot.llm.ReplyStyle;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型相关配置绑定 —— 对应 application.yml 里的 {@code app.llm.*}
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
 *
 * <h2>2026-10-02：{@code Provider} / {@code ReplyStyle} 搬去了 {@code llm} 包</h2>
 * 它们是<b>模型层自己的形状</b>，只是碰巧从 yml 绑定过来 ——
 * {@code Provider} 是「一个厂商的接入参数」，{@code ReplyStyle} 是回复风格
 * （{@code LlmRouter.describeReplyStyle()} 直接把它翻成给模型的提示词）。
 * 留在本类的嵌套类里，会让 {@code llm} 包看起来"依赖配置的形状"。
 *
 * <p>搬迁是**纯搬运**：yml 的键一个都没变，绑定关系也没变 ——
 * 本类仍然持有 {@code Map<String, Provider>} 和 {@code ReplyStyle}，仍然由 Spring 填值。
 *
 * <p>业务侧只读视图见 {@link LlmPolicy} —— 业务包只依赖它，不依赖本类。
 */
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties implements LlmPolicy {

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

    @Override
    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    @Override
    public List<String> getFallbackProviders() {
        return fallbackProviders;
    }

    public void setFallbackProviders(List<String> fallbackProviders) {
        this.fallbackProviders = fallbackProviders;
    }

    @Override
    public String getSystemPromptFile() {
        return systemPromptFile;
    }

    public void setSystemPromptFile(String systemPromptFile) {
        this.systemPromptFile = systemPromptFile;
    }

    @Override
    public ReplyStyle getReplyStyle() {
        return replyStyle;
    }

    public void setReplyStyle(ReplyStyle replyStyle) {
        this.replyStyle = replyStyle;
    }

    @Override
    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers;
    }
}
