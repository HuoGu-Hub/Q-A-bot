package com.example.qqbot.kb.map;

import com.example.qqbot.kb.PlaceCategory;
import com.example.qqbot.kb.PlaceDirectory;
import com.example.qqbot.kb.PlacePoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 查看器的**只读视图层** —— 把"库里的原始行"整形成"画一张图需要的东西"。
 *
 * <h2>为什么不让控制器直接查仓储</h2>
 * 前端要的数据形状和表结构差别很大：
 * <ul>
 *   <li>点位要带上"该用哪张画布"（{@code canvasIndex} 算出来的）；</li>
 *   <li>分组要带中文显示名和图标文件名；</li>
 *   <li>画布要带解析后的瓦片清单（库里存的是 JSON 原文）。</li>
 * </ul>
 * 这些整形逻辑放控制器里，每个接口都要抄一遍；放这里，公开端与管理端共用同一份。
 *
 * <h2>只读</h2>
 * 本类只有查询。写点位是 {@link MapPlaceService}，写备注是 {@link MapNoteService}。
 */
@Service
public class MapViewService {

    private static final Logger log = LoggerFactory.getLogger(MapViewService.class);

    /** 地图中文名 —— 只影响界面标签，不动语料（改语料的名字会动向量，那是另一件事） */
    private static final Map<String, String> MAP_ZH = Map.of(
            "Embervale", "余烬谷",
            "Blackmire", "黑沼",
            "EarlyAccess", "抢先体验",
            "FrozenFrontier", "冰封边境",
            "Update4", "更新 4",
            "Test", "测试");

    /** 分组显示名。没收录的保持英文 —— 宁可显示英文，也不要编一个错的中文 */
    private static final Map<String, String> GROUP_ZH = Map.ofEntries(
            Map.entry("Ancient Spire", "远古尖塔"),
            Map.entry("Ancient Vault", "远古宝库"),
            Map.entry("Ancient Obelisk", "方尖碑"),
            Map.entry("Elixir Well", "灵药井"),
            Map.entry("Shroud Root", "雾瘴之根"),
            Map.entry("Flame Shrine", "火焰神龛"),
            Map.entry("Night Sanctum", "暗夜圣所"),
            Map.entry("Sun Temple", "太阳神殿"),
            Map.entry("Balefire", "炬火"),
            Map.entry("Hollow Halls", "空洞大厅"),
            Map.entry("Gem Forge", "宝石熔炉"),
            Map.entry("Village", "村庄"),
            Map.entry("Camp", "营地"),
            Map.entry("Farm", "农场"),
            Map.entry("Mine", "矿坑"),
            Map.entry("Enemy Camp", "敌营"),
            Map.entry("Misc. Dungeon", "杂项地牢"),
            Map.entry("Hidden Tomb", "隐秘墓穴"),
            Map.entry("Shroud Lair", "雾瘴巢穴"),
            Map.entry("Cave Passage", "洞穴通道"),
            Map.entry("Quest Location", "任务地点"),
            Map.entry("Fossil", "化石"),
            Map.entry("Music Sheet", "乐谱"),
            Map.entry("Cyclops Skeleton", "独眼巨人骨架"),
            Map.entry("Wall Tapestry", "壁毯"),
            Map.entry("Flame Mosaic", "火焰马赛克"),
            Map.entry("Dragon's Map Piece", "龙之地图残片"),
            Map.entry("Drak Relief", "德拉克浮雕"),
            Map.entry("Queen Pikemead Statue Piece", "皮克米德女王雕像碎片"),
            Map.entry("Alchemist's Wall Hanging", "炼金术士壁挂"));

    /** 分类中文名。**与 {@link PlaceCategory} 一一对应**，少一个就会在界面上显示英文键名 */
    private static final Map<String, String> CATEGORY_ZH = Map.of(
            PlaceCategory.RESOURCE, "资源",
            PlaceCategory.LANDMARK, "地标",
            PlaceCategory.POI, "兴趣点",
            PlaceCategory.NPC, "NPC",
            PlaceCategory.REGION, "区域",
            PlaceCategory.COLLECTION, "收集品",
            PlaceCategory.CUSTOM, "其它");

