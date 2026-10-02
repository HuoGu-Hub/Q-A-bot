package com.example.qqbot.config;

import com.example.qqbot.kb.Embedding;
import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.MapSync;
import com.example.qqbot.kb.PublicSearch;
import com.example.qqbot.kb.Rerank;
import com.example.qqbot.kb.TitleIndex;
import com.example.qqbot.kb.WikiImport;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库检索（RAG）的配置绑定 —— 对应 application.yml 里的 {@code app.kb.*}。
 *
 * <p>分成三块：
 * <ul>
 *   <li><b>检索</b>（运行时）：topK、相似度阈值、总开关</li>
 *   <li><b>embedding</b>：向量模型服务（默认硅基流动 bge-m3）</li>
 *   <li><b>build</b>：离线构建语料库（抓 wiki → 清洗 → 分块）</li>
 * </ul>
 *
 * <h2>2026-10-02：6 个分组搬去了 {@code kb} 包，本类只剩「绑定」</h2>
 * {@code Embedding} / {@code Rerank} / {@code MapSync} / {@code WikiImport} /
 * {@code TitleIndex} / {@code PublicSearch} 原先是本类的嵌套类。它们是
 * <b>知识库自己的领域词汇</b>，只是碰巧从 yml 绑定过来 —— 留在 config 里会让
 * {@code kb} 包看起来"依赖配置的形状"。
 *
 * <p>搬迁是**纯搬运**：yml 的键一个都没变（{@code app.kb.rerank.*} 照旧），
 * 绑定关系也没变 —— 本类仍然持有那 6 个对象、仍然由 Spring 填值。
 *
 * <p>业务侧只读视图见 {@link KbPolicy} —— 业务包只依赖它，不依赖本类。
 */
@ConfigurationProperties(prefix = "app.kb")
public class KbProperties implements KbPolicy {

    /** 运行时检索总开关。关掉后退化成「没有知识库」的普通回答 */
    private boolean enabled = true;


    /** 知识库根目录（相对路径以程序工作目录为基准） */
    private String dir = "./data/kb";

    /** 取前几条资料拼进 prompt */
    private int topK = 5;

    /**
     * 相似度下限（余弦，0~1）。低于它的资料**宁可不要** ——
     * 给模型一段不相关的资料，比不给更糟（会诱导它硬编答案）。
     */
    private double minScore = 0.45;

    /**
     * C 路（标题向量）的闸门阈值 —— 余弦 ≥ 它才认为"这次提问就是在问某个名字"，把那条置顶。
     *
     * <p>为什么要有闸门、为什么是这个值：见 {@code KbRetriever} 的类注释与
     * {@code docs/md/知识库标题向量召回方案.md}。一句话 ——
     * 名字查询 100% 能触发、真实口语提问只有 2.5% 误触发（实测 200 + 120 条）。
     *
     * <p>设成 {@code <= 0} 就是**关掉 C 路**（等于回到没有标题向量之前的检索行为），
     * 这也是它的回滚开关。
     */
    private double titleGate = 0.70;

    private Embedding embedding = new Embedding();

    /** 重排（cross-encoder）配置，见 docs/md/知识库口语问答重构方案.md D4 */
    private Rerank rerank = new Rerank();

    /** wiki 地图数据同步（Interactive Data Maps）。见 docs/md/知识库口语问答重构方案.md 第四节 */
    private MapSync mapSync = new MapSync();

    /** wiki 文章导入（任务 / 机制）。见 docs/md/知识库口语问答重构方案.md 第四节 */
    private WikiImport wikiImport = new WikiImport();

    private TitleIndex titleIndex = new TitleIndex();

    private PublicSearch publicSearch = new PublicSearch();

        // ==================== getter / setter ====================

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }


    @Override
    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    @Override
    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    @Override
    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    @Override
    public double getTitleGate() {
        return titleGate;
    }

    public void setTitleGate(double titleGate) {
        this.titleGate = titleGate;
    }

    @Override
    public TitleIndex getTitleIndex() {
        return titleIndex;
    }

    public void setTitleIndex(TitleIndex titleIndex) {
        this.titleIndex = titleIndex;
    }

    @Override
    public Embedding getEmbedding() {
        return embedding;
    }

    @Override
    public Rerank getRerank() {
        return rerank;
    }

    @Override
    public MapSync getMapSync() {
        return mapSync;
    }

    @Override
    public WikiImport getWikiImport() {
        return wikiImport;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }





    @Override
    public PublicSearch getPublicSearch() {
        return publicSearch;
    }

    public void setPublicSearch(PublicSearch publicSearch) {
        this.publicSearch = publicSearch;
    }


}

