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
 * <h2>为什么正文要截断</h2>
 * 检索是"把 top-5 拼进 prompt"，单块太长会把 prompt 撑爆、也会稀释语义。
 * 实测任务页约 2 KB、机制页约 11 KB，所以按 {@link #maxBodyChars} 截断。
 *
 * <p>正文进库前一律过 {@code WikitextCleaner} —— 不清洗等于把 `{{` 和 `[[` 当内容喂给模型。
 * *
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

    /** 每个来源最多导入多少页 —— 防止某个分类突然膨胀时失控 */
    private int maxPagesPerSource = 300;

    /** 派生块 docId 的前缀，形如 `wiki·任务`。purge 就按它清 */
    private String docIdPrefix = "wiki";

    /** 单块正文上限（字符）。超长页面截断，别把 prompt 撑爆 */
    private int maxBodyChars = 1500;

    /** 启动时自动导入一次。**默认关** —— 运维动作 */
    private boolean importOnStart = false;

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

    public boolean isImportOnStart() {
        return importOnStart;
    }

    public void setImportOnStart(boolean importOnStart) {
        this.importOnStart = importOnStart;
    }
}
