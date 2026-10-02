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

    /** 重排（cross-encoder）配置，见 docs/md/知识库口语问答重构方案.md D4 */
    private Rerank rerank = new Rerank();

    /** wiki 地图数据同步（Interactive Data Maps）。见 docs/md/知识库口语问答重构方案.md 第四节 */
    private MapSync mapSync = new MapSync();

    /** wiki 文章导入（任务 / 机制）。见 docs/md/知识库口语问答重构方案.md 第四节 */
    private WikiImport wikiImport = new WikiImport();

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
     */
    public static class Rerank {

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

    /**
     * wiki 地图数据同步配置。
     *
     * <h2>数据在哪</h2>
     * wiki 用 Interactive Data Maps 扩展，数据全部落在 MediaWiki 的 {@code Map} 命名空间（id 2900），
     * 实测 **32 页**、其中 Embervale 一张图就有约 1,064 个 marker，
     * 每个 marker 都带 {@code name / description / article / x / y}（实测 100% 覆盖）。
     * 每页是标准 wiki 页面，因此**用公开 API 就能读，不需要爬 HTML**。
     *
     * <h2>为什么必须增量</h2>
     * {@code Map:Embervale/Main} 单页就 **100,630 字符**。全量 32 页约 548 KB，
     * 每次都拉一遍既慢又给对方服务器添负担。每页都有 {@code revid}，
     * 所以只拉**版本变过**的页 —— 状态存在 {@code kb_map_page} 表里。
     *
     * <p>⚠️ 别用通用网页抓取工具走这条路：{@code Main} 页超过 100 KB 会被截断成非法 JSON
     * （实测报 "Unterminated string"）。必须走 API 分批取。
     */
    public static class MapSync {

        /** 总开关：关掉则不同步、也不注册 CLI 入口 */
        private boolean enabled = true;

        /** MediaWiki API 入口 */
        private String apiBase = "https://enshrouded.wiki.gg/api.php";

        /** {@code Map} 命名空间 id（实测 2900） */
        private int namespace = 2900;

        /**
         * 一次请求取几页。MediaWiki 的 titles 上限是 50，
         * 但内容页可能上百 KB，取 8 是"少往返"与"单次响应别太大"之间的折中。
         */
        private int batchSize = 8;

        /** 遵守 wiki 的使用礼仪，带一个能说明来意的 UA */
        private String userAgent = "qqbot-personal/0.1 (Enshrouded map data sync; contact via group)";

        private int timeoutSeconds = 30;

        /** 最多同步多少页（防止命名空间里冒出成千上万页时失控） */
        private int maxPages = 500;

        /**
         * 命中限流时最多重试几次。
         *
         * <p><b>为什么需要它（实测）</b>：wiki 会对**没有自定义 User-Agent** 的请求直接限流，
         * 返回 `{"error":{"code":"ratelimited"}}`。我们在 Node 探针里就撞上了这个 ——
         * 连发几个请求之后全部失败。批量导入（任务页有 149 篇）必然触发，
         * 所以必须有退避重试，而不是把整次同步判死。
         */
        private int maxRetries = 4;

        /** 退避基数（毫秒）。第 n 次重试等 `backoffMillis * 2^(n-1)` */
        private long backoffMillis = 1000;

        /** 每批之间的固定间隔（毫秒）—— 主动放慢，别把对方服务器打疼 */
        private long batchDelayMillis = 200;

        /** 启动时自动同步一次。**默认关**：这是运维动作，不该每次重启都跑 */
        private boolean syncOnStart = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public int getNamespace() {
            return namespace;
        }

        public void setNamespace(int namespace) {
            this.namespace = namespace;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }

        public boolean isSyncOnStart() {
            return syncOnStart;
        }

        public void setSyncOnStart(boolean syncOnStart) {
            this.syncOnStart = syncOnStart;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getBackoffMillis() {
            return backoffMillis;
        }

        public void setBackoffMillis(long backoffMillis) {
            this.backoffMillis = backoffMillis;
        }

        public long getBatchDelayMillis() {
            return batchDelayMillis;
        }

        public void setBatchDelayMillis(long batchDelayMillis) {
            this.batchDelayMillis = batchDelayMillis;
        }
    }

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
     */
    public static class WikiImport {

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

    public Rerank getRerank() {
        return rerank;
    }

    public MapSync getMapSync() {
        return mapSync;
    }

    public WikiImport getWikiImport() {
        return wikiImport;
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
