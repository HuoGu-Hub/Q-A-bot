package com.example.qqbot.kb.category;

import com.example.qqbot.kb.TermNames;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 分类服务 —— <b>读分块符号里的标签，认得的才算分类</b>。
 *
 * <h2>和改造前的区别</h2>
 * 原来它把 600 多个原始标签"智能归类"到 9 个大类，规则是一整套子串启发式，
 * 分错得很厉害。现在：
 * <ul>
 *   <li>分类是一份**人工维护的清单**（{@link CategoryStore}），可以新增 / 改名 / 删除；</li>
 *   <li>词条的分块符号里带什么标签，它就归到什么分类；</li>
 *   <li>标签不在清单里（或压根没带标签）→ **未分类**。不做任何猜测。</li>
 * </ul>
 *
 * <p>wiki 清洗语料是例外：导入时会把来源名与页面自身的分类**自动登记**进清单
 * （见 {@link #ensure}），所以它们天然有分类。
 */
@Service
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    /** 没有分类（或标签认不出）时的兜底名。它不是一个真实分类，不落库、不可删除 */
    public static final String UNCATEGORIZED = "未分类";

    /** 群友贡献内容的固定分类：聊天蒸馏、人工沉淀都归这里 */
    public static final String CONTRIB = "大佬攻略";

    /**
     * 清单的短缓存。
     *
     * <p>统计与穿透要对**每个词条**判一次"它属于哪些分类"，而清单在库里 ——
     * 不缓存就是每个词条一次 SQLite 往返（3000 条 = 3000 次查询）。
     * 分类是给人浏览的，几秒的延迟无所谓；写入时立刻失效。
     */
    private static final long CACHE_TTL_MS = 5000;

    private final CategoryStore store;
    private final KbBlockStore blocks;

    private volatile Map<String, String> labelCache;
    private volatile long labelCachedAt;

    public CategoryService(CategoryStore store, KbBlockStore blocks) {
        this.store = store;
        this.blocks = blocks;
    }

    /** 当前清单（名字 → 显示名），带短缓存 */
    private Map<String, String> knownLabels() {
        Map<String, String> cached = labelCache;
        if (cached != null && System.currentTimeMillis() - labelCachedAt < CACHE_TTL_MS) {
            return cached;
        }
        Map<String, String> fresh = store.labels();
        labelCache = fresh;
        labelCachedAt = System.currentTimeMillis();
        return fresh;
    }

    private void invalidate() {
        labelCache = null;
    }

    /** 一个分类的规模 */
    public record Stat(String name, String label, int count) {
    }

    /** 分类穿透里的一条 —— 一个条目 = 一个分块 */
    public record EntryItem(String id, String docId, String title, String alias,
                            List<String> categories, String source, String snippet) {
    }

    public record EntryPage(int total, List<EntryItem> items) {
    }

    public boolean isAvailable() {
        return store.isAvailable();
    }

    // ==================== 清单 ====================

    /** 全部分类名（按清单顺序） */
    public List<String> catalogNames() {
        List<String> out = new ArrayList<>();
        for (CategoryStore.Entry e : store.all()) {
            out.add(e.name());
        }
        return out;
    }

    /** 分类名 → 显示名；不在清单里返回 {@code null} */
    public String labelOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return knownLabels().get(name.trim());
    }

    /**
     * 新增一个分类。
     *
     * @return false = 名字为空、与「未分类」重名、已存在或存储不可用
     */
    public boolean add(String name, String label) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty() || UNCATEGORIZED.equals(key) || knownLabels().containsKey(key)) {
            return false;
        }
        boolean ok = store.upsert(key, label == null || label.isBlank() ? key : label.trim());
        if (ok) {
            invalidate();
            log.info("[KB-CAT] 新增分类「{}」", key);
        }
        return ok;
    }

    /** 改显示名（分类名是分块符号里的标签，不改） */
    public boolean rename(String name, String label) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty() || !knownLabels().containsKey(key)) {
            return false;
        }
        boolean ok = store.upsert(key, label == null ? "" : label.trim());
        if (ok) {
            invalidate();
        }
        return ok;
    }

    /** 删除一个分类 —— 词条不会消失，只是回到「未分类」 */
    public boolean remove(String name) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty() || UNCATEGORIZED.equals(key)) {
            return false;
        }
        boolean ok = store.remove(key);
        if (ok) {
            invalidate();
            log.info("[KB-CAT] 删除分类「{}」（其下词条回到未分类）", key);
        }
        return ok;
    }

    /**
     * 把一批分类名登记进清单（已存在的跳过）—— **wiki 导入专用**。
     *
     * <p>文档导入**不调它**：那是人工的清单，标签不认识就归未分类。
     * 而 wiki 抓取来源有几百个页面分类，没人会去手工预先建好。
     *
     * @return 新登记了几个
     */
    public int ensure(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return 0;
        }
        Map<String, String> known = knownLabels();
        int added = 0;
        for (String raw : new LinkedHashSet<>(names)) {
            String key = raw == null ? "" : raw.trim();
            if (key.isEmpty() || UNCATEGORIZED.equals(key) || known.containsKey(key)) {
                continue;
            }
            if (store.upsert(key, key)) {
                known.put(key, key);
                added++;
            }
        }
        if (added > 0) {
            invalidate();
            log.info("[KB-CAT] 自动登记 {} 个 wiki 分类", added);
        }
        return added;
    }

    // ==================== 归属 ====================

    /**
     * 一个词条**真正认得**的分类 —— 标签与清单的交集。
     *
     * <p>空表 = 未分类。这就是"不做智能分类"的全部含义。
     */
    public List<String> recognized(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        Map<String, String> known = knownLabels();
        if (known.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : tags) {
            if (t == null) {
                continue;
            }
            String key = t.trim();
            if (key.isEmpty() || UNCATEGORIZED.equals(key)) {
                continue;
            }
            if (known.containsKey(key)) {
                out.add(key);
            }
        }
        return new ArrayList<>(out);
    }

    /** 显示名列表（未分类时返回空表，由调用方决定显示「未分类」） */
    public List<String> displayNames(List<String> tags) {
        Map<String, String> labels = knownLabels();
        List<String> out = new ArrayList<>();
        for (String name : recognized(tags)) {
            String label = labels.getOrDefault(name, name);
            if (!label.isBlank() && !out.contains(label)) {
                out.add(label);
            }
        }
        return out;
    }

    // ==================== 统计 / 穿透 ====================

    /** 每个分类有多少词条（凭标签精确匹配，不做任何推断） */
    public List<Stat> stats() {
        Map<String, String> labels = knownLabels();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : labels.keySet()) {
            counts.put(name, 0);
        }
        for (KbBlock b : blocks.all()) {
            for (String c : recognized(b.tags())) {
                counts.merge(c, 1, Integer::sum);
            }
        }
        List<Stat> out = new ArrayList<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            out.add(new Stat(e.getKey(), e.getValue(), counts.getOrDefault(e.getKey(), 0)));
        }
        return out;
    }

    /** 未分类的词条数 */
    public int uncategorizedCount() {
        int n = 0;
        for (KbBlock b : blocks.all()) {
            if (recognized(b.tags()).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static final int SNIPPET_MAX = 120;

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() <= SNIPPET_MAX ? s : s.substring(0, SNIPPET_MAX) + "…";
    }

    /**
     * 分类穿透：某个分类（或未分类）下有哪些词条。
     *
     * <p>{@code category} 为空 = 不按分类过滤；{@link #UNCATEGORIZED} = 只看没分类的。
     */
    public EntryPage entries(String category, String q, int limit, int offset) {
        String cat = category == null ? "" : category.trim();
        String kw = q == null ? "" : q.trim().toLowerCase();
        boolean uncategorized = UNCATEGORIZED.equals(cat);
        boolean all = cat.isEmpty();

        List<EntryItem> hit = new ArrayList<>();
        for (KbBlock b : blocks.all()) {
            List<String> cats = recognized(b.tags());
            if (!all) {
                if (uncategorized) {
                    if (!cats.isEmpty()) {
                        continue;
                    }
                } else if (!cats.contains(cat)) {
                    continue;
                }
            }
            if (!kw.isEmpty()) {
                String hay = (nz(b.title()) + " " + nz(b.id().value()) + " " + nz(b.docId())
                        + " " + nz(b.aliasOrEmpty()) + " " + nz(b.body())).toLowerCase();
                if (!hay.contains(kw)) {
                    continue;
                }
            }
            hit.add(new EntryItem(b.id().value(), b.docId(), b.title(),
                    TermNames.effectiveAlias(b.title(), b.aliasOrEmpty()),
                    cats, b.source(), snippet(b.body())));
        }
        hit.sort(Comparator.comparing(x -> nz(x.title())));

        int total = hit.size();
        int from = Math.max(0, Math.min(offset, total));
        int to = Math.max(from, Math.min(from + Math.max(1, limit), total));
        return new EntryPage(total, new ArrayList<>(hit.subList(from, to)));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
