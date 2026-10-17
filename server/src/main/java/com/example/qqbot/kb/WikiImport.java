package com.example.qqbot.kb;

import java.util.ArrayList;
import java.util.List;

/**
 * wiki 文章导入配置 —— 把**任务**与**机制**补进语料。
 *
 * <h2>为什么这两类必须从文章来，不能从地图来</h2>
 * 实测地图的 `Quests` 分组**只有 1 个 marker**，而 `Category:Quests` 有 **149 篇**文章。
 * 任务的做法、前置、目标、奖励全在文章正文里，地图只有个坐标点。
 *
 * <h2>为什么正文要限长 —— 但**不是截断**（2026-10-02 改）</h2>
 * 检索是"把 top-5 拼进 prompt"，单块太长会把 prompt 撑爆、也会稀释语义。
 * 实测任务页约 2 KB、机制页约 11 KB。
 *
 * <p>原先的做法是 {@code substring(0, maxBodyChars)} —— 机制页会**丢掉 86% 的正文**，
 * 关键信息（前置条件、数值）很可能正好在被截掉的部分。这与实测缺口
 * 「任务/机制的中文检索只有 2/4」吻合。
 *
 * <p>现在 {@link #maxBodyChars} 的含义是 <b>单块上限</b>：超长页面由
 * {@code KbTextChunker} 按段落/句子边界**切成多块**，一块都不丢。
 *
 * <p>正文进库前一律过 {@code WikitextCleaner} —— 不清洗等于把 `{{` 和 `[[` 当内容喂给模型。
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>知识库自己的领域词汇</b>，只是碰巧从 {@code app.kb.*} 绑定过来。
 * 留在 {@code KbProperties} 的嵌套类里，会让 {@code kb} 包看起来"依赖配置的形状"。
 * 搬迁是**纯搬运**：零语义变化、零新抽象，yml 的键一个都没动。
 */
public class WikiImport {

    private boolean enabled = true;

    /** 分类名（不带 `Category:` 前缀） */
    private List<String> categories = new ArrayList<>();

    /** 页面前缀，如 `Quests/`（任务页都在这个前缀下） */
    private List<String> prefixes = new ArrayList<>();

    /**
     * 每个来源最多导入多少页。**0 = 不限**（默认）。
     *
     * <h2>为什么默认不限（2026-10-17 改）</h2>
     * 原来是 300，而页数会随游戏更新长大 —— 实测 {@code Category:Crafting Materials}
     * 已经 341 页，于是每跑一次都**静默少 41 页**（只有报告里一行警告）。
     * 任何固定数字迟早会被超过，而"拉全资料"是硬需求，所以默认不设上限：
     * 拉到底，由 {@code MAX_ROUNDS} 兜住死循环，页数在页面上**事前**看得见。
     *
     * <p>想给某个特别大的来源设闸，再把它调成具体数字；顶到时导入报告里会明确报出来。
     */
    private int maxPagesPerSource = 0;

    /** 派生块 docId 的前缀，形如 `wiki·任务`。purge 就按它清 */
    private String docIdPrefix = "wiki";

    /**
     * **单块**正文上限（字符）。超长页面按段落/句子边界**切成多块**，不再截断
     * —— 见 {@code KbTextChunker}。
     *
     * <p>注意它的含义变了：以前是"超过就丢掉"，现在是"超过就切下一块"。
     * 所以调它只影响**块的粒度**，不再影响"有没有丢内容"。
     */
    private int maxBodyChars = 1500;



    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }

    public List<String> getPrefixes() {
        return prefixes;
    }

    public void setPrefixes(List<String> prefixes) {
        this.prefixes = prefixes;
    }

    public int getMaxPagesPerSource() {
        return maxPagesPerSource;
    }

    public void setMaxPagesPerSource(int maxPagesPerSource) {
        this.maxPagesPerSource = maxPagesPerSource;
    }

    public String getDocIdPrefix() {
        return docIdPrefix;
    }

    public void setDocIdPrefix(String docIdPrefix) {
        this.docIdPrefix = docIdPrefix;
    }

    public int getMaxBodyChars() {
        return maxBodyChars;
    }

    public void setMaxBodyChars(int maxBodyChars) {
        this.maxBodyChars = maxBodyChars;
    }


    /** 渲染兜底：拿 {@code action=parse} 的 HTML 补数据层与数据表 */
    private final Render render = new Render();

    public Render getRender() {
        return render;
    }

    /**
     * 渲染兜底配置。
     *
     * <p>为什么只当"兜底"：实测 27 篇样本里，渲染文本更多的是 11 篇（数据表页），
     * 相当 14 篇，更少 2 篇。所以**不是**全面改用渲染，而是取两份文本里更长的那份 ——
     * 数据驱动的页面拿回被 {@code {{Get}}} 吞掉的内容，其余页面一个字不动。
     */
    public static class Render {

        private boolean enabled = true;

        /**
         * 渲染文本要比清洗后的正文长这么多倍才采用。
         *
         * <p>1.2 的含义是"多出两成才值得换"：实测 1.0~1.2 的那些页只是排版差异，
         * 换过去没有收益，反而让语料无谓波动。
         */
        private double preferRatio = 1.2;

        /** 渲染请求之间的间隔（毫秒）。一页一次请求，别把对方服务器打疼 */
        private int delayMillis = 200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getPreferRatio() {
            return preferRatio;
        }

        public void setPreferRatio(double preferRatio) {
            this.preferRatio = preferRatio;
        }

        public int getDelayMillis() {
            return delayMillis;
        }

        public void setDelayMillis(int delayMillis) {
            this.delayMillis = delayMillis;
        }
    }
}
