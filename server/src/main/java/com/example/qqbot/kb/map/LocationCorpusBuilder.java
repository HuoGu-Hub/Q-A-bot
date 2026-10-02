package com.example.qqbot.kb.map;

import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.term.KbTermStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 由地图 marker **派生地点语料** —— 这是"把地图数据变成能回答的问题"的那一步。
 *
 * <h2>为什么不能把 1,703 个 marker 直接灌进知识库</h2>
 * 实测 marker 严重重复：`Flame Shrine` 一个词条就有 111 个 marker、`Shroud Root` 66 个。
 * 一条一个块会让语料里塞进上千条近乎重复的条目，把检索彻底淹没。
 * 所以按 **article（词条）聚合**：一个词条一个块，块里说明"共几处、都在哪些区域"。
 *
 * <h2>派生哪些、不派生哪些（按价值分层）</h2>
 * <ul>
 *   <li><b>区域</b>（Springlands / Revelwood / …）—— 中文名**全部从现有语料里核对过**，不是编的；</li>
 *   <li><b>POI 类型</b>（marker ≥5 个的：Flame Shrine、Ancient Spire、Elixir Well…）—— 玩家真的会问"灵火祭坛在哪"；</li>
 *   <li><b>具名地点</b>（Wolf Cave、Carpentry Camp…）—— 就是玩家嘴里那些地名；</li>
 *   <li><b>NPC</b>（Blacksmith / Alchemist / Hunter / Carpenter / Farmer / Bard / Barber / Fisher）
 *       —— 实测他们出现在 `Ancient_Vault` 分组里，这正是"NPC 在哪"的答案来源；</li>
 *   <li><b>刻意排除 `Lore/*`（350+ 条）</b>：那是世界观叙事文本，不是"哪里/怎么"的事实，
 *       而且数量会把真正的 POI 挤下去。要用再单独做一层，别混进来。</li>
 * </ul>
 *
 * <h2>可回滚</h2>
 * 派生块全部落在 `docId = {@link #DOC_ID}` 下，{@link #purge()} 一条命令清干净 ——
 * 这是"每阶段可回滚"在语料层的落点。id 由 article 确定性生成，所以重跑是幂等覆盖。
 */
@Service
public class LocationCorpusBuilder {

    private static final Logger log = LoggerFactory.getLogger(LocationCorpusBuilder.class);

    /** 派生块都归在这份"文档"下，便于整份清除与统计 */
    public static final String DOC_ID = "地图·地点";
    /** 块来源标记 —— 与 doc / manual 并列，便于区分"这份是机器从地图派生的" */
    public static final String SRC_MAP = "map";

    /**
     * 区域中英对照 —— **每一项都在现有语料里核对过**，不是猜的
     * （如 `来源 春之原野` 43 次、`黑沼树皮（Blackmire Bark）`、`空洞大厅（Hollow Halls）`）。
     */
    private static final Map<String, String> REGION_ZH = new LinkedHashMap<>();

    static {
        REGION_ZH.put("springlands", "春之原野");
        REGION_ZH.put("revelwood", "启示林");
        REGION_ZH.put("nomad highlands", "游牧高地");
        REGION_ZH.put("kindlewastes", "燃烬荒原");
        REGION_ZH.put("albaneve summits", "阿尔巴内夫峰");
        REGION_ZH.put("veilwater basin", "雾水盆地");
        REGION_ZH.put("hollow halls", "空洞大厅");
        REGION_ZH.put("drak", "德拉克");
        REGION_ZH.put("blackmire", "黑沼");
        REGION_ZH.put("embervale", "余烬谷");
    }

    /**
     * 词条中文显示名 —— **每一项都在现有语料里核对过，没有一个是编的**。
     *
     * <p>为什么必须有它：地点块如果只有英文标题（`Blacksmith`），中文提问「铁匠在哪」
     * 会被一批名字里含"铁匠"的**物品**压过去（实测 top-5 全是铁匠长袍/铁匠帽/锻造工具）。
     * 加上中文名之后，"铁匠（Blacksmith）"才既吃字面匹配、又吃语义匹配。
     *
     * <p>证据（语料原文）：
     * <ul>
     *   <li>POI：`火花可以从遍布余烬谷的火焰神龛与火焰圣所收集`（Flame Shrine）、
     *       `黑沼远古尖塔入口处`（Ancient Spire）、`阿尔巴内夫峰的一口灵药井`（Elixir Well）、
     *       `雾瘴巢穴战利品`（Shroud Lair，66 次）、`炼金术士的远古宝库`（Ancient Vault）；</li>
     *   <li>NPC：来自语料正文的 `制作：X` 字段 —— 木匠 286 次、猎人 178、农夫 169、
     *       炼金术士 160、铁匠 102、吟游诗人 81、收藏家 59、渔夫 14、理发师 1。</li>
     * </ul>
     * <b>没核对到的一律留英文</b>（如 Sun Temple / Catacomb / Shroud Root），
     * 宁可少一个中文名，也不要编一个错的 —— 编错了会污染整个检索。
     */
    private static final Map<String, String> NAME_ZH = new LinkedHashMap<>();

    static {
        NAME_ZH.putAll(REGION_ZH);
        // POI（语料正文核对）
        NAME_ZH.put("flame shrine", "火焰神龛");
        NAME_ZH.put("ancient spire", "远古尖塔");
        NAME_ZH.put("elixir well", "灵药井");
        NAME_ZH.put("shroud lair", "雾瘴巢穴");
        NAME_ZH.put("ancient obelisk", "方尖碑");
        NAME_ZH.put("ancient vault", "远古宝库");
        // NPC（语料『制作：X』核对）
        NAME_ZH.put("carpenter", "木匠");
        NAME_ZH.put("hunter", "猎人");
        NAME_ZH.put("farmer", "农夫");
        NAME_ZH.put("alchemist", "炼金术士");
        NAME_ZH.put("blacksmith", "铁匠");
        NAME_ZH.put("bard", "吟游诗人");
        NAME_ZH.put("fisher", "渔夫");
        NAME_ZH.put("barber", "理发师");
        NAME_ZH.put("collector", "收藏家");
    }

    /** 服务型 NPC —— 实测他们以 marker 的 article 形式出现（Blacksmith 在 Ancient Vault 里） */
    private static final Set<String> NPCS = Set.of(
            "blacksmith", "alchemist", "hunter", "carpenter", "farmer", "bard", "barber", "fisher", "collector");

    /** POI 类型的门槛：marker 数达到它才算"一类地标"，否则按具名地点处理 */
    private static final int POI_MIN_MARKERS = 5;

    private final KbMapStore mapStore;
    private final KbBlockStore blockStore;
    private final KbBlockIndex index;
    private final EmbeddingClient embedding;
    private final KbTermStore termStore;

    public LocationCorpusBuilder(KbMapStore mapStore, KbBlockStore blockStore, KbBlockIndex index,
                                 EmbeddingClient embedding, KbTermStore termStore) {
        this.mapStore = mapStore;
        this.blockStore = blockStore;
        this.index = index;
        this.embedding = embedding;
        this.termStore = termStore;
    }

    /** 一个待写入的派生块 */
    public record Entry(String id, String title, String body, String url, List<String> tags, String kind) {
    }

    /**
     * @param entries  派生出的块数
     * @param regions  其中区域类
     * @param pois     其中 POI 类型
     * @param places   其中具名地点
     * @param npcs     其中 NPC
     * @param skippedLore 被刻意排除的 `Lore/*` 词条数
     * @param written  实际写入（含向量）的块数
     */
    public record BuildReport(int entries, int regions, int pois, int places, int npcs, int skippedLore,
                              int written, int purged, boolean dryRun, List<String> samples) {
    }

    /** 只算不写（dryRun / 测试用） */
    public BuildReport planOnly() {
        List<Entry> entries = plan(mapStore.all());
        return report(entries, countSkippedLore(mapStore.all()), 0, 0, true);
    }

    /**
     * 真派生：算 + 向量化 + 写库。**幂等**（id 由 article 决定，重跑是覆盖）。
     *
     * <p>向量化失败会抛 {@link EmbeddingClient.EmbeddingException} ——
     * 这里**不吞**：块写进去了却没有向量，等于"检索永远看不到它"，比直接失败更难排查。
     */
    public BuildReport build(boolean dryRun) {
        List<KbMapStore.Marker> markers = mapStore.all();
        List<Entry> entries = plan(markers);
        int skippedLore = countSkippedLore(markers);
        if (dryRun) {
            return report(entries, skippedLore, 0, 0, true);
        }
        if (!embedding.isAvailable()) {
            throw new IllegalStateException("向量模型不可用，无法派生地点语料（没有向量的块检索不到）");
        }

        int written = 0;
        String now = Instant.now().toString();
        for (Entry e : entries) {
            float[] vec = embedding.embedOne(e.title() + "\n" + e.body());
            KbBlock block = new KbBlock(e.id(), DOC_ID, e.title(), e.body(), e.url(),
                    e.tags(), SRC_MAP, false, now);
            if (blockStore.upsert(block, vec)) {
                // C 路（标题向量）也要补：地点问题常常就是"名字提问"
                blockStore.upsertTitleVector(e.id(), e.title(), embedding.embedOne(e.title()));
                written++;
            }
        }
        index.reload();
        // 新块要**立刻**能被 B 路（关键词）看到：词条表只在启动时 reconcile 一次，
        // 不补这一下，运行期导入的块要等下次重启才进词表。
        termStore.reconcile();
        log.info("[KB-MAP] 地点语料派生完成：{} 条（区域 {} / POI {} / 具名 {} / NPC {}），跳过 Lore {} 条",
                written, countKind(entries, "region"), countKind(entries, "poi"),
                countKind(entries, "place"), countKind(entries, "npc"), skippedLore);
        return report(entries, skippedLore, written, 0, false);
    }

    /** 清掉全部派生块 —— 回滚用。返回删掉的条数 */
    public int purge() {
        int n = 0;
        for (KbBlock b : blockStore.byDoc(DOC_ID)) {
            blockStore.deleteTitleVector(b.id());
            if (blockStore.delete(b.id())) {
                n++;
            }
        }
        index.reload();
        // 新块要**立刻**能被 B 路（关键词）看到：词条表只在启动时 reconcile 一次，
        // 不补这一下，运行期导入的块要等下次重启才进词表。
        termStore.reconcile();
        log.info("[KB-MAP] 已清除派生地点语料 {} 条", n);
        return n;
    }

    /* ==================== 纯函数部分（可离线测） ==================== */

    /** 把 marker 聚合成派生块。**纯函数**：不碰数据库、不调模型 */
    public static List<Entry> plan(List<KbMapStore.Marker> markers) {
        // article -> 该词条的全部 marker（去掉 # 锚点、剔掉坏值）
        Map<String, List<KbMapStore.Marker>> byArticle = new LinkedHashMap<>();
        for (KbMapStore.Marker m : markers) {
            String art = normalizeArticle(m.article());
            if (art == null) {
                continue;
            }
            byArticle.computeIfAbsent(art, k -> new ArrayList<>()).add(m);
        }

        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, List<KbMapStore.Marker>> e : byArticle.entrySet()) {
            String article = e.getKey();
            List<KbMapStore.Marker> ms = e.getValue();
            String kind = classify(article, ms.size());
            out.add(toEntry(article, ms, kind));
        }
        return out;
    }

    /**
     * 词条名归一化。返回 {@code null} 表示**这个 marker 不该进语料**：
     * <ul>
     *   <li>空 / 只有空白；</li>
     *   <li>{@code :c:Vukah} 这种分类链接（实测存在）；</li>
     *   <li>{@code Lore/xxx} 叙事文本 —— 刻意排除，见类注释；</li>
     *   <li>{@code Collectibles#Fossils} 这类锚点 —— 剥掉 {@code #} 之后保留前半段。</li>
     * </ul>
     */
    static String normalizeArticle(String article) {
        if (article == null) {
            return null;
        }
        String a = article.trim();
        int hash = a.indexOf('#');
        if (hash >= 0) {
            a = a.substring(0, hash).trim();
        }
        if (a.isEmpty() || a.startsWith(":") || a.startsWith("Lore/")) {
            return null;
        }
        return a;
    }

    static String classify(String article, int markerCount) {
        if (REGION_ZH.containsKey(article.toLowerCase(Locale.ROOT))) {
            return "region";
        }
        if (NPCS.contains(article.toLowerCase(Locale.ROOT))) {
            return "npc";
        }
        return markerCount >= POI_MIN_MARKERS ? "poi" : "place";
    }

    static Entry toEntry(String article, List<KbMapStore.Marker> ms, String kind) {
        Set<String> regions = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        Set<String> maps = new LinkedHashSet<>();
        for (KbMapStore.Marker m : ms) {
            String r = regionOf(m);
            if (r != null) {
                regions.add(r);
            }
            if (m.group() != null && !m.group().isBlank()) {
                groups.add(m.group());
            }
            if (m.map() != null && !m.map().isBlank()) {
                maps.add(m.map());
            }
        }

        String zh = NAME_ZH.get(article.toLowerCase(Locale.ROOT));
        // 有核对过的中文名就写成「中文（English）」—— 中英都能命中
        String title = zh != null ? zh + "（" + article + "）" : article;

        StringBuilder body = new StringBuilder();
        body.append(title).append("。");
        body.append("共 ").append(ms.size()).append(" 处。");
        if (!regions.isEmpty()) {
            body.append("所在区域：").append(String.join("、", regions)).append("。");
        }
        // 抽前两条描述当正文 —— 它们含 wiki 内链（如 [[Fell Thunderbrute]]），是 NPC/怪物名字的来源
        int shown = 0;
        for (KbMapStore.Marker m : ms) {
            if (shown >= 2) {
                break;
            }
            String d = cleanDescription(m.description());
            if (!d.isBlank()) {
                body.append(d).append('。');
                shown++;
            }
        }
        body.append("地图坐标示例：")
                .append(String.format(Locale.ROOT, "(%.0f, %.0f)", ms.get(0).x(), ms.get(0).y()))
                .append("。");
        if (ms.size() > 1) {
            body.append("另有 ").append(ms.size() - 1).append(" 处，见 wiki 地图。");
        }

        String url = "https://enshrouded.wiki.gg/wiki/" + article.replace(' ', '_');
        List<String> tags = new ArrayList<>();
        tags.add("地图");
        tags.add(kind.equals("region") ? "区域" : kind.equals("npc") ? "NPC" : "地点");
        tags.addAll(groups);
        return new Entry(idOf(article), title, body.toString(), url, tags, kind);
    }

    /** 确定性 id —— 重跑幂等的前提 */
    static String idOf(String article) {
        return "map-loc-" + article.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    /**
     * 一个 marker 属于哪个区域。
     *
     * <p>三个信号，按可靠性排序：
     * <ol>
     *   <li>{@code Village} 分组的 article 本身就是区域名；</li>
     *   <li>{@code LoreXxx} 分组名后缀就是区域（实测 707/1703 个 marker 走这条）；</li>
     *   <li>描述里的 {@code in the <Region>} —— 实测只覆盖 17.8%，但有比没有好。</li>
     * </ol>
     * 拿不到就返回 {@code null}（**不猜**）。
     */
    static String regionOf(KbMapStore.Marker m) {
        String grp = m.group() == null ? "" : m.group();
        if (grp.startsWith("Lore") && grp.length() > 4) {
            String suffix = grp.substring(4);
            String zh = REGION_ZH.get(splitCamel(suffix).toLowerCase(Locale.ROOT));
            return zh != null ? zh : splitCamel(suffix);
        }
        String art = normalizeArticle(m.article());
        if (art != null && REGION_ZH.containsKey(art.toLowerCase(Locale.ROOT))) {
            return REGION_ZH.get(art.toLowerCase(Locale.ROOT));
        }
        String desc = m.description() == null ? "" : m.description();
        java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("\\bin (?:the )?([A-Z][A-Za-z' ]{2,30})").matcher(desc);
        if (mm.find()) {
            String name = mm.group(1).trim().replaceAll("[,\\s]+$", "");
            String zh = REGION_ZH.get(name.toLowerCase(Locale.ROOT));
            return zh != null ? zh : name;
        }
        return null;
    }

    /** {@code AlbaneveSummits} → {@code Albaneve Summits} */
    static String splitCamel(String s) {
        return s.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
    }

    /** 描述里去掉 wiki 内链标记，保留可读文本 */
    static String cleanDescription(String d) {
        if (d == null) {
            return "";
        }
        return d.replace("[[", "").replace("]]", "").trim();
    }

    static int countSkippedLore(List<KbMapStore.Marker> markers) {
        Set<String> lore = new LinkedHashSet<>();
        for (KbMapStore.Marker m : markers) {
            String a = m.article() == null ? "" : m.article().trim();
            if (a.startsWith("Lore/")) {
                lore.add(a);
            }
        }
        return lore.size();
    }

    private static int countKind(List<Entry> entries, String kind) {
        int n = 0;
        for (Entry e : entries) {
            if (e.kind().equals(kind)) {
                n++;
            }
        }
        return n;
    }

    private static BuildReport report(List<Entry> entries, int skippedLore, int written, int purged, boolean dryRun) {
        List<String> samples = new ArrayList<>();
        for (Entry e : entries) {
            if (samples.size() < 8) {
                samples.add("[" + e.kind() + "] " + e.title());
            }
        }
        return new BuildReport(entries.size(), countKind(entries, "region"), countKind(entries, "poi"),
                countKind(entries, "place"), countKind(entries, "npc"), skippedLore, written, purged, dryRun, samples);
    }
}
