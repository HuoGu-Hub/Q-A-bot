package com.example.qqbot.kb.map;

import com.example.qqbot.config.KbProperties;
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
 * MediaWiki API 客户端 —— 只做地图同步需要的那两件事。
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
public class WikiMapClient {

    private static final Logger log = LoggerFactory.getLogger(WikiMapClient.class);

    private final KbProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public WikiMapClient(KbProperties props, ObjectMapper mapper) {
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

    /** 命名空间里全部页面名 */
    public List<String> listPages() {
        KbProperties.MapSync cfg = props.getMapSync();
        String url = cfg.getApiBase() + "?action=query&list=allpages&aplimit=" + Math.max(1, cfg.getMaxPages())
                + "&apnamespace=" + cfg.getNamespace() + "&format=json&formatversion=2";
        JsonNode root = get(url);
        List<String> out = new ArrayList<>();
        for (JsonNode p : root.path("query").path("allpages")) {
            String title = p.path("title").asText("");
            if (!title.isBlank()) {
                out.add(title);
            }
        }
        log.info("[KB-MAP] 命名空间 {} 共 {} 页", cfg.getNamespace(), out.size());
        return out;
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
        KbProperties.MapSync cfg = props.getMapSync();
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

    private static void sleepBackoff(KbProperties.MapSync cfg, int attempt) {
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
