package com.example.qqbot.kb.wiki;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.persistence.KbWikiSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * wiki 拉取范围与触发接口（管理后台）。
 *
 * <p>这个控制器就是"把命令行搬进页面"的那一层：
 * <ul>
 *   <li>GET  /sources —— 当前范围（页面上勾选的结果，没配过则回显 yml 默认值）
 *       + 每个来源在知识库里的**生效情况**（入库块数 / 最近同步时间）；</li>
 *   <li>POST /sources —— 保存范围（整表替换）；</li>
 *   <li>GET  /catalog —— wiki 上有什么分类、各多少页（菜单，带缓存）；</li>
 *   <li>POST /import —— 触发一次拉取 + 清洗（空跑 / 增量 / 强制重洗）；</li>
 *   <li>GET  /import/status —— 轮询进度与报告。</li>
 * </ul>
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/kb/wiki")
public class KbWikiController {

    private static final Logger log = LoggerFactory.getLogger(KbWikiController.class);

    /** 菜单缓存时长：wiki 上分类不会分钟级变化，而示例页名要逐个数 */
    private static final long CATALOG_TTL_MILLIS = 10 * 60 * 1000L;

    /**
     * 给多少个分类取"前三个示例页名"。
     *
     * <p>页数不再需要逐个数 —— {@code prop=categoryinfo} 一次能问 50 个分类。
     * 但"示例页名"只能一个分类一次请求，所以只给**内容类**的前若干个取：
     * 非内容类在页面上没有「选它」，取来没人看。
     */
    private static final int CATALOG_SAMPLE_LIMIT = 24;

    private final KbWikiSourceStore store;
    private final WikiApiClient client;
    private final WikiImportService service;
    private final KbPolicy props;
    private final KbBlockStore blockStore;
    private final KbWikiPageStore pageStore;

    private volatile Map<String, Object> catalogCache;
    private volatile long catalogAt;

    public KbWikiController(KbWikiSourceStore store, WikiApiClient client,
                            WikiImportService service, KbPolicy props,
                            KbBlockStore blockStore, KbWikiPageStore pageStore) {
        this.store = store;
        this.client = client;
        this.service = service;
        this.props = props;
        this.blockStore = blockStore;
        this.pageStore = pageStore;
    }

