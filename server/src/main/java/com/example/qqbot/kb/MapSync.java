package com.example.qqbot.kb;

import java.util.ArrayList;
import java.util.List;

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
     * 只同步这几张地图（按 {@code Map:<名字>/...} 的前缀）。**空 = 不过滤** ——
     * 保持"命名空间里有什么就拉什么"的旧行为。
     *
     * <h2>为什么需要它</h2>
     * wiki 的 {@code Map} 命名空间里躺着的不只是当前世界：实测有 6 族页面 ——
     * Embervale（当前世界 1064 点）、EarlyAccess（**抢先体验版的旧世界图** 585 点）、
     * Blackmire / FrozenFrontier（单个区域的局部图）、Update4、还有一个别人建的 Test 页。
     * 不分青红皂白全拉进来会造成两个后果：
     * <ol>
     *   <li><b>界面上的假选择</b>：「默认显示」页会列出 5 张地图的切换 chip，
     *       而实际上常用的只有一张；</li>
     *   <li><b>语料被污染</b>：地点语料按 article 聚合，于是"远古尖塔"一条里
     *       混进了 5 套互不相干的坐标系（Embervale 0..10240 / EarlyAccess 1478..8269 /
     *       Blackmire 0..1485）—— 群友问出来的坐标可能来自旧世界。</li>
     * </ol>
     * 白名单是这套问题的**源头开关**：不进库就不会出现在界面、目录和语料里。
     * 以后想看某张区域图，把名字加回来、点一次同步即可。
     */
    private List<String> maps = new ArrayList<>(List.of("Embervale"));

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

    public List<String> getMaps() {
        return maps;
    }

    /** 空列表 = 不过滤。空白项直接丢掉，免得 yml 里一个多余空格就把整张地图挡在外面 */
    public void setMaps(List<String> maps) {
        List<String> clean = new ArrayList<>();
        if (maps != null) {
            for (String m : maps) {
                if (m != null && !m.isBlank()) {
                    clean.add(m.strip());
                }
            }
        }
        this.maps = clean;
    }

    /** 这张地图要不要同步。白名单为空时一律放行（旧行为） */
    public boolean allows(String map) {
        if (maps == null || maps.isEmpty()) {
            return true;
        }
        return map != null && maps.contains(map.strip());
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
