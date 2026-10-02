package com.example.qqbot.publicapi;

import com.example.qqbot.site.SitePolicy;
import com.example.qqbot.kb.Glossary;
import com.example.qqbot.kb.category.CategoryService;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.qa.QaAnalytics;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 公开只读接口（面向群友）。
 *
 * <p><b>三条硬性约束：</b>
 * <ol>
 *   <li><b>只读</b> —— 全是 GET，没有任何写操作</li>
 *   <li><b>只吐公开字段</b> —— 返回 {@link PublicDtos} 里的类型，
 *       从类型层面就不带 QQ 号/群号/检索分数</li>
 *   <li><b>按 IP 限流</b> —— 公网可达，必须防刷</li>
 * </ol>
 *
 * <p>⚠️ <b>绝不复用 {@code AdminController} 的方法</b>。
 * 即使某个字段"看起来可以公开"，也要在这里显式拷贝一次 ——
 * 这样以后给管理接口加字段时，不会顺手泄露出去。
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private static final Logger log = LoggerFactory.getLogger(PublicController.class);

    /** 公开检索最多返回几条 */
    private static final int MAX_SEARCH_RESULTS = 20;

    /** 公开接口最多返回多少正文字符（防止被当成免费 API 刷全文） */
    private static final int MAX_TEXT_CHARS = 1200;

    private final com.example.qqbot.kb.KbCorpus kbCorpus;
    private final SitePolicy site;
    private final com.example.qqbot.config.BotProperties botProps;
    private final com.example.qqbot.kb.category.CategoryService categoryService;
    private final com.example.qqbot.site.SiteTextService siteText;
    private final KbRetriever kbRetriever;
    private final PublicSearchService publicSearch;
    private final Glossary glossary;
    private final QaAnalytics analytics;
    private final PublicRateLimiter limiter;

    public PublicController(com.example.qqbot.kb.KbCorpus kbCorpus, KbRetriever kbRetriever,
                            PublicSearchService publicSearch, Glossary glossary,
                            QaAnalytics analytics,
                            PublicRateLimiter limiter,
                            SitePolicy site,
                            com.example.qqbot.config.BotProperties botProps,
                            com.example.qqbot.kb.category.CategoryService categoryService,
                            com.example.qqbot.site.SiteTextService siteText) {
        this.kbCorpus = kbCorpus;
        this.kbRetriever = kbRetriever;
        this.publicSearch = publicSearch;
        this.glossary = glossary;
        this.analytics = analytics;
        this.limiter = limiter;
        this.site = site;
        this.botProps = botProps;
        this.categoryService = categoryService;
        this.siteText = siteText;
    }

    // ==================== 统计 ====================

    /**
     * 公开统计。
     *
     * <p>只给"这个助手有多少料"这类不敏感的数字：
     * 累计问答数、命中率、语料条数、术语条数。
     */
    /**
     * 站点信息（公开站「关于」页用）。
     *
     * <p>只有群名称/群号/说明，**不含任何隐私**。
     */
    @GetMapping("/site")
    public Map<String, Object> siteInfo() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("groupName", site.getGroupName());
        out.put("groupNumber", site.getGroupNumber());
        out.put("groupDesc", site.getGroupDesc());
        out.put("joinHint", site.getJoinHint());
        out.put("botName", botProps.getName());
        // 页面固定文案的**覆盖值**（键是 "page.block"）。
        // 只回被改过的：没改过的由前端用自带默认文案渲染 ——
        // 这样接口小，而且后端不可用时站点也不会变空白。
        out.put("texts", siteText.publicOverrides());
        return out;
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }

        QaAnalytics.Overview ov = analytics.overview(0);
        List<QaAnalytics.DayCount> daily = ov.daily();
        String since = daily.isEmpty() ? "" : daily.get(0).day();

        return ResponseEntity.ok(new PublicDtos.PublicStats(
                // 提问量，不是消息总数 —— 原来是 total()，对外错约 17 倍
                ov.questions(),
                round(ov.hitRate()),
                kbCorpus.isReady() ? kbCorpus.entries().size() : 0,
                glossary.size(),
                since));
    }

    // ==================== 知识库 ====================

    /**
     * 全文检索。
     *
     * <p>走 {@link PublicSearchService} —— 它是"公网刹车"：结果缓存 + 按天配额 +
     * 同页去重。语料是英文的，中文口语提问（"木头"、"等级上限"）只有向量路
     * （bge-m3 是多语言的）才搜得到；纯本地关键词路只认术语表里的标准名词。
     *
     * <p>配额用完或向量模型不可用时**静默降级**为纯关键词，搜索照样能用，
     * 只是少了语义能力（返回体里的 {@code mode} 会变成 keyword）。
     *
     * <p>只返回标题/链接/正文片段，不返回余弦分数（那是内部调优指标）。
     */
    @GetMapping("/kb/search")
    public ResponseEntity<?> search(@RequestParam("q") String q,
                                    @RequestParam(defaultValue = "20") int limit,
                                    HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return ResponseEntity.ok(new PublicDtos.KbSearchResult("", 0, PublicSearchService.MODE_KEYWORD, List.of()));
        }
        if (query.length() > 200) {
            query = query.substring(0, 200);
        }

        // 缓存 + 配额 + 同页去重都在这一层里，见 PublicSearchService
        int want = Math.min(Math.max(limit, 1), MAX_SEARCH_RESULTS);
        PublicSearchService.Outcome out = publicSearch.search(query, want);
        List<PublicDtos.KbEntry> entries = new ArrayList<>();
        for (KbRetriever.Hit hit : out.hits()) {
            entries.add(toEntry(hit.entry()));
        }

        return ResponseEntity.ok(
                new PublicDtos.KbSearchResult(query, entries.size(), out.mode(), entries));
    }

    /** 分类列表（用于「浏览资料库」） */
    /**
     * 大类列表（资料库首页用）。
     *
     * <p>返回的是玩家视角的 7 个大类，不是 Wiki 的 603 个原始英文分类 ——
     * 原始分类给玩家看等于没有分类。
     *
     * <p>每个大类附带它下面最热门的几个原始标签（也翻译成中文了）。
     */
    @GetMapping("/kb/groups")
    public ResponseEntity<?> groups(HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (CategoryService.GroupStat g : categoryService.groupStats()) {
            // 「其他」不展示给玩家 —— 那是给管理端看的兜底桶
            if ("other".equals(g.key())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", g.key());
            m.put("label", g.label());
            m.put("icon", g.icon());
            m.put("desc", g.desc());
            m.put("entryCount", g.chunkCount());
            m.put("tags", topTags(g.key(), 14));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** 某大类下的热门标签（原始分类 + 中文名 + 条目数） */
    private List<Map<String, Object>> topTags(String groupKey, int limit) {
        Map<String, String> rawToGroup = categoryService.rawToGroup();
        Map<String, String> labels = categoryService.rawToLabel();
        Map<String, Integer> counts = categoryService.rawCounts();

        // 按【显示名】合并 —— 不同原始分类可能译名相同（enemy / enemies 都叫「敌人」）
        Map<String, Map<String, Object>> byLabel = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : rawToGroup.entrySet()) {
            if (!groupKey.equals(e.getValue())) {
                continue;
            }
            int n = counts.getOrDefault(e.getKey(), 0);
            if (n < 2) {
                continue;
            }
            // 没有中文名就【不展示】—— 绝不把 Wiki 的英文原始分类甩给玩家。
            // 2026-09-26 实测：7 个大类里有 137 个原始分类查不到中文名（多为单页任务/信件名），
            // 退回英文会让标签行变成中英混杂（「迷失于迷雾」旁边挂个 Lost In The Shroud）。
            String label = labels.get(e.getKey());
            if (label == null) {
                continue;
            }
            Map<String, Object> t = byLabel.get(label);
            if (t == null) {
                t = new LinkedHashMap<>();
                t.put("raw", e.getKey());
                t.put("label", label);
                t.put("count", n);
                byLabel.put(label, t);
            } else {
                // 同名合并：条目数相加，raw 保留第一个（点进去看的是同一类东西）
                t.put("count", (int) t.get("count") + n);
            }
        }
        List<Map<String, Object>> tags = new ArrayList<>(byLabel.values());
        tags.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));
        return tags.size() > limit ? tags.subList(0, limit) : tags;
    }

    /**
     * 按大类/标签浏览条目。
     *
     * <p>不走向量检索 —— 这是分类浏览，不是搜索。直接扫索引里的分类字段。
     */
    @GetMapping("/kb/browse")
    public ResponseEntity<?> browse(@RequestParam(required = false) String group,
                                    @RequestParam(required = false) String tag,
                                    @RequestParam(defaultValue = "0") int page,
                                    HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        if (!kbCorpus.isReady()) {
            return ResponseEntity.ok(Map.of("entries", List.of(), "total", 0, "page", 0));
        }

        Map<String, String> rawToGroup = categoryService.rawToGroup();
        Map<String, String> labels = categoryService.rawToLabel();

        // 按标题去重 —— 一个页面会被切成多块
        Map<String, com.example.qqbot.kb.KbCorpus.Entry> matched = new LinkedHashMap<>();
        for (com.example.qqbot.kb.KbCorpus.Entry chunk : kbCorpus.entries()) {
            List<String> cats = chunk.tags();
            if (cats == null || cats.isEmpty()) {
                continue;
            }
            for (String c : cats) {
                if (c == null || c.isBlank()) {
                    continue;
                }
                boolean hit;
                if (tag != null && !tag.isBlank()) {
                    hit = tag.equals(c);
                } else if (group != null && !group.isBlank()) {
                    hit = group.equals(rawToGroup.get(c));
                } else {
                    hit = false;
                }
                if (hit) {
                    matched.putIfAbsent(chunk.title(), chunk);
                    break;
                }
            }
        }

        List<com.example.qqbot.kb.KbCorpus.Entry> all = new ArrayList<>(matched.values());
        all.sort(Comparator.comparing(com.example.qqbot.kb.KbCorpus.Entry::title));
        int total = all.size();
        int size = 40;
        int from = Math.max(0, page) * size;
        int to = Math.min(total, from + size);

        List<Map<String, Object>> entries = new ArrayList<>();
        if (from < total) {
            for (com.example.qqbot.kb.KbCorpus.Entry c : all.subList(from, to)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", c.title());
                m.put("url", c.url());
                m.put("text", com.example.qqbot.kb.KbTextDisplay.excerpt(c.text(), 170));
                List<String> shown = new ArrayList<>();
                for (String cat : c.tags()) {
                    // 同上：只有词典/后台给过中文名的才展示，不退回英文原名
                    String lbl = labels.get(cat);
                    if (lbl != null && !shown.contains(lbl)) {
                        shown.add(lbl);
                    }
                    if (shown.size() >= 3) {
                        break;
                    }
                }
                m.put("tags", shown);
                entries.add(m);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", entries);
        out.put("total", total);
        out.put("page", page);
        out.put("pageSize", size);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/kb/page")
    public ResponseEntity<?> page(@RequestParam("title") String title, HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        if (!kbCorpus.isReady() || title == null || title.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "词条不存在"));
        }

        String want = title.trim().toLowerCase(Locale.ROOT);
        List<com.example.qqbot.kb.KbCorpus.Entry> matched = new ArrayList<>();
        for (com.example.qqbot.kb.KbCorpus.Entry chunk : kbCorpus.entries()) {
            if (chunk.title().toLowerCase(Locale.ROOT).equals(want)) {
                matched.add(chunk);
            }
        }
        if (matched.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "词条不存在"));
        }
        matched.sort(Comparator.comparing(com.example.qqbot.kb.KbCorpus.Entry::id));

        StringBuilder sb = new StringBuilder();
        for (com.example.qqbot.kb.KbCorpus.Entry c : matched) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(c.text());
        }
        String raw = sb.toString();
        if (raw.length() > MAX_TEXT_CHARS) {
            raw = raw.substring(0, MAX_TEXT_CHARS) + "…";
        }
        String text = com.example.qqbot.kb.KbTextDisplay.full(raw);

        return ResponseEntity.ok(toEntry(matched.get(0), text));
    }

    // ==================== 内部 ====================

    /** 统一的限流检查。返回非 null 表示已拦截，直接回响应。 */
    private ResponseEntity<?> rateLimit(HttpServletRequest request) {
        String ip = clientIp(request);
        if (!limiter.allow(ip)) {
            log.warn("[PUBLIC] 限流触发：ip={}", ip);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "请求太频繁，请稍后再试"));
        }
        return null;
    }

    /** 取真实客户端 IP（上公网后通常挂在反向代理后面） */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    /** 语料条目 → 公开 DTO（**显式拷贝**，不直接暴露内部对象） */
    private PublicDtos.KbEntry toEntry(com.example.qqbot.kb.KbCorpus.Entry entry) {
        return toEntry(entry, clip(entry.text()));
    }

    private PublicDtos.KbEntry toEntry(com.example.qqbot.kb.KbCorpus.Entry entry, String text) {
        return new PublicDtos.KbEntry(
                entry.title(),
                entry.url(),
                text,
                shownCats(entry.tags()));
    }

    /**
     * 公开站展示的分类标签。
     *
     * <p>规则与「资料库」浏览列表**完全一致**：只给后台/词典已经定过中文名的分类，
     * 并且翻成中文；没有中文名的**不退回英文原名**。理由是公开站是中文站 ——
     * 冒出 {@code Wake Of Water Update}、{@code One-handed Swords} 这种 wiki
     * 编辑用的分类名，读者只会以为页面出错了。
     *
     * <p>详情页和搜索结果共用这一份逻辑，否则「同一批标签在列表里是中文、
     * 点进去变英文」又是一处前后不一致。
     */
    private List<String> shownCats(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        Map<String, String> labels = categoryService.rawToLabel();
        List<String> out = new ArrayList<>();
        for (String c : tags) {
            String lbl = c == null ? null : labels.get(c);
            if (lbl != null && !lbl.isBlank() && !out.contains(lbl)) {
                out.add(lbl);
            }
        }
        return out;
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return com.example.qqbot.kb.KbTextDisplay.excerpt(s, MAX_TEXT_CHARS);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