    /**
     * 每个来源在知识库里的**生效情况** —— 页面上每个来源都要显示"入库了没有"。
     *
     * <p>返回 {@code {来源名: {blocks: N, lastSync: "..."}}}：
     * <ul>
     *   <li>{@code blocks} —— 该来源下 {@code retired = 0} 的块数。块按
     *       {@code wiki·<来源名>} 分组（见 {@code WikiArticleImporter}），数一遍就知道漏没漏；</li>
     *   <li>{@code lastSync} —— 该来源最近一次同步时间，用来区分"从没拉过"和
     *       "拉过但清洗后没正文"（这两种 {@code blocks} 都是 0）。</li>
     * </ul>
     */
    private Map<String, Object> coverage() {
        String docPrefix = props.getWikiImport().getDocIdPrefix() + "·";
        Map<String, Integer> blocks = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Integer> e
                    : blockStore.activeCountByDoc(WikiArticleImporter.SRC_WIKI).entrySet()) {
                if (e.getKey().startsWith(docPrefix)) {
                    blocks.put(e.getKey().substring(docPrefix.length()), e.getValue());
                }
            }
        } catch (Exception e) {
            log.warn("[KB-WIKI] 统计来源入库块数失败：{}", e.getMessage());
        }
        Map<String, String> lastSync = Map.of();
        try {
            lastSync = pageStore.lastSyncBySource();
        } catch (Exception e) {
            log.warn("[KB-WIKI] 统计来源最近同步时间失败：{}", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        LinkedHashSet<String> names = new LinkedHashSet<>(blocks.keySet());
        names.addAll(lastSync.keySet());
        for (String name : names) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blocks", blocks.getOrDefault(name, 0));
            m.put("lastSync", lastSync.getOrDefault(name, ""));
            out.put(name, m);
        }
        return out;
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
        // 页面上的"点开看"链接用：api-base 去掉 /api.php 就是站点根
        out.put("wikiBase", props.getMapSync().getApiBase().replace("/api.php", ""));
        out.put("renderEnabled", props.getWikiImport().getRender().isEnabled());
        // 知识库里的生效情况（未缓存：导入完必须立刻反映出来）
        out.put("coverage", coverage());
        return out;
    }

    /** 保存范围：整表替换（页面上勾选的结果就是完整范围） */
    @PutMapping("/sources")
    public Map<String, Object> saveSources(@RequestBody SourcesRequest body) {
        List<KbWikiSourceRepository.Source> items = new ArrayList<>();
        if (body.items() != null) {
            for (SourceItem item : body.items()) {
                if (item == null || item.name() == null || item.name().isBlank()) {
                    continue;
                }
                String kind = "prefix".equals(item.kind()) ? "prefix" : "category";
                boolean enabled = item.enabled() == null || item.enabled();
                items.add(new KbWikiSourceRepository.Source(item.name().strip(), kind, enabled, ""));
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
     * <h2>页数怎么来的（2026-10-17 改）</h2>
     * 旧实现用 {@code list=categorymembers} 逐个分类去数，一次请求只数一个 ——
     * 所以只敢数前 40 个，页面上**其余分类的页数全是空的**（看着像没读取）。
     * 现在用 {@code prop=categoryinfo} 批量问（一次 50 个），每个分类都有准确页数；
     * 它给的 {@code pages} 已经排除了子分类与文件，正是"选它大约会拉多少篇正文"。
     *
     * <p>仍然逐个请求的只剩"前三个示例页名"，且只给内容类的前若干个。
     */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        Map<String, Object> cached = catalogCache;
        if (cached != null && System.currentTimeMillis() - catalogAt < CATALOG_TTL_MILLIS) {
            return cached;
        }
        // ⚠️ 先拷一份再排序：别假设上游给的是可变列表（List.of 那种一 sort 就抛）
        List<WikiApiClient.CategoryInfo> all = new ArrayList<>(client.listAllCategories());

        // 批量取每个分类的文章页数；整批失败就退回成员数（含子分类与文件，只作参考）
        List<String> names = new ArrayList<>(all.size());
        for (WikiApiClient.CategoryInfo c : all) {
            names.add(c.name());
        }
        Map<String, Integer> loaded;
        try {
            loaded = client.fetchCategoryPages(names);
        } catch (Exception e) {
            log.warn("[KB-WIKI] 取分类页数失败，页数退回成员数：{}", e.getMessage());
            loaded = Map.of();
        }
        // lambda 捕获的局部变量必须 effectively final —— 上面的 try/catch 赋值两次，所以这里拷一份
        final Map<String, Integer> pagesByName = loaded;

        // 按"会拉多少篇正文"排序 —— 大分类排前面，挑起来才有感觉
        all.sort((a, b) -> Integer.compare(
                pagesByName.getOrDefault(b.name(), b.size()),
                pagesByName.getOrDefault(a.name(), a.size())));

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, List<String>> samples = new java.util.HashMap<>();
        int sampled = 0;
        for (WikiApiClient.CategoryInfo c : all) {
            String klass = klassOf(c.name());
            if (sampled < CATALOG_SAMPLE_LIMIT && "content".equals(klass)) {
                try {
                    // ⚠️ collect 在"没有下一页令牌"时会返回整页（最多 500 条），不认 cap，
                    //    所以这里自己截前三个，别把 500 个页名塞进响应
                    List<String> titles = client.listCategoryMembers(c.name(), 3).titles();
                    samples.put(c.name(), titles.subList(0, Math.min(3, titles.size())));
                } catch (Exception e) {
                    log.warn("[KB-WIKI] 取分类 {} 的示例页失败：{}", c.name(), e.getMessage());
                }
                sampled++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("size", c.size());
            m.put("pages", pagesByName.get(c.name()));
            m.put("klass", klass);
            m.put("advice", adviceOf(c.name()));
            // 示例页名：让"Crafting Materials"这种英文分类名一眼能看懂装的是什么
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
    @PostMapping("/imports")
    public Map<String, Object> start(@RequestBody(required = false) StartImportRequest body) {
        String mode = body == null || body.mode() == null ? "incremental" : body.mode();
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
    @GetMapping("/imports/status")
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

    public record SourceItem(String name, String kind, Boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record SourcesRequest(List<SourceItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record StartImportRequest(String mode) {
    }
}
