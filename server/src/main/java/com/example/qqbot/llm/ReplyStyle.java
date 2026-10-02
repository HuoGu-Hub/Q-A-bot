package com.example.qqbot.llm;


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
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>模型层自己的形状</b>，只是碰巧从 {@code app.llm.*} 绑定过来：
 * {@code Provider} 是「一个厂商的接入参数」，{@code ReplyStyle} 是回复风格
 * （{@code LlmRouter.describeReplyStyle()} 直接把它翻成给模型的提示词）。
 * 留在 {@code LlmProperties} 的嵌套类里，会让 {@code llm} 包看起来"依赖配置的形状"。
 * 搬迁是**纯搬运**：零语义变化，yml 的键一个都没动。
 */
public class ReplyStyle {

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
