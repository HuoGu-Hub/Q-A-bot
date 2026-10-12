package com.example.qqbot.kb.wiki;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.persistence.KbWikiSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * wiki 拉取范围与触发接口（管理后台）。
 *
 * <p>这个控制器就是"把命令行搬进页面"的那一层：
 * <ul>
 *   <li>GET  /sources —— 当前范围（页面上勾选的结果，没配过则回显 yml 默认值）；</li>
 *   <li>POST /sources —— 保存范围（整表替换）；</li>
 *   <li>GET  /catalog —— wiki 上有什么分类、各多少页（菜单，带缓存）；</li>
 *   <li>POST /import —— 触发一次拉取 + 清洗（空跑 / 增量 / 强制重洗）；</li>
 *   <li>GET  /import/status —— 轮询进度与报告。</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/api/kb/wiki")
public class KbWikiController {

    private static final Logger log = LoggerFactory.getLogger(KbWikiController.class);

    /** 菜单缓存时长：wiki 上分类不会分钟级变化，而每个分类要发一次请求数页数 */
    private static final long CATALOG_TTL_MILLIS = 10 * 60 * 1000L;
    /** 菜单里最多数多少个分类的准确页数（其余只给成员数） */
    private static final int CATALOG_COUNT_LIMIT = 40;

    private final KbWikiSourceStore store;
    private final WikiApiClient client;
    private final WikiImportService service;
    private final KbPolicy props;

    private volatile Map<String, Object> catalogCache;
    private volatile long catalogAt;

    public KbWikiController(KbWikiSourceStore store, WikiApiClient client,
                            WikiImportService service, KbPolicy props) {
        this.store = store;
        this.client = client;
        this.service = service;
        this.props = props;
    }