    /** 分类清单（键 + 中文名）—— 管理端的下拉框与公开站的图例共用这一份 */
    public static List<Map<String, Object>> categoryLabels() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : List.of(PlaceCategory.RESOURCE, PlaceCategory.LANDMARK,
                PlaceCategory.POI, PlaceCategory.NPC, PlaceCategory.REGION,
                PlaceCategory.COLLECTION, PlaceCategory.CUSTOM)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("label", CATEGORY_ZH.getOrDefault(key, key));
            out.add(m);
        }
        return out;
    }

    /** 画布页面的后缀中文名 */
    private static final Map<String, String> CANVAS_ZH = Map.of(
            "Background", "主地图",
            "Main", "主地图",
            "Lore", "世界观",
            "Collectibles", "收集品",
            "Collectibles2", "收集品",
            "Quests", "任务",
            "Landmark-markers", "地标",
            "POIs", "兴趣点",
            "Areas", "区域");

    private final KbMapStore mapStore;
    private final MapPlaceService places;
    private final MapNoteService notes;
    private final PlaceDirectory directory;
    private final ObjectMapper mapper;

    public MapViewService(KbMapStore mapStore, MapPlaceService places, MapNoteService notes,
                          PlaceDirectory directory, ObjectMapper mapper) {
        this.mapStore = mapStore;
        this.places = places;
        this.notes = notes;
        this.directory = directory;
        this.mapper = mapper;
    }

    /* ==================== 视图类型 ==================== */

    /** 一块底图瓦片：格子坐标 + 图片名 */
    public record TileView(String image, int col, int row) {
    }

    /** 一张可渲染的画布 */
    public record CanvasView(String page, String label, double[] topLeft, double[] bottomRight,
                             double[] tileAt, double[] tileSize, List<TileView> tiles,
                             String background, String backdrop,
                             double zoomMin, double zoomMax) {

        /** 世界宽高 —— 前端算初始缩放要用 */
        public double[] extent() {
            return new double[]{Math.abs(bottomRight[0] - topLeft[0]),
                    Math.abs(topLeft[1] - bottomRight[1])};
        }
    }

    /**
     * 一个图层（分组）。
     *
     * <p>带上 {@code category}：前端要靠它决定"默认开哪些图层" ——
     * 收集品（化石、乐谱、壁毯）有 550 多个，全开会让总览糊成一片，
     * 而它们不是"资源在哪、敌营在哪"这类问题的答案。
     */
    public record GroupView(String key, String name, String icon, boolean collectible,
                            boolean landmark, String category, int count) {
    }

    /** 一张地图的目录信息（不含点位） */
    public record MapView(String map, String label, int markerCount, int adminCount,
                          List<CanvasView> canvases, List<GroupView> groups) {
    }

    /** 一个点位。wiki 点与自定义点在这一层**长得一样**，前端只需一条渲染路径 */
    public record MarkerView(String id, String map, String page, String canvas, String group,
                             String name, String original, String description, String article,
                             double x, double y, String image, boolean landmark,
                             String category, int priority, String note, String source) {
    }

    /* ==================== 查询 ==================== */

    /** 全部地图（含画布与分组），不含点位 —— 前端首屏只拉这一份 */
    public List<MapView> catalog() {
        List<MapView> out = new ArrayList<>();
        for (KbMapRepositoryCount c : mapCounts()) {
            Map<String, Integer> byGroup = mapStore.countsByGroup(c.map());
            List<KbMapStore.Group> groups = mapStore.groups(c.map());
            List<GroupView> groupViews = new ArrayList<>();
            for (KbMapStore.Group g : groups) {
                int n = byGroup.getOrDefault(g.key(), 0);
                // 没有点位的分组不进图层列表 —— 免得界面上出现一堆空开关
                if (n == 0) {
                    continue;
                }
                String category = WikiPlaceDirectory.categoryOf(g.key(), "");
                groupViews.add(new GroupView(g.key(), groupLabel(g.name()),
                        g.icon() == null ? "" : g.icon(), g.collectible(),
                        PlaceCategory.LANDMARK.equals(category), category, n));
            }
            groupViews.sort((a, b) -> Integer.compare(b.count(), a.count()));
            out.add(new MapView(c.map(), labelOf(c.map()), (int) c.count(),
                    places.list(c.map(), false).size(), canvasesOf(c.map()), groupViews));
        }
        // 地图按点位多少排序：余烬谷第一（它最大）
        out.sort((a, b) -> Integer.compare(b.markerCount(), a.markerCount()));
        return out;
    }

    /** 内部小结构：避免为了一个计数把仓储类型暴露出去 */
    private record KbMapRepositoryCount(String map, long count) {
    }

    private List<KbMapRepositoryCount> mapCounts() {
        List<KbMapRepositoryCount> out = new ArrayList<>();
        // 用分组统计求和，而不是再写一条"按地图计数"的 SQL —— 分组数据在 catalog() 里还要用一次
        for (String map : mapStore.mapNames()) {
            int total = 0;
            for (int n : mapStore.countsByGroup(map).values()) {
                total += n;
            }
            out.add(new KbMapRepositoryCount(map, total));
        }
        return out;
    }

    /**
     * 一张地图**真正用得到**的可渲染画布。
     *
     * <p>为什么不是"所有能画的画布"：实测 {@code Map:EarlyAccess} 有 8 个页面带着同一张底图
     * （{@code Tabs/All}、{@code Tabs/Areas} … 是 tab 定义页），全列出来界面会冒出八个
     * 名字差不多的切换按钮，而其中七个一个点位都没有。
     *
     * <p>判据两条，取并集：<b>有点位落在它上面</b>，或者<b>它是这张地图的主画布</b>
     * （地图还没有点位时也要能看到底图）。
     */
    /** 有哪些地图（只有名字）—— 后台任务遍历用 */
    public List<String> maps() {
        return mapStore.mapNames();
    }

    public List<CanvasView> canvasesOf(String map) {
        List<KbMapStore.Canvas> all = mapStore.renderableCanvases();
        Map<String, String> canvasOfPage = WikiPlaceDirectory.canvasIndex(all);
        Set<String> used = new LinkedHashSet<>();
        for (KbMapStore.Marker m : mapStore.all()) {
            if (map != null && !map.isBlank() && !map.equals(m.map())) {
                continue;
            }
            String page = canvasOfPage.get(nz(m.page()));
            if (page != null) {
                used.add(page);
            }
        }
        String primary = WikiPlaceDirectory.primaryCanvasOf(all).get(nz(map));
        if (primary != null) {
            used.add(primary);
        }

        List<CanvasView> out = new ArrayList<>();
        for (KbMapStore.Canvas c : all) {
            if (map != null && !map.isBlank() && !map.equals(c.map())) {
                continue;
            }
            if (!used.isEmpty() && !used.contains(c.page())) {
                continue;
            }
            out.add(new CanvasView(c.page(), canvasLabel(c.page()),
                    c.topLeft(), c.bottomRight(), c.tileAt(), c.tileSize(),
                    tilesOf(c.tilesJson()),
                    c.backgroundImage() == null ? "" : c.backgroundImage(),
                    c.backdrop() == null ? "" : c.backdrop(), c.zoomMin(), c.zoomMax()));
        }
        return out;
    }

    /**
     * 一张地图的全部点位（wiki + 自定义），已按画布归属整好形。
     *
     * <p>为什么把两者合进同一个返回值：前端只需要一条渲染路径。
     * 用 {@code source} 区分来源，图层开关按 {@code group} / {@code category} 过滤。
     */
    public List<MarkerView> markers(String map) {
        return markers(map, true);
    }

    /**
     * @param onlyVisible 只给公开站要显示的点位。**默认必须是 true**：
     *                    这个方法的调用方大多是公开接口，"忘了过滤"就等于把隐藏点位泄露出去，
     *                    而"忘了显示"只是少几个点 —— 两种错误的代价完全不对称。
     */
    public List<MarkerView> markers(String map, boolean onlyVisible) {
        List<KbMapStore.Canvas> canvases = mapStore.canvases();
        Map<String, String> canvasOfPage = WikiPlaceDirectory.canvasIndex(canvases);
        Map<String, String> primaryOfMap = WikiPlaceDirectory.primaryCanvasOf(canvases);

        List<MarkerView> out = new ArrayList<>();
        Map<String, MapNoteService.Note> mapNotes = notes.byMap(map);
        for (KbMapStore.Marker m : mapStore.all()) {
            if (map != null && !map.isBlank() && !map.equals(m.map())) {
                continue;
            }
            MapNoteService.Note note = mapNotes.get(MapNoteService.ref(m.page(), m.markerId()));
            String canvas = canvasOfPage.getOrDefault(nz(m.page()),
                    primaryOfMap.getOrDefault(nz(m.map()), ""));
            String article = LocationCorpusBuilder.normalizeArticle(m.article());
            String zh = LocationCorpusBuilder.zhName(article);
            String region = LocationCorpusBuilder.regionOf(m);
            String auto = zh != null ? zh : nz(m.name());
            if (region != null && !region.isBlank()) {
                auto = auto + "·" + region;
            }
            String display = note != null && !nz(note.alias()).isBlank() ? note.alias().strip() : auto;
            String category = note != null && PlaceCategory.isKnown(note.category())
                    ? PlaceCategory.normalize(note.category())
                    : WikiPlaceDirectory.categoryOf(m.group(), article);
            out.add(new MarkerView(MapNoteService.ref(m.page(), m.markerId()), nz(m.map()),
                    nz(m.page()), canvas, nz(m.group()), display, nz(m.name()),
                    nz(m.description()), nz(article), m.x(), m.y(), nz(m.image()),
                    note != null && note.landmark() || PlaceCategory.LANDMARK.equals(category),
                    category, note == null ? 0 : note.priority(),
                    note == null ? "" : nz(note.note()), "wiki"));
        }

        for (MapPlaceService.Place p : places.list(map, onlyVisible)) {
            String canvas = p.page() == null || p.page().isBlank()
                    ? primaryOfMap.getOrDefault(nz(p.map()), "") : p.page();
            out.add(new MarkerView("place:" + p.id(), nz(p.map()), nz(p.page()), canvas,
                    p.category(), p.name(), p.name(), p.note(), "", p.x(), p.y(), "",
                    p.landmark(), p.category(), p.priority(), nz(p.note()), "admin"));
        }
        return out;
    }

    /**
     * 搜索点位。走 {@link PlaceDirectory} 而不是自己扫 —— 那边已经统一了
     * wiki 点、备注别名、自定义点的匹配与排序；这里再写一套只会两处不一致。
     */
    public List<MarkerView> search(String term, int limit) {
        List<MarkerView> out = new ArrayList<>();
        for (PlacePoint p : directory.find(term, limit)) {
            out.add(new MarkerView(p.ref(), nz(p.map()), pageOf(p.ref()), nz(p.canvas()),
                    nz(p.category()), nz(p.name()), nz(p.name()), nz(p.note()),
                    "", p.x(), p.y(), "", p.landmark(), p.category(), p.priority(),
                    nz(p.note()), nz(p.source())));
        }
        return out;
    }

    /* ==================== 资源白名单 ==================== */

    /**
     * 这个图片名是否**确实属于**我们的地图数据。
     *
     * <p><b>为什么必须查</b>：接口是公开的。不查的话，任何人把
     * {@code ?name=任意文件.jpg} 换成别的名字，我们这台机器就成了
     * wiki 的免费图片代理 —— 流量算我们的、缓存目录也会被慢慢灌满。
     * 只放行"画布瓦片、底图整图、分组图标"这三类，正好是我们真正会渲染的东西。
     */
    public boolean allowedAsset(String map, String page, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String want = name.strip().replace(' ', '_');
        for (KbMapStore.Canvas c : mapStore.canvases()) {
            if (map != null && !map.isBlank() && !map.equals(nz(c.map()))) {
                continue;
            }
            if (page != null && !page.isBlank() && !page.equals(nz(c.page()))) {
                continue;
            }
            if (nz(c.backgroundImage()).replace(' ', '_').equals(want)) {
                return true;
            }
            for (TileView t : tilesOf(c.tilesJson())) {
                if (t.image().replace(' ', '_').equals(want)) {
                    return true;
                }
            }
        }
        for (KbMapStore.Group g : mapStore.groups(map)) {
            if (nz(g.icon()).replace(' ', '_').equals(want)) {
                return true;
            }
        }
        return false;
    }

    /** 全部图标的去重清单 —— 管理端一次性预取，省得逐个请求 */
    public Set<String> allIcons() {
        Set<String> out = new LinkedHashSet<>();
        for (String map : mapStore.mapNames()) {
            for (KbMapStore.Group g : mapStore.groups(map)) {
                if (g.icon() != null && !g.icon().isBlank()) {
                    out.add(g.icon());
                }
            }
        }
        return out;
    }

    /** 解析瓦片清单 JSON。解析失败返回空表 —— 画不出底图不该让整页 500 */
    public List<TileView> tilesOf(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = mapper.readTree(json);
            if (!arr.isArray()) {
                return List.of();
            }
            List<TileView> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String image = n.path("image").asText("");
                JsonNode pos = n.path("position");
                if (image.isBlank() || !pos.isArray() || pos.size() < 2) {
                    continue;
                }
                out.add(new TileView(image, pos.get(0).asInt(0), pos.get(1).asInt(0)));
            }
            return out;
        } catch (Exception e) {
            log.warn("[MAP-VIEW] 瓦片清单解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    /* ==================== 标签 ==================== */

    public static String labelOf(String map) {
        return MAP_ZH.getOrDefault(nz(map), nz(map));
    }

    static String groupLabel(String name) {
        String n = nz(name);
        if (n.startsWith("Lore: ")) {
            // 13 个 Lore 分组其实是世界观分类，统一前缀比逐个翻译更不容易出错
            return "世界观·" + n.substring(6);
        }
        return GROUP_ZH.getOrDefault(n, n);
    }

    static String canvasLabel(String page) {
        String p = nz(page);
        int slash = p.lastIndexOf('/');
        String suffix = slash >= 0 ? p.substring(slash + 1) : p;
        String zh = CANVAS_ZH.get(suffix);
        return zh != null ? zh : suffix;
    }

    /** {@code Map:Embervale/Main#AncientSpire-Springlands} → 页面名 */
    private static String pageOf(String ref) {
        if (ref == null) {
            return "";
        }
        int hash = ref.indexOf('#');
        return hash >= 0 ? ref.substring(0, hash) : "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 排障用：一行说清库里有什么 */
    public String describe() {
        Map<String, Long> bySource = new LinkedHashMap<>();
        bySource.put("canvases", (long) mapStore.canvases().size());
        bySource.put("markers", (long) mapStore.markerCount());
        bySource.put("groups", (long) mapStore.groupCount());
        bySource.put("pages", (long) mapStore.pageCount());
        bySource.put("adminPlaces", places.count());
        bySource.put("notes", notes.count());
        return bySource.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("");
    }
}
