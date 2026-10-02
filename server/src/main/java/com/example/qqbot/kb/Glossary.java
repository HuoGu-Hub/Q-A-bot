package com.example.qqbot.kb;

import com.example.qqbot.kb.term.KbTermStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中英术语对照 —— B 路关键词检索的燃料。
 *
 * <p>解决的是实测到的一个具体问题：「废料杯怎么合成」纯靠向量会把
 * Crucible / Smelter 这类"同属合成语义"的页面排在前面，真正的 Scrap Cup 掉到第 4。
 * 有了「废料杯 → Scrap Cup」这张对照，就能直接把中文问题锚定到英文页面上。
 *
 * <h2>数据从哪来（2026-09-27 改造）</h2>
 * 以前本类自己去读 {@code data/kb/glossary/zh-en.tsv}，于是同一份数据被
 * 本类、{@code GlossaryStore}（后台写）、{@code KbTermService}（列表组装）
 * 各自解析一遍。现在只有一个来源：{@link KbTermStore}（SQLite 单表）。
 *
 * <h2>为什么不需要调用方记得刷新</h2>
 * 每次取用前比对 {@link KbTermStore#version()}：写入方一改，版本号就变，
 * 本类下一次查询自动重载。旧实现靠每个写方法末尾手动 {@code glossary.reload()}，
 * 新增一个写入口就漏一个，漏了不报错 —— 只是后台改完词，机器人一直在用旧的。
 *
 * <p>只加载状态不是 rejected 的行；没有可用数据时返回空（B 路退化成只认英文词）。
 */
@Component
public class Glossary {

    private static final Logger log = LoggerFactory.getLogger(Glossary.class);

    /**
     * 中文片段匹配的长度范围（字）。
     *
     * <p>1 字太泛（"火" 能匹配一大片）；4 字以上基本白做 —— 片段是按长度从小到大试的，
     * 一个名字只要含任意 2 字片段就命中了，长片段只在"恰好没有任何 2 字交集"时才有用，
     * 实测那一档几乎不发生。所以上限压到 3：**实测最差耗时从 50ms 降到 20ms 以内**。
     */
    private static final int FRAG_MIN = 2;
    private static final int FRAG_MAX = 3;

    /**
     * 一个片段命中多少个名字以上就算"太泛"，整个丢掉。
     *
     * <p>等于一个便宜的停用词表：不写死「什么/怎么/这个」，而是看它在**当前词表里**
     * 到底泛不泛 —— 语料换了（比如满屏「材料」），判断自动跟着变。
     */
    private static final int FRAG_TOO_COMMON = 40;

    /** 片段匹配最多补几个名字：多了只是白撑大关键词路的扫描量，收益递减 */
    private static final int FRAG_MAX_TERMS = 8;

    /** 问句里最多取多少片段（长问句会切出上百个，没必要全试） */
    private static final int FRAG_MAX_TRIES = 40;

    /** 查询里出现的英文单词（长度 >= 3），用于用户直接打英文的场景 */
    private static final Pattern ASCII_WORD = Pattern.compile("[A-Za-z][A-Za-z'\\-]{2,}");

    private final KbTermStore store;

    private volatile boolean loaded;
    private volatile long loadedVersion = -1;
    private volatile List<Map.Entry<String, String>> entries = List.of();

    public Glossary(KbTermStore store) {
        this.store = store;
    }

    /** 一个问题里命中的游戏名词 */
    public record Term(String zh, String en) {
    }

    /**
     * 找出文本里出现的所有游戏名词（中文名 + 对应英文名）。
     *
     * <p>统计模块靠它做「关键词提问排行」—— 复用同一份术语表，
     * 和检索的口径天然一致，不需要额外维护第二份词表。
     */
    public List<Term> matchChinese(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        ensureLoaded();

        // 最长优先：否则「爆炸箭 III」会同时命中「爆炸箭 I」和「爆炸箭 II」，
        // 关键词统计立刻失真。按中文名长度降序匹配，已被覆盖的区间不再匹配短的。
        List<Map.Entry<String, String>> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> b.getKey().length() - a.getKey().length());

        boolean[] used = new boolean[text.length()];
        List<Term> out = new ArrayList<>();
        for (Map.Entry<String, String> e : sorted) {
            String zh = e.getKey();
            int at = text.indexOf(zh);
            while (at >= 0) {
                boolean overlap = false;
                for (int i = at; i < at + zh.length() && i < used.length; i++) {
                    if (used[i]) {
                        overlap = true;
                        break;
                    }
                }
                if (!overlap) {
                    out.add(new Term(zh, e.getValue()));
                    for (int i = at; i < at + zh.length() && i < used.length; i++) {
                        used[i] = true;
                    }
                    break;
                }
                at = text.indexOf(zh, at + 1);
            }
        }
        return out;
    }

    /** 把中文问题扩展成可用于关键词匹配的英文词列表 */
    public List<String> expand(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        ensureLoaded();
        Set<String> terms = new LinkedHashSet<>();

        // 中文出现在问题里 → 把这一条的**英文名和所有中文写法**都加进来。
        //
        // 为什么两种都给：知识库可能是英文语料（旧存储），也可能是中文语料（导入的文档）。
        // 只给英文名的话，切到中文语料后关键词路会**静默失配** —— 拿 "Kiln" 去中文正文里
        // 找，一条也匹配不到，而向量路照常返回结果，所以谁都不会发现。
        for (Term t : matchChinese(query)) {
            terms.add(t.en());
            for (String raw : t.zh().split("[、/｜|]")) {
                String name = raw.trim();
                // 单字别名在中文里是子串匹配，噪声太大（"火" 能匹配一大片），不收
                if (name.length() >= 2) {
                    terms.add(name);
                }
            }
        }
        // ★ 中文**子串**匹配：不依赖人工写别名也能命中。
        //   群友常说的是名字的一部分（"抱枕怎么得" ↔ 「大型农场动物抱枕」），
        //   而词表里存的是全名，精确匹配对不上 —— 这一层就是补这个缺口。
        terms.addAll(byChineseFragment(query, terms));

        // 用户直接打英文的情况（"Explosive Arrow III 怎么得"）
        Matcher m = ASCII_WORD.matcher(query);
        while (m.find()) {
            String word = m.group();
            // 罗马数字没有区分度：III 能匹配到一大片 "XXX III" 的装备
            if (word.matches("(?i)[ivxlcdm]+")) {
                continue;
            }
            terms.add(word);
        }
        return new ArrayList<>(terms);
    }

    /**
     * 中文**子串**匹配：问句里的短词是某个名字的一部分时，把那个名字也算命中。
     *
     * <p>解决的问题：「抱枕怎么得」对着词条「大型农场动物抱枕」—— 精确匹配（{@link #matchChinese}）
     * 要求整条名字出现在问句里，而群友只会说尾部那个词。没有这一层，就只能靠人给每条词条手写别名，
     * 而词条是导入文档自动生成的，没人会去补 2,600 条的别名。
     *
     * <p>做法：把问句切成 2~4 字的连续中文片段，看哪个名字含有它；
     * **命中名字太多的片段整段丢掉**（便宜版停用词，见 {@link #FRAG_TOO_COMMON}）。
     *
     * <p>返回的是**名字本身**（如「大型农场动物抱枕」），交给关键词路去和块标题/正文比 ——
     * 名字就是块标题，所以能吃到标题那一份高权重。
     */
    private List<String> byChineseFragment(String query, Set<String> already) {
        Set<String> frags = new LinkedHashSet<>();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < query.length() && frags.size() < FRAG_MAX_TRIES; i++) {
            char c = query.charAt(i);
            if (isCjk(c)) {
                run.append(c);
            } else {
                collectFragments(frags, run);
            }
        }
        collectFragments(frags, run);
        if (frags.isEmpty()) {
            return List.of();
        }

        // 一趟扫完：每个名字挂在它命中的**第一个**片段上，同时数出片段的命中数
        Map<String, Integer> hits = new LinkedHashMap<>();
        Map<String, List<String>> matched = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries) {
            String name = e.getKey();
            if (name.length() < FRAG_MIN || already.contains(name)) {
                continue;
            }
            for (String f : frags) {
                if (name.contains(f)) {
                    hits.merge(f, 1, Integer::sum);
                    matched.computeIfAbsent(f, k -> new ArrayList<>()).add(name);
                    break;
                }
            }
        }

        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> h : hits.entrySet()) {
            if (h.getValue() > FRAG_TOO_COMMON) {
                continue;   // 太泛，丢
            }
            for (String name : matched.get(h.getKey())) {
                if (out.size() >= FRAG_MAX_TERMS) {
                    return out;
                }
                if (!out.contains(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /** 把一段连续中文切成 2~4 字的片段 */
    private static void collectFragments(Set<String> out, StringBuilder run) {
        int n = run.length();
        for (int len = FRAG_MIN; len <= FRAG_MAX; len++) {
            for (int i = 0; i + len <= n; i++) {
                out.add(run.substring(i, i + len));
            }
        }
        run.setLength(0);
    }

    private static boolean isCjk(char c) {
        return c >= 0x4e00 && c <= 0x9fff;
    }

    /** 术语条目数（供启动日志/排查） */
    public int size() {
        ensureLoaded();
        return entries.size();
    }

    /** 全部条目（后台展示用） */
    public List<Map.Entry<String, String>> all() {
        ensureLoaded();
        return List.copyOf(entries);
    }

    /**
     * 强制重新加载。
     *
     * <p>正常路径**不需要**调它 —— {@link #ensureLoaded()} 会比版本号自动感知写入。
     * 留着是为了让测试和排查能显式刷新。
     */
    public void reload() {
        synchronized (this) {
            loaded = false;
            loadedVersion = -1;
            entries = List.of();
        }
        log.info("[KB] 术语表已重新加载：{} 条", size());
    }

    private void ensureLoaded() {
        long v = store.version();
        if (loaded && loadedVersion == v) {
            return;
        }
        synchronized (this) {
            if (loaded && loadedVersion == v) {
                return;
            }
            try {
                entries = load();
                loadedVersion = v;
            } catch (Exception e) {
                log.warn("[KB] 加载术语表失败，B 路退化为只认英文词：{}", e.getMessage());
                entries = List.of();
                loadedVersion = v;
            } finally {
                loaded = true;
            }
        }
    }

    private List<Map.Entry<String, String>> load() {
        if (!store.isAvailable()) {
            log.info("[KB] 词条存储不可用，B 路将只认英文词");
            return List.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (KbTermStore.Entry e : store.list()) {
            if (e.en().isEmpty() || "rejected".equalsIgnoreCase(e.status())) {
                continue;
            }
            // 中文名可以写多个别名，用「、」或「/」分隔 —— 人工核对时很有用。
            // 例如 Flame Altar 的官方译名可能是「火焰祭坛」，但群友习惯叫「灵火祭坛」，
            // 写「灵火祭坛、火焰祭坛」两个都能命中。
            for (String alias : e.zh().split("[、/｜|]")) {
                String name = alias.trim();
                // 中文至少要 2 个字才值得做子串匹配，否则「剑」「弓」这种单字会命中一大片
                if (name.length() >= 2) {
                    map.putIfAbsent(name, e.en());
                }
            }
        }
        log.info("[KB] 术语表已加载：{} 条", map.size());
        return new ArrayList<>(map.entrySet());
    }
}
