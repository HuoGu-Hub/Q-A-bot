package com.example.qqbot.kb.map;

import com.example.qqbot.kb.PlaceCategory;
import com.example.qqbot.kb.PlaceDirectory;
import com.example.qqbot.kb.PlacePoint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link PlaceDirectory} 的实现 —— 核心问"有哪些地点"，答案由这里拼出来。
 *
 * <h2>三个来源合成一份目录</h2>
 * <ol>
 *   <li><b>wiki 点位</b>（{@code kb_map_marker}）：可整体重刷的镜像，同步时按页先删后插；</li>
 *   <li><b>本地备注</b>（{@code kb_map_marker_note}）：给 wiki 点位补中文名、备注、地标标记。
 *       它<b>不在</b>同步的删除范围内，所以镜像怎么刷都不会丢；</li>
 *   <li><b>管理员点位</b>（{@code kb_map_place}）：wiki 上根本没有的资源点、俗称地名。</li>
 * </ol>
 * 三者在这里汇成同一个 {@link PlacePoint}，上层再也分不出谁是谁 —— 这正是目的：
 * "哪里有 X"的算法不该关心数据是从哪来的。
 *
 * <h2>为什么不用缓存</h2>
 * 实测全部点位约 1700 条，重建一次是毫秒级；而缓存要处理失效
 * —— 管理员刚标完一个点却问不出结果，是最难排查的那类 bug。
 * 真到性能不够的那天再加，那时也有真实的数字做依据。
 */
@Component
public class WikiPlaceDirectory implements PlaceDirectory {

    /**
     * 可以当**方位参照物**的分组。
     *
     * <p>判据是"玩家听到名字就知道在哪"：远古尖塔、火焰神龛、村庄这类玩家会互相指路的地方。
     * 而 {@code Camp} / {@code Farm} 这些有几十个、彼此难以区分，拿它当参照等于没说。
     * 收集品与 Lore 文本更不行 —— 它们不是"地方"。
     */
    private static final Set<String> LANDMARK_GROUPS = Set.of(
            "Ancient_Spire", "Flame_Shrine", "Village", "Elixir_Well", "Ancient_Vault",
            "Ancient_Obelisk", "Hollow_Halls", "Sun_Temple", "Balefire", "Night_Sanctum",
            "Shroud_Lair", "Gem_Forge");

    /** 服务型 NPC 的 article —— 他们挂在 {@code Ancient_Vault} 分组下，但不是地标 */
    private static final Set<String> NPC_ARTICLES = Set.of(
            "blacksmith", "alchemist", "hunter", "carpenter", "farmer", "bard", "barber",
            "fisher", "collector");

    /** 具名兴趣点分组：数量多、名字杂，能当搜索目标但不适合当参照物 */
    private static final Set<String> POI_GROUPS = Set.of(
            "Camp", "Farm", "Mine", "Enemy_Camp", "Hidden_Temple", "Tomb", "Cave_Passage");

    private final KbMapStore mapStore;
    private final MapPlaceService places;
    private final MapNoteService notes;
    private final MapScope scope;

    public WikiPlaceDirectory(KbMapStore mapStore, MapPlaceService places, MapNoteService notes,
                              MapScope scope) {
        this.mapStore = mapStore;
        this.places = places;
        this.notes = notes;
        this.scope = scope;
    }

    @Override
    public boolean isAvailable() {
        return mapStore.isAvailable() || (places != null && places.isAvailable());
    }

