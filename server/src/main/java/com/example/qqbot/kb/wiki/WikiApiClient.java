package com.example.qqbot.kb.wiki;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.MapSync;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MediaWiki API 客户端 —— 通用的一层，地图同步与文章导入都用它。
 *
 * <p>⚠️ 连接设置（api-base / user-agent / 批大小 / 退避）目前借用 {@code app.kb.map-sync.*}，
 * 因为那是最早需要它的功能。两个功能共用同一套连接参数是合理的，
 * 但**配置名不该叫 map-sync** —— 抽出 {@code app.kb.wiki.*} 是后续的一小步。
 *
 * <h2>为什么走 API 而不是抓页面 HTML</h2>
 * {@code Map} 命名空间里的页面**本身就是 JSON**，API 直接给原文。
 * 抓 HTML 反而要把 JSON 从 DOM 里再抠出来，多一层脆弱性。
 *
 * <h2>为什么要分两次取</h2>
 * <ol>
 *   <li>{@link #listPages()} 拿到命名空间里全部页名；</li>
 *   <li>{@link #fetchMeta(List)} 只取 <b>revid + 时间戳</b>（很轻），
 *       和本地存的对一下就知道哪几页变了；</li>
 *   <li>{@link #fetchContent(List)} 只对**变过的那几页**取正文。</li>
 * </ol>
 * 这是增量同步的全部秘密：常态下第 3 步是零个请求。
 *
 * <p>⚠️ 单页可达 100 KB 以上（{@code Map:Embervale/Main} 实测 100,630 字符），
 * 所以绝不能"一次把所有页内容拉回来"。
 */
@Component
public class WikiApiClient {

    private static final Logger log = LoggerFactory.getLogger(WikiApiClient.class);

    private final KbPolicy props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public WikiApiClient(KbPolicy props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** 一页的版本信息（不含正文） */
    public record Meta(String title, long revid, String timestamp) {
    }

    /** 一页的正文 */
    public record Revision(String title, long revid, String timestamp, String content) {
    }

    public static class WikiException extends RuntimeException {
        public WikiException(String message) {
            super(message);
        }

        public WikiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public boolean isEnabled() {
        return props.isEnabled() && props.getMapSync().isEnabled();
    }

    /** 一页列表结果：这一页的页名 + 下一页要回传的 continue 参数（空 = 到底了） */
    record Page(List<String> items, Map<String, String> next) {
    }

    /**
     * 列页结果。
     *
     * @param titles    列到的全部页名
     * @param truncated 是否**因为顶到上限而提前停下**（= 后面还有页没列）
     * @param requests  实际发了几次请求（观测用）
     */
    public record Collected(List<String> titles, boolean truncated, int requests) {
    }

    /** 翻页轮数上限：万一某个 wiki 一直回同一个令牌，也不能把导入卡死 */
    private static final int MAX_ROUNDS = 200;

    /**
     * 通用翻页收集 —— 把"取下一页"抽成一个函数，因此**可以离线单测**。
     *
     * <h2>为什么必须跟 {@code continue}（2026-10-05 修）</h2>
     * 三个 list 方法原来都是**单次请求**：{@code cmlimit} / {@code aplimit} 一次到底。
     * 而全站来源上限是 {@code max-pages-per-source: 300} —— 一旦某个分类涨过上限，
     * 多出来的页会**被静默丢掉**：不报错、不写状态行、日志里只有"列到 N 页"。
     * 这是遗漏里最难查的一类（实测 Lore 264 页，距上限只剩 36 页）。
     *
     * <p>现在跟着 {@code continue} 一路拉，直到没有令牌（真到底）或攒够 {@code cap} 条。
     * 后者把 {@link Collected#truncated()} 标成 true，**调用方必须报出来**。
     */
    static Collected collect(java.util.function.Function<Map<String, String>, Page> fetch, int cap) {
        int limit = Math.max(1, cap);
        List<String> out = new ArrayList<>();
        Map<String, String> cont = Map.of();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Page page = fetch.apply(cont);
            out.addAll(page.items());
            if (page.next().isEmpty()) {
                return new Collected(List.copyOf(out), false, round + 1);
            }
            if (out.size() >= limit) {
                return new Collected(List.copyOf(out.subList(0, limit)), true, round + 1);
            }
            cont = page.next();
        }
        log.warn("[KB-MAP] 列页翻页超过 {} 轮，停止（wiki 可能一直在回同一个 continue 令牌）", MAX_ROUNDS);
        return new Collected(List.copyOf(out), true, MAX_ROUNDS);
    }

    /** 指定命名空间里的全部页面名（跟着 continue 拉完，上限 {@code map-sync.max-pages}） */
    public Collected listNamespacePages() {
        MapSync cfg = props.getMapSync();
        Collected out = collect(cont -> {
            Map<String, String> q = baseQuery();
            q.put("list", "allpages");
            q.put("aplimit", "500");
            q.put("apnamespace", String.valueOf(cfg.getNamespace()));
            q.putAll(cont);
            JsonNode root = get(buildUrl(q));
            return new Page(titlesOf(root.path("query").path("allpages")),
                    nextOf(root, "apcontinue", "apcontinue"));
        }, cfg.getMaxPages());
        log.info("[KB-MAP] 命名空间 {} 共 {} 页（{} 次请求{}）", cfg.getNamespace(), out.titles().size(),
                out.requests(), out.truncated() ? "，⚠️ 顶到 max-pages 上限" : "");
        return out;
    }

    /**
     * 某个分类下的页面名。
     *
     * <p>⚠️ 加了 {@code cmtype=page}：不加的话**子分类**（{@code Category:...}）与文件
     * （{@code File:...}）也会被当成文章列进来 —— 实测 {@code Category:Gameplay} 的 11 个成员里
     * 有 2 个是子分类，导进来只会在状态表里多两行 0 字产的账面数据。
     */
    public Collected listCategoryMembers(String category, int cap) {
        return collect(cont -> {
            Map<String, String> q = baseQuery();
            q.put("list", "categorymembers");
            q.put("cmlimit", "500");
            q.put("cmtype", "page");
            q.put("cmtitle", "Category:" + category);
            q.putAll(cont);
            JsonNode root = get(buildUrl(q));
            return new Page(titlesOf(root.path("query").path("categorymembers")),
                    nextOf(root, "cmcontinue", "cmcontinue"));
        }, cap);
    }

    /** 某个前缀下的页面名（如 {@code Quests/}，命名空间 0） */
    public Collected listByPrefix(String prefix, int cap) {
        return collect(cont -> {
            Map<String, String> q = baseQuery();
            q.put("list", "allpages");
            q.put("aplimit", "500");
            q.put("apnamespace", "0");
            q.put("apprefix", prefix);
            q.putAll(cont);
            JsonNode root = get(buildUrl(q));
            return new Page(titlesOf(root.path("query").path("allpages")),
                    nextOf(root, "apcontinue", "apcontinue"));
        }, cap);
    }

    private static Map<String, String> baseQuery() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("action", "query");
        q.put("format", "json");
        q.put("formatversion", "2");
        return q;
    }

    private static List<String> titlesOf(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            String t = n.path("title").asText("");
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 从响应里取下一页的 continue 令牌。
     *
     * <p>MediaWiki 的 continue 块形如 {@code {"cmcontinue":"...","continue":"-||"}} ——
     * 除了模块自己的令牌，还有一个通用 {@code continue}，两个都要原样回传；
     * 少回一个可能翻页错位或提前结束。
     */
    private static Map<String, String> nextOf(JsonNode root, String tokenField, String paramName) {
        JsonNode c = root.path("continue");
        if (!c.isObject()) {
            return Map.of();
        }
        String token = c.path(tokenField).asText("");
        if (token.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put(paramName, token);
        String generic = c.path("continue").asText("");
        if (!generic.isBlank()) {
            out.put("continue", generic);
        }
        return out;
    }

    /** 参数表 → 完整 URL（键值统一 URL 编码） */
    private String buildUrl(Map<String, String> params) {
        String base = props.getMapSync().getApiBase();
        StringBuilder sb = new StringBuilder(base);
        sb.append(base.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 取 revid + 时间戳（分批，每批 {@code batch-size} 页） */
    public Map<String, Meta> fetchMeta(List<String> titles) {
        Map<String, Meta> out = new LinkedHashMap<>();
        for (List<String> batch : batches(titles)) {
            String url = props.getMapSync().getApiBase()
                    + "?action=query&prop=revisions&rvprop=ids%7Ctimestamp&rvslots=main"
                    + "&format=json&formatversion=2&titles=" + join(batch);
            JsonNode root = get(url);
            for (JsonNode p : root.path("query").path("pages")) {
                String title = p.path("title").asText("");
                JsonNode rev = p.path("revisions").path(0);
                if (title.isBlank() || rev.isMissingNode()) {
                    continue;
                }
                out.put(title, new Meta(title, rev.path("revid").asLong(0), rev.path("timestamp").asText("")));
            }
        }
        return out;
    }

    /** 取正文（分批） */
    public Map<String, Revision> fetchContent(List<String> titles) {
        Map<String, Revision> out = new LinkedHashMap<>();
        for (List<String> batch : batches(titles)) {
            String url = props.getMapSync().getApiBase()
                    + "?action=query&prop=revisions&rvprop=content%7Cids%7Ctimestamp&rvslots=main"
                    + "&format=json&formatversion=2&titles=" + join(batch);
            JsonNode root = get(url);
            for (JsonNode p : root.path("query").path("pages")) {
                String title = p.path("title").asText("");
                JsonNode rev = p.path("revisions").path(0);
                if (title.isBlank() || rev.isMissingNode()) {
                    continue;
                }
                out.put(title, new Revision(title, rev.path("revid").asLong(0),
                        rev.path("timestamp").asText(""),
                        rev.path("slots").path("main").path("content").asText("")));
            }
        }
        return out;
    }

    private List<List<String>> batches(List<String> titles) {
        // 批间主动放慢：别把对方服务器打疼，也降低被限流的概率
        long delay = props.getMapSync().getBatchDelayMillis();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        int size = Math.max(1, props.getMapSync().getBatchSize());
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < titles.size(); i += size) {
            out.add(titles.subList(i, Math.min(titles.size(), i + size)));
        }
        return out;
    }

    private static String join(List<String> titles) {
        StringBuilder sb = new StringBuilder();
        for (String t : titles) {
            if (sb.length() > 0) {
                sb.append("%7C");
            }
            sb.append(URLEncoder.encode(t, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * 发一次请求，**命中限流就退避重试**。
     *
     * <p><b>为什么必须有重试</b>（2026-10-02 实测）：wiki 会对没有自定义 User-Agent 的请求
     * 直接回 `{"error":{"code":"ratelimited"}}`；即使 UA 正确，批量拉取（任务页有 149 篇）
     * 也可能触发限流。一次同步不该因为第 40 页被限流就整体失败。
     *
     * <p>退避是**指数**的（`backoffMillis * 2^(n-1)`），并且只对"限流/服务端错误"重试；
     * 4xx 里那些"你请求写错了"的错误立刻抛，重试没有意义。
     */
    private JsonNode get(String url) {
        MapSync cfg = props.getMapSync();
        int maxRetries = Math.max(1, cfg.getMaxRetries());
        WikiException last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .header("User-Agent", cfg.getUserAgent())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                last = new WikiException("请求 wiki API 失败：" + e.getMessage(), e);
                sleepBackoff(cfg, attempt);
                continue;
            }

            boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
            JsonNode root = null;
            try {
                root = mapper.readTree(response.body());
            } catch (Exception ignored) {
                // 非 JSON 响应：下面按状态码判断
            }
            if (root != null && root.path("error").isObject()) {
                String code = root.path("error").path("code").asText("");
                String info = root.path("error").path("info").asText("");
                if ("ratelimited".equals(code) || "maxlag".equals(code)) {
                    retryable = true;
                }
                last = new WikiException("wiki API 返回错误：" + code + " " + info);
            } else if (response.statusCode() != 200) {
                last = new WikiException("wiki API HTTP " + response.statusCode() + "：" + url);
            } else if (root != null) {
                return root;
            }

            if (!retryable) {
                throw last;
            }
            log.warn("[KB-MAP] wiki 请求被限流/服务端错误（第 {}/{} 次）：{} —— {} ms 后重试",
                    attempt, maxRetries, last == null ? "?" : last.getMessage(),
                    cfg.getBackoffMillis() * (1L << Math.min(attempt - 1, 6)));
            sleepBackoff(cfg, attempt);
        }
        throw last != null ? last : new WikiException("wiki API 请求失败：" + url);
    }

    private static void sleepBackoff(MapSync cfg, int attempt) {
        long wait = Math.max(0, cfg.getBackoffMillis()) * (1L << Math.min(attempt - 1, 6));
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
