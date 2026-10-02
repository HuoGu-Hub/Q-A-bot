package com.example.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库检索（RAG）的全部配置，对应 application.yml 里的 app.kb.*
 *
 * <p>分成三块：
 * <ul>
 *   <li><b>检索</b>（运行时）：topK、相似度阈值、总开关</li>
 *   <li><b>embedding</b>：向量模型服务（默认硅基流动 bge-m3）</li>
 *   <li><b>build</b>：离线构建语料库（抓 wiki → 清洗 → 分块）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.kb")
public class KbProperties {

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

    private TitleIndex titleIndex = new TitleIndex();

    private PublicSearch publicSearch = new PublicSearch();

    // ==================== 标题向量补建 ====================

    /**
     * 标题向量的一次性补建（CLI）。
     *
     * <p>它只补"缺失"和"过期"（标题和生成时那份对不上）的块，**幂等**，
     * 所以随时可以再跑一遍，不会重复花钱。
     *
     * <pre>
     * mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.title-index.enabled=true --spring.main.web-application-type=none"
     * </pre>
     */
    public static class TitleIndex {

        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    // ==================== embedding ====================

    public static class Embedding {

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

    // ==================== 向量索引（P2）====================

        // ==================== 公开站检索 ====================

    /**
     * 公开站（公网可达）的检索策略。
     *
     * <p>和群内检索最大的区别：**这里是陌生人能打的**，所以每一项都要有刹车 ——
     * 缓存挡住重复、按天配额挡住刷量、阈值放宽保证"搜得到东西"。
     */
    public static class PublicSearch {

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

    // ==================== 术语表（B 路关键词检索的燃料）====================

        // ==================== 离线构建 ====================

        // ==================== getter / setter ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }


    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public double getTitleGate() {
        return titleGate;
    }

    public void setTitleGate(double titleGate) {
        this.titleGate = titleGate;
    }

    public TitleIndex getTitleIndex() {
        return titleIndex;
    }

    public void setTitleIndex(TitleIndex titleIndex) {
        this.titleIndex = titleIndex;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }





    public PublicSearch getPublicSearch() {
        return publicSearch;
    }

    public void setPublicSearch(PublicSearch publicSearch) {
        this.publicSearch = publicSearch;
    }


}