    /** 当前范围 + yml 默认值（页面据此判断是不是还没配过） */
    @GetMapping("/sources")
    public Map<String, Object> sources() {
        List<Map<String, Object>> saved = new ArrayList<>();
        for (KbWikiSourceRepository.Source s : store.all()) {
            saved.add(row(s.name(), s.kind(), s.enabled()));
        }
        List<Map<String, Object>> defaults = new ArrayList<>();
        for (String p : props.getWikiImport().getPrefixes()) {
            if (p != null && !p.isBlank()) {
                defaults.add(row(p.trim(), "prefix", true));
            }
        }
        for (String c : props.getWikiImport().getCategories()) {
            if (c != null && !c.isBlank()) {
                defaults.add(row(c.trim(), "category", true));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", saved);
        out.put("defaults", defaults);
        out.put("saved", !saved.isEmpty());
        out.put("maxPagesPerSource", props.getWikiImport().getMaxPagesPerSource());
        out.put("renderEnabled", props.getWikiImport().getRender().isEnabled());
        return out;
    }

    /** 保存范围：整表替换（页面上勾选的结果就是完整范围） */
    @PostMapping("/sources")
    public Map<String, Object> saveSources(@RequestBody Map<String, Object> body) {
        Object raw = body.get("items");
        List<KbWikiSourceRepository.Source> items = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    String name = String.valueOf(m.get("name") == null ? "" : m.get("name")).strip();
                    String kind = "prefix".equals(String.valueOf(m.get("kind"))) ? "prefix" : "category";
                    boolean enabled = !Boolean.FALSE.equals(m.get("enabled"));
                    if (!name.isEmpty()) {
                        items.add(new KbWikiSourceRepository.Source(name, kind, enabled, ""));
                    }
                }
            }
        }
        store.replace(items);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", store.count());
        out.put("enabled", store.enabled().size());
        return out;
    }

    /**
     * wiki 上的分类菜单。
     *
     * <p>带缓存：列全部分类只要一次请求，但"这个分类有多少正文页"要逐个数 ——
     * 40 个分类就是 40 次请求，页面每次打开都重算会把 wiki 打疼，也没必要。
     */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        Map<String, Object> cached = catalogCache;
        if (cached != null && System.currentTimeMillis() - catalogAt < CATALOG_TTL_MILLIS) {
            return cached;
        }
        // ⚠️ 先拷一份再排序：别假设上游给的是可变列表（List.of 那种一 sort 就抛）
        List<WikiApiClient.CategoryInfo> all = new ArrayList<>(client.listAllCategories());
        all.sort((a, b) -> Integer.compare(b.size(), a.size()));
        List<Map<String, Object>> rows = new ArrayList<>();
        // 分类名 -> 前 3 个页名（顺手从数页数那次请求里取，不多发请求）
        Map<String, List<String>> samples = new java.util.HashMap<>();
        int counted = 0;
        for (WikiApiClient.CategoryInfo c : all) {
            Integer pages = null;
            if (counted < CATALOG_COUNT_LIMIT) {
                try {
                    List<String> titles = client.listCategoryMembers(c.name(), 500).titles();
                    pages = titles.size();
                    samples.put(c.name(), titles.subList(0, Math.min(3, titles.size())));
                } catch (Exception e) {
                    log.warn("[KB-WIKI] 数分类 {} 的页数失败：{}", c.name(), e.getMessage());
                }
                counted++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("size", c.size());
            m.put("pages", pages);
            m.put("klass", klassOf(c.name()));
            m.put("advice", adviceOf(c.name()));
            // 示例页名：让"Crafting Materials"这种英文分类名一眼能看懂装的是什么。
            // 顺手就有 —— 数页数那次请求已经返回了标题，不多发请求。
            m.put("samples", samples.getOrDefault(c.name(), List.of()));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("total", rows.size());
        out.put("cachedAt", java.time.Instant.now().toString());
        catalogCache = out;
        catalogAt = System.currentTimeMillis();
        return out;
    }

    /** 触发一次导入 */
    @PostMapping("/import")
    public Map<String, Object> start(@RequestBody(required = false) Map<String, Object> body) {
        String mode = body == null ? "incremental" : String.valueOf(body.getOrDefault("mode", "incremental"));
        WikiImportService.Mode m = switch (mode) {
            case "dry-run" -> WikiImportService.Mode.DRY_RUN;
            case "force" -> WikiImportService.Mode.FORCE;
            case "purge" -> WikiImportService.Mode.PURGE;
            default -> WikiImportService.Mode.INCREMENTAL;
        };
        WikiImportService.Status st = service.start(m);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", true);
        out.put("status", statusMap(st));
        return out;
    }

    /** 轮询进度与报告 */
    @GetMapping("/import/status")
    public Map<String, Object> status() {
        return statusMap(service.status());
    }

    private static Map<String, Object> statusMap(WikiImportService.Status st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", st.state());
        m.put("phase", st.phase());
        m.put("done", st.done());
        m.put("total", st.total());
        m.put("startedAt", st.startedAt());
        m.put("finishedAt", st.finishedAt());
        m.put("report", st.report());
        m.put("errors", st.errors());
        return m;
    }

    /**
     * 分类的类别 —— 决定页面上给什么建议。
     *
     * <p>wiki 上 298 个分类里，**大多数不是内容**：维护清单（Pages with… / Maintenance/…）、
     * 模板清单、更新日期（Wake Of Water Update）、索引页（Item catalogs）。
     * 名字是英文的，光看名字分不出来，所以由后端判定 + 给中文建议，
     * 免得运维把几千页无关内容拉进语料。
     */
    private static String klassOf(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("pages with") || n.startsWith("pages using") || n.startsWith("maintenance/")
                || n.startsWith("templates/") || n.startsWith("missing ") || n.contains("broken file links")) {
            return "maintenance";
        }
        if (n.endsWith(" update") || n.equals("early access launch")) {
            return "update";
        }
        if (n.contains("catalog") || n.equals("music")) {
            return "index";
        }
        return "content";
    }

    private static String adviceOf(String name) {
        return switch (klassOf(name)) {
            case "maintenance" -> "维护清单：wiki 自己的记账页，装了模板的页都会被记进来。别选，会拉进几千页无关内容";
            case "update" -> "更新日期类：一页会同时属于它的主题分类，选它会和已导入的内容重复";
            case "index" -> "索引类：内容基本是链接与表格，清洗后会挤掉真正的物品块";
            default -> "内容类：可以选";
        };
    }

    private static Map<String, Object> row(String name, String kind, boolean enabled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("kind", kind);
        m.put("enabled", enabled);
        return m;
    }
}
