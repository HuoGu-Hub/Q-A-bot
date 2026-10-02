package com.example.qqbot.kb;

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
 *
 * <h2>为什么不在 {@code config} 包里（2026-10-02 挪过来）</h2>
 * 它是<b>知识库自己的领域词汇</b>，只是碰巧从 {@code app.kb.*} 绑定过来。
 * 留在 {@code KbProperties} 的嵌套类里，会让 {@code kb} 包看起来"依赖配置的形状"。
 * 搬迁是**纯搬运**：零语义变化、零新抽象，yml 的键一个都没动。
 */
public class MapSync {

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