    @Override
    public List<PlacePoint> all() {
        List<PlacePoint> out = new ArrayList<>();
        List<KbMapStore.Canvas> canvases = mapStore.canvases();
        Map<String, String> canvasOfPage = canvasIndex(canvases);
        Map<String, String> primaryOfMap = primaryCanvasOf(canvases);

        // 先按地图分组：备注是"一次取一张图"的查询，逐点查会把目录退化成上千次 SQL
        Map<String, List<KbMapStore.Marker>> wikiByMap = new LinkedHashMap<>();
        for (KbMapStore.Marker m : mapStore.all()) {
            wikiByMap.computeIfAbsent(nz(m.map()), k -> new ArrayList<>()).add(m);
        }
        // 同一批点位会在多个页面里重复定义（实测 {@code Map:EarlyAccess/Main} 与
        // {@code Landmark-markers} 的尖塔坐标一模一样）。不去重的话，
        // 一个点可以拿它自己的副本当"最近的参照物"，算出"约 0 坐标单位"这种废话。
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, List<KbMapStore.Marker>> e : wikiByMap.entrySet()) {
            String map = e.getKey();
            // 没启用的地图不进问答：库里留着它们（随时能切回来），但机器人的坐标必须来自当前世界。
            // 以前这里混着 5 张图的点位，"远古尖塔在哪"会答出旧世界的坐标
            if (!scope.isActive(map)) {
                continue;
            }
            Map<String, MapNoteService.Note> mapNotes = notes.byMap(map);
            for (KbMapStore.Marker m : e.getValue()) {
                MapNoteService.Note note = mapNotes.get(MapNoteService.ref(m.page(), m.markerId()));
                // 隐藏的点位连问答也不参与 —— 否则"藏起来"只是看不见，仍然会被说出去
                if (note != null && note.hidden()) {
                    continue;
                }
                String canvas = canvasOfPage.getOrDefault(nz(m.page()), primaryOfMap.getOrDefault(map, ""));
                PlacePoint point = toPoint(m, note, canvas);
                // 去重键含名字：同一格子上放两个不同的东西是合法的，不该被合并
                String dedupKey = nz(m.map()) + "|" + canvas + "|"
                        + Math.round(m.x()) + "," + Math.round(m.y()) + "|" + point.name();
                if (seen.add(dedupKey)) {
                    out.add(point);
                }
            }
        }

