package com.example.qqbot.kb.category;

import com.example.qqbot.kb.KbCorpus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 分类服务：扫描原始分类 + 合并映射 + 输出给两端。

 * <p><b>扫描结果会缓存</b> —— chunks.jsonl 有 3.6 MB，每次请求都读太浪费。
 * 增量索引后会调 {@link #invalidate()} 让它重扫。
 */
@Service
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    /**
     * 缓存存活时间。
     *
     * <p>原来只靠"写入方记得调 invalidate()"来失效。现在写入路径变多了
     * （文档导入 / 管理端改块 / 投递都能改标签），漏一个就是**永久**显示旧分类，
     * 而且不报错。加一个 TTL 让它自愈：最坏情况是分类列表晚 30 秒才变，
     * 而分类是给人浏览用的，30 秒无所谓。
     */
    private static final long CACHE_TTL_MS = 30_000;

    private final CategoryStore store;
    private final ObjectMapper mapper;
    private final KbCorpus corpus;

    /** 缓存：原始分类 → 条目数 */
    private final AtomicReference<Map<String, Integer>> rawCache = new AtomicReference<>();
    private volatile long rawCachedAt;

    /**
     * 缓存：原始分类 → 中文标签。
     *
     * <p>公开站的搜索/详情页会把**每条结果的标签**翻成中文，一次请求几十条 → 几十次
     * {@code rawToLabel()}。不加缓存就是几十次 SQLite 读，纯属白花；加上 TTL 之后
     * 分类改动最多晚 30 秒生效，而它本来就是给人浏览的。
     */
    private final AtomicReference<Map<String, String>> labelCache = new AtomicReference<>();
    private volatile long labelCachedAt;

    public CategoryService(CategoryStore store, ObjectMapper mapper,
                           KbCorpus corpus) {
        this.store = store;
        this.mapper = mapper;
        this.corpus = corpus;
    }

    /** 让缓存失效（增量索引后调用） */
    public void invalidate() {
        rawCache.set(null);
        labelCache.set(null);
    }

    /**
     * 统计每个原始分类（= 标签）有多少条目。
     *
     * <p><b>为什么不再读 chunks.jsonl</b>：那是旧存储。切到块表之后那个文件不再被维护，
     * 读它只会得到一份**永远不变**的旧统计 —— 而且不报错。
     * 现在统一从 {@link KbCorpus} 取：旧存储 = chunks.jsonl，新存储 = 块表，
     * 换存储不用改这里。
     *
     * <p>一个条目可能属于多个分类，所以「各分类条目数之和」会大于总条目数。
     */
    public Map<String, Integer> rawCounts() {
        Map<String, Integer> cached = rawCache.get();
        if (cached != null && System.currentTimeMillis() - rawCachedAt < CACHE_TTL_MS) {
            return cached;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        try {
            for (KbCorpus.Entry e : corpus.entries()) {
                for (String c : e.tags()) {
                    String k = c == null ? "" : c.trim();
                    if (!k.isEmpty()) {
                        counts.merge(k, 1, Integer::sum);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[KB-CAT] 扫描分类失败：{}", e.getMessage());
        }
        log.info("[KB-CAT] 扫描到 {} 个原始分类", counts.size());
        rawCache.set(counts);
        rawCachedAt = System.currentTimeMillis();
        return counts;
    }

    /** 一个原始分类的完整视图（管理端用） */
    public record RawItem(String raw, int count, String groupKey, String groupLabel,
                          String labelZh, boolean custom, boolean hidden) {
    }

    /**
     * 管理端列表：所有原始分类 + 当前归属 + 是否被手动改过。
     */
    public List<RawItem> listAll() {
        Map<String, Integer> counts = rawCounts();
        Map<String, CategoryStore.Mapping> custom = store.loadAll();

        List<RawItem> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String raw = e.getKey();
            CategoryStore.Mapping m = custom.get(raw);
            boolean isCustom = m != null;
            String key = isCustom ? m.groupKey() : KbGroups.autoGroup(raw);
            String label = isCustom && m.labelZh() != null && !m.labelZh().isBlank()
                    ? m.labelZh()
                    : (KbGroups.HIDDEN.equals(key) ? "" : KbGroups.get(key).label());

            out.add(new RawItem(raw, e.getValue(), key,
                    KbGroups.HIDDEN.equals(key) ? "（隐藏）" : KbGroups.get(key).label(),
                    label, isCustom, KbGroups.HIDDEN.equals(key)));
        }
        // 条目多的排前面
        out.sort(Comparator.comparingInt(RawItem::count).reversed());
        return out;
    }

    /** 一个大类的统计（管理端总览 + 公开站展示） */
    public record GroupStat(String key, String label, String icon, String desc,
                            int rawCount, int chunkCount) {
    }

    /**
     * 按大类统计。
     *
     * <p>注意「条目数」用的是**原始分类的条目数之和**（会有重复计数，
     * 因为一个条目可能同时属于多个分类）。这个数字只用于展示规模，不当精确值。
     */
    public List<GroupStat> groupStats() {
        List<RawItem> all = listAll();
        Map<String, int[]> acc = new LinkedHashMap<>();
        for (KbGroups.Group g : KbGroups.ALL) {
            acc.put(g.key(), new int[]{0, 0});
        }
        for (RawItem it : all) {
            int[] a = acc.get(it.groupKey());
            if (a != null) {
                a[0]++;
                a[1] += it.count();
            }
        }
        List<GroupStat> out = new ArrayList<>();
        for (KbGroups.Group g : KbGroups.ALL) {
            int[] a = acc.get(g.key());
            // 「其他」和「隐藏」不展示给玩家，但仍要出现在管理端
            out.add(new GroupStat(g.key(), g.label(), g.icon(), g.desc(), a[0], a[1]));
        }
        return out;
    }

    /** 全部原始分类 → 大类的映射（公开站筛选用） */
    public Map<String, String> rawToGroup() {
        Map<String, CategoryStore.Mapping> custom = store.loadAll();
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : rawCounts().keySet()) {
            CategoryStore.Mapping m = custom.get(raw);
            out.put(raw, m != null ? m.groupKey() : KbGroups.autoGroup(raw));
        }
        return out;
    }

    /**
     * 中文标签。三层优先级：
     * <ol>
     *   <li>管理员在后台设置的名字（最高）</li>
     *   <li>内置中文词典（约 110 条，覆盖九成条目）</li>
     *   <li>都没有 → 不返回，调用方决定退回英文还是隐藏</li>
     * </ol>
     */
    public Map<String, String> rawToLabel() {
        Map<String, String> cached = labelCache.get();
        if (cached != null && System.currentTimeMillis() - labelCachedAt < CACHE_TTL_MS) {
            return cached;
        }
        Map<String, CategoryStore.Mapping> custom = store.loadAll();
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : rawCounts().keySet()) {
            CategoryStore.Mapping m = custom.get(raw);
            if (m != null && m.labelZh() != null && !m.labelZh().isBlank()) {
                out.put(raw, m.labelZh());
                continue;
            }
            String builtin = KbGroups.labelZh(raw);
            if (builtin != null) {
                out.put(raw, builtin);
            }
        }
        labelCache.set(out);
        labelCachedAt = System.currentTimeMillis();
        return out;
    }

    // ==================== 分类穿透 ====================

    /**
     * 穿透列表里的一条：一个「条目」= 一个可检索块。
     *
     * @param id        块 id（如 {@code flame-altar-0}）
     * @param docId     所属文档 slug
     * @param title     标题
     * @param url       出处
     * @param tags      该条目身上的全部原始分类
     * @param curated   是否为我们自己维护的内容
     * @param contentAt 内容时间
     * @param snippet   正文摘要（压平空白后截断，仅供列表预览）
     */
    public record EntryItem(String id, String docId, String title, String url,
                            List<String> tags, boolean curated, String contentAt, String snippet) {
    }

    /** 穿透结果的一页 */
    public record EntryPage(int total, List<EntryItem> items) {
    }

    /** 摘要截断长度。列表只用来认人，不追求把正文看全。 */
    private static final int SNIPPET_MAX = 120;

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() <= SNIPPET_MAX ? s : s.substring(0, SNIPPET_MAX) + "…";
    }

    /**
     * 分类穿透：某个原始分类（或某个大类）下，具体有哪些条目。
     *
     * <p><b>为什么必须和 {@link #rawCounts()} 用同一份数据</b>：面板上那个
     * 「N 条目」就是这里数出来的。如果这里改成读别的存储（比如原来的
     * chunks.jsonl），点进去的条数会和卡片上的数字对不上 —— 用户第一反应
     * 就是「你这统计是假的」。所以两边都走 {@link KbCorpus#entries()}，
     * 而且过滤口径完全一致（标签 trim 后相等 / 同一套大类映射）。
     *
     * <p>一个条目可能同时属于多个分类，所以按大类穿透时会比按原始分类穿透
     * 多出一些条目 —— 这是对的，它本来就在那个大类里。
     *
     * @param raw      原始分类名；非空时按它精确匹配
     * @param groupKey 大类 key；raw 为空时按它匹配（走 {@link #rawToGroup()} 的映射）
     * @param q        可选的二次搜索（标题 / id / 文档 / 正文，忽略大小写）
     */
    public EntryPage entries(String raw, String groupKey, String q, int limit, int offset) {
        String rawKey = raw == null ? "" : raw.trim();
        String group = groupKey == null ? "" : groupKey.trim();
        String kw = q == null ? "" : q.trim().toLowerCase();
        Map<String, String> groupOf = group.isEmpty() ? Map.of() : rawToGroup();

        List<EntryItem> hit = new ArrayList<>();
        for (KbCorpus.Entry e : corpus.entries()) {
            if (!inScope(e, rawKey, group, groupOf)) {
                continue;
            }
            if (!kw.isEmpty()) {
                String hay = (nz(e.title()) + " " + nz(e.id()) + " " + nz(e.docId()) + " " + nz(e.text()))
                        .toLowerCase();
                if (!hay.contains(kw)) {
                    continue;
                }
            }
            hit.add(new EntryItem(e.id(), e.docId(), e.title(), e.url(), e.tags(),
                    e.curated(), e.contentAt(), snippet(e.text())));
        }
        hit.sort(Comparator.comparing(x -> nz(x.title())));

        int total = hit.size();
        int from = Math.max(0, Math.min(offset, total));
        int to = Math.max(from, Math.min(from + Math.max(1, limit), total));
        return new EntryPage(total, new ArrayList<>(hit.subList(from, to)));
    }

    private static boolean inScope(KbCorpus.Entry e, String rawKey, String group,
                                   Map<String, String> groupOf) {
        for (String t : e.tags()) {
            String k = t == null ? "" : t.trim();
            if (k.isEmpty()) {
                continue;
            }
            if (!rawKey.isEmpty()) {
                if (k.equals(rawKey)) {
                    return true;
                }
            } else if (!group.isEmpty()) {
                String g = groupOf.getOrDefault(k, KbGroups.autoGroup(k));
                if (group.equals(g)) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