        // 管理员点位：只取公开的。隐藏逻辑与上面一致
        for (MapPlaceService.Place p : places.list(null, true)) {
            if (!scope.isActive(p.map())) {
                continue;
            }
            String canvas = p.page() == null || p.page().isBlank()
                    ? primaryOfMap.getOrDefault(nz(p.map()), "")
                    : p.page();
            out.add(new PlacePoint(p.map(), canvas, p.name(), p.aliases(), p.x(), p.y(),
                    p.category(), p.note(), p.landmark(), p.priority(), "admin", "place:" + p.id()));
        }
        return out;
    }

    @Override
    public List<PlacePoint> find(String term, int limit) {
        if (term == null || term.isBlank()) {
            return List.of();
        }
        String t = term.strip();
        List<PlacePoint> hits = new ArrayList<>();
        for (PlacePoint p : all()) {
            if (p.matches(t)) {
                hits.add(p);
            }
        }
        hits.sort(rank(t));
        int want = Math.max(1, limit);
        return hits.size() > want ? new ArrayList<>(hits.subList(0, want)) : hits;
    }

    @Override
    public Map<String, double[]> worldSizes() {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (KbMapStore.Canvas c : mapStore.canvases()) {
            if (!c.hasCrs()) {
                continue;
            }
            double[] e = c.extent();
            double[] cur = out.get(c.map());
            // 取面积最大的那张：同一地图有多张画布时（余烬谷的 Background 与 Collectibles），
            // 用大的当尺度分母才不会把距离都算成"很远"
            if (cur == null || e[0] * e[1] > cur[0] * cur[1]) {
                out.put(c.map(), e);
            }
        }
        return out;
    }

    /* ==================== 拼装 ==================== */

    /**
     * 一个 wiki marker 摊成地点。
     *
     * <p>显示名的优先级：<b>管理员备注的中文别名 &gt; 已核对的中文词条名 &gt; wiki 原名</b>。
     * 中文名后面挂区域（{@code 远古尖塔·春之原野}）是必需的：一个词条往往有几十个点
     * （实测火焰神龛 63 个），不带区域的话答案里会排出一串一模一样的名字。
     */
    private static PlacePoint toPoint(KbMapStore.Marker m, MapNoteService.Note note, String canvas) {
        String article = LocationCorpusBuilder.normalizeArticle(m.article());
        String zh = LocationCorpusBuilder.zhName(article);
        String region = LocationCorpusBuilder.regionOf(m);
        String auto = autoName(m, article, zh, region);
        String name = note != null && !nz(note.alias()).isBlank() ? note.alias().strip() : auto;

        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(auto);
        aliases.add(nz(m.name()));
        aliases.add(nz(article));
        aliases.add(nz(zh));
        aliases.add(nz(region));

        String category = note != null && PlaceCategory.isKnown(note.category())
                ? PlaceCategory.normalize(note.category())
                : categoryOf(m.group(), article);
        boolean landmark = (note != null && note.landmark()) || PlaceCategory.LANDMARK.equals(category);
        int priority = note == null ? 0 : note.priority();
        String remark = note == null ? "" : nz(note.note());

        return new PlacePoint(nz(m.map()), canvas, name, new ArrayList<>(aliases), m.x(), m.y(),
                category, remark, landmark, priority, "wiki",
                MapNoteService.ref(m.page(), m.markerId()));
    }

    /**
     * 显示名的自动拼法 —— <b>全项目只有这一份</b>。
     *
     * <p>包内可见是因为 {@code MapViewService} 也要用它：那边曾经自己抄了一遍，
     * 于是同一个点位在公开站里叫「雾水盆地·雾水盆地」，在问答里却是对的。
     */
    static String autoName(KbMapStore.Marker m, String article, String zh, String region) {
        String base = zh != null ? zh : nz(m.name());
        if (base.isBlank()) {
            base = article == null ? "未命名点位" : article;
        }
        if (region == null || region.isBlank()) {
            return base;
        }
        // 词条本身就是区域时（实测：雾水盆地里的酒馆，article 直接指向 Veilwater Basin），
        // 再挂一次区域只会得到「雾水盆地·雾水盆地」—— 挂上去等于没挂。
        // 这时 wiki 自己的名字更有信息量：读图的人要的是"是哪个酒馆"，不是"又一条区域名"。
        if (region.equals(base)) {
            String own = nz(m.name());
            return own.isBlank() || own.equals(base) ? base : own;
        }
        return base + "·" + region;
    }

    /**
     * wiki 分组 → 核心分类词。
     *
     * <p>这里是**唯一**认识 wiki 分组名的地方 —— 换数据来源只需要改这一个方法，
     * 检索与排序完全不受影响（见 {@link PlaceCategory} 的注释）。
     */
    static String categoryOf(String group, String article) {
        String g = nz(group);
        String a = nz(article).toLowerCase(Locale.ROOT);
        if (NPC_ARTICLES.contains(a)) {
            return PlaceCategory.NPC;
        }
        // Lore* 是世界观叙事文本的点，不是"地方"；归到收集品，绝不当地标
        if (g.startsWith("Lore")) {
            return PlaceCategory.COLLECTION;
        }
        if ("QuestLocation".equals(g)) {
            return PlaceCategory.POI;
        }
        if (LANDMARK_GROUPS.contains(g)) {
            return PlaceCategory.LANDMARK;
        }
        if (POI_GROUPS.contains(g)) {
            return PlaceCategory.POI;
        }
        return PlaceCategory.COLLECTION;
    }

    /* ==================== 画布归属 ==================== */

    /**
     * 页面 → 它该用哪张画布。
     *
     * <p>规则（按优先级）：
     * <ol>
     *   <li>这页自己带底图 → 就用自己（{@code Background}、{@code Collectibles}）；</li>
     *   <li>否则找<b>同一地图、坐标系相同、且带底图</b>的页
     *       （{@code Main} / {@code Lore} / {@code Quests} / {@code Collectibles2} 都是这样挂到
     *       {@code Map:Embervale/Background} 上的）；</li>
     *   <li>再不行就退回自己（有 crs 但没底图，至少坐标换算是对的）。</li>
     * </ol>
     * 为什么不能"一张地图一张画布"：实测 {@code Map:Embervale} 里就存在两套原点，
     * 硬合并会让其中一批点位整体偏移上万像素。
     */
    static Map<String, String> canvasIndex(List<KbMapStore.Canvas> canvases) {
        Map<String, String> withBackground = new LinkedHashMap<>();
        for (KbMapStore.Canvas c : canvases) {
            if (!c.hasCrs() || !hasBackground(c)) {
                continue;
            }
            String k = key(c.map(), c);
            String cur = withBackground.get(k);
            // 同一套坐标系往往有好几个页面都带底图（{@code Tabs/*} 是 tab 定义页）。
            // 只取第一个会按字典序选中 {@code Collectibles} 这种"没有点位"的页，
            // 于是地图上的点全部挂到一个叫"收集品"的画布上 —— 底图一样，但名字是错的。
            if (cur == null || canonicalRank(c.page()) > canonicalRank(cur)) {
                withBackground.put(k, c.page());
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (KbMapStore.Canvas c : canvases) {
            if (!c.hasCrs()) {
                continue;
            }
            String hit = withBackground.get(key(c.map(), c));
            out.put(c.page(), hit != null ? hit : c.page());
        }
        return out;
    }

    /**
     * 每张地图的"主画布" —— 没写 page 的自定义点位挂在它上面。
     *
     * <p>判据是"<b>规范底图页优先，再看面积</b>"，不是单纯比大小：
     * 实测 {@code Map:EarlyAccess} 有 8 个页面都带同一张底图（{@code Tabs/*} 是 tab 定义页），
     * 面积一样大 —— 只比面积会按字典序选中 {@code Collectibles}，
     * 而它一个点位都没有。规范页是 {@code Main} / {@code Background}。
     */
    static Map<String, String> primaryCanvasOf(List<KbMapStore.Canvas> canvases) {
        Map<String, KbMapStore.Canvas> best = new LinkedHashMap<>();
        for (KbMapStore.Canvas c : canvases) {
            if (!c.hasCrs() || !hasBackground(c)) {
                continue;
            }
            KbMapStore.Canvas cur = best.get(c.map());
            if (cur == null || betterPrimary(c, cur)) {
                best.put(c.map(), c);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        best.forEach((map, c) -> out.put(map, c.page()));
        return out;
    }

    private static boolean betterPrimary(KbMapStore.Canvas a, KbMapStore.Canvas b) {
        int ra = canonicalRank(a.page());
        int rb = canonicalRank(b.page());
        if (ra != rb) {
            return ra > rb;
        }
        double[] ea = a.extent();
        double[] eb = b.extent();
        return ea[0] * ea[1] > eb[0] * eb[1];
    }

    /** 规范底图页的名字 —— {@code Map:X/Main} 与 {@code Map:X/Background} 是 wiki 约定的两张主图 */
    private static int canonicalRank(String page) {
        String p = nz(page);
        if (p.endsWith("/Background")) {
            return 2;
        }
        if (p.endsWith("/Main")) {
            return 1;
        }
        return 0;
    }

    private static boolean hasBackground(KbMapStore.Canvas c) {
        return (c.tilesJson() != null && !c.tilesJson().isBlank())
                || (c.backgroundImage() != null && !c.backgroundImage().isBlank());
    }

    /** 坐标系指纹 —— 用四至判断"两页是不是同一套坐标" */
    private static String key(String map, KbMapStore.Canvas c) {
        double[] tl = c.topLeft();
        double[] br = c.bottomRight();
        return nz(map) + "|" + tl[0] + "," + tl[1] + "," + br[0] + "," + br[1];
    }

    /* ==================== 检索排序 ==================== */

    /**
     * 相关度：完全同名 &gt; 名字开头 &gt; 名字包含 &gt; 别名包含 &gt; 只在备注里出现；
     * 同档按名字短的优先（"铁矿"排在"铁矿洞窟入口"前面），再看人工优先级。
     */
    private static Comparator<PlacePoint> rank(String term) {
        String t = term.toLowerCase(Locale.ROOT);
        return Comparator
                .comparingInt((PlacePoint p) -> tier(p, t))
                .thenComparingInt(p -> p.name() == null ? 999 : p.name().length())
                .thenComparing(Comparator.comparingInt(PlacePoint::priority).reversed());
    }

    private static int tier(PlacePoint p, String t) {
        String n = p.name() == null ? "" : p.name().toLowerCase(Locale.ROOT);
        if (n.equals(t)) {
            return 0;
        }
        if (n.startsWith(t)) {
            return 1;
        }
        if (n.contains(t)) {
            return 2;
        }
        for (String a : p.aliasesOrEmpty()) {
            if (a != null && a.toLowerCase(Locale.ROOT).contains(t)) {
                return 3;
            }
        }
        return 4;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 排障用：一眼看出三个来源各贡献了多少 */
    public String describeSources() {
        Set<String> maps = new HashSet<>();
        int wiki = 0;
        int admin = 0;
        for (PlacePoint p : all()) {
            maps.add(p.map());
            if ("admin".equals(p.source())) {
                admin++;
            } else {
                wiki++;
            }
        }
        return "wiki=" + wiki + ", admin=" + admin + ", notes=" + notes.count()
                + ", maps=" + maps.size() + ", canvases=" + mapStore.canvases().size();
    }
}
