package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.PlaceDirectory;
import com.example.qqbot.kb.PlaceNames;
import com.example.qqbot.kb.PlacePoint;
import com.example.qqbot.kb.ResourceDirectory;
import com.example.qqbot.kb.ResourceHotspots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@link ResourceDirectory} 的实现 —— 把地图 MOD 的资源层接进问答。
 *
 * <h2>为什么匹配的是"层"，不是"点"</h2>
 * 资源点没有独立名字：747 个铜点全叫「铜」。给点做检索只会得到 747 条一样的记录，
 * 给层做匹配才能回答"铜矿在哪"。层名、颜色、点数在 {@code kb_map_resource_layer} 里都是现成的。
 *
 * <h2>为什么不用向量</h2>
 * 可问的资源名是闭集（层名 + 常见叫法），确定性匹配更准、可解释、零成本；
 * 向量留给物品、配方、机制这些真正有文本的资料。
 *
 * <h2>别名规则</h2>
 * 层名（至少 2 字）直接命中；矿类再补「X矿 / X矿石 / X矿脉」与英文别名，按最长命中。
 * 单字「铜 / 铁 / 金 / 银」不单独参与匹配 —— 否则「青铜锭」会被误判成铜矿。
 */
@Component
public class MapResourceDirectory implements ResourceDirectory {

    private static final Logger log = LoggerFactory.getLogger(MapResourceDirectory.class);

    /* 有坐标的图层都能问；林地面这类没有逐点的层，points() 为空，自然返回 null */

    /**
     * 人工核对的英文名与常见叫法。中文的「X矿 / X矿石 / X矿脉」由 {@link #aliasesFor} 自动生成，
     * 这里只补自动规则覆盖不到的写法（煤矿 vs 煤炭、盐矿 vs 盐、海蓝宝石……）。
     */
    private static final Map<String, List<String>> CURATED = Map.ofEntries(
            Map.entry("copper", List.of("铜矿", "铜矿石", "铜矿脉", "copper", "copper ore", "copper vein")),
            Map.entry("iron", List.of("铁矿", "铁矿石", "铁矿脉", "iron", "iron ore", "iron vein")),
            Map.entry("silver", List.of("银矿", "银矿石", "银矿脉", "silver", "silver ore")),
            Map.entry("gold", List.of("金矿", "金矿石", "金矿脉", "gold", "gold ore")),
            Map.entry("tin", List.of("锡矿", "锡矿石", "锡矿脉", "tin", "tin ore")),
            Map.entry("coal", List.of("煤矿", "煤炭", "coal", "coal ore")),
            Map.entry("salt", List.of("盐矿", "盐", "salt", "salt mine")),
            Map.entry("sulfur", List.of("硫磺矿", "硫磺", "sulfur", "sulphur")),
            Map.entry("flintstone", List.of("燧石", "燧石矿", "flintstone", "flint")),
            Map.entry("clay", List.of("黏土", "黏土矿", "clay")),
            Map.entry("amber", List.of("琥珀", "琥珀矿", "amber")),
            Map.entry("limestone", List.of("石灰石", "石灰石矿", "limestone")),
            Map.entry("lapis_lazuli", List.of("青金石", "青金石矿", "lapis lazuli", "lapis")),
            Map.entry("amethyst", List.of("紫水晶", "紫水晶矿", "amethyst")),
            Map.entry("obsidian", List.of("黑曜石", "黑曜石矿", "obsidian")),
            Map.entry("aquamarine", List.of("海蓝宝石", "海蓝宝石矿", "aquamarine")),
            Map.entry("mother_of_pearl", List.of("珍珠母", "mother of pearl")),
            Map.entry("red_marble", List.of("红色大理石", "红色大理石矿", "red marble")),
            Map.entry("diamond", List.of("钻石", "钻石矿", "diamond")),
            Map.entry("fossilized_bone", List.of("骨化石", "fossilized bone")),
            Map.entry("celesthium", List.of("Celesthium", "celesthium ore")));

    /** 图层目录的缓存时长。图层只在导入时变，没必要每次提问都读一遍库、算一遍文件哈希 */
    private static final long LAYERS_TTL_MS = 60_000;

    private final MapResourceService resources;
    private final PlaceDirectory places;
    private final KbMapStore mapStore;
    private final MapScope scope;
    private final MapOptions options;

    private List<Layer> cachedLayers = List.of();
    private long cachedAt;

    public MapResourceDirectory(MapResourceService resources, PlaceDirectory places,
                                KbMapStore mapStore, MapScope scope, MapOptions options) {
        this.resources = resources;
        this.places = places;
        this.mapStore = mapStore;
        this.scope = scope;
        this.options = options;
    }

    @Override
    public boolean isAvailable() {
        return options.isModEnabled() && !layers().isEmpty();
    }

    @Override
    public Answer answer(String term) {
        if (term == null || term.isBlank() || !options.isModEnabled()) {
            return null;
        }
        try {
            Layer layer = match(term.strip(), layers());
            if (layer == null) {
                return null;
            }
            List<MapResourceService.PointView> rows = resources.points(layer.map(), layer.key());
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            List<ResourceHotspots.Point> points = new ArrayList<>(rows.size());
            for (MapResourceService.PointView p : rows) {
                points.add(new ResourceHotspots.Point(p.x(), p.z()));
            }
            List<ResourceHotspots.Hotspot> found = ResourceHotspots.find(points);
            if (found.isEmpty()) {
                return null;
            }
            List<PlacePoint> anchors = landmarks(layer.map());
            List<Hotspot> hotspots = new ArrayList<>(found.size());
            for (ResourceHotspots.Hotspot h : found) {
                hotspots.add(withAnchor(h, anchors, regionOf(h, rows)));
            }
            log.info("[RES] 「{}」→ {}（{} 个点，{} 处热点）", term.strip(), layer.key(),
                    points.size(), hotspots.size());
            return new Answer(displayName(layer), layer, points.size(),
                    (int) ResourceHotspots.HOT_RADIUS, hotspots);
        } catch (Exception e) {
            // 资源分布是加分项，任何一步失败都只表现为"这次没有"，绝不能把 locate 带走
            log.warn("[RES] 资源热点计算失败，本次按没有处理：{} —— {}", term, e.getMessage());
            return null;
        }
    }

    /** 当前启用地图下的矿类图层。地图没导入资源时返回空 */
    private synchronized List<Layer> layers() {
        if (!options.isModEnabled()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        // cachedAt=0 是"还没查过"；查过之后即使是空表也缓存一分钟，避免每个问题都算一遍文件哈希
        if (cachedAt != 0 && now - cachedAt < LAYERS_TTL_MS) {
            return cachedLayers;
        }
        List<Layer> out = new ArrayList<>();
        // 排序后再遍历：同一份数据每次匹配的顺序必须一致
        for (String map : new TreeSet<>(scope.activeMaps())) {
            MapResourceService.CatalogView catalog;
            try {
                catalog = resources.catalog(map);
            } catch (Exception e) {
                log.debug("[RES] 读图层目录失败 map={}：{}", map, e.getMessage());
                continue;
            }
            if (catalog == null || !catalog.imported()) {
                continue;
            }
            for (MapResourceService.CategoryView category : catalog.categories()) {
                for (MapResourceService.LayerView l : category.layers()) {
                    if (l.count() <= 0) {
                        continue;
                    }
                    out.add(new Layer(map, l.key(), l.name(), l.kind(), l.subtype(),
                            l.count(), l.color()));
                }
            }
        }
        cachedLayers = List.copyOf(out);
        cachedAt = now;
        return cachedLayers;
    }

    /** 提问里包含哪个层名 / 别名；按最长命中，同长取点数多的。对不上返回 null */
    static Layer match(String query, List<Layer> layers) {
        if (query == null || query.isBlank() || layers == null || layers.isEmpty()) {
            return null;
        }
        String q = query.strip().toLowerCase(Locale.ROOT);
        Layer best = null;
        int bestLength = 0;
        for (Layer layer : layers) {
            // 整词等于层名也认：模型可能把「铜矿在哪」抽成「铜」再传进来
            String name = layer.name() == null ? "" : layer.name().strip().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && q.equals(name)
                    && (name.length() > bestLength
                    || (name.length() == bestLength && best != null && layer.count() > best.count()))) {
                best = layer;
                bestLength = name.length();
            }
            for (String alias : aliasesFor(layer)) {
                String a = alias.toLowerCase(Locale.ROOT);
                if (a.length() < 2 || !contains(q, a)) {
                    continue;
                }
                if (a.length() > bestLength
                        || (a.length() == bestLength && best != null && layer.count() > best.count())) {
                    best = layer;
                    bestLength = a.length();
                }
            }
        }
        return best;
    }

    /** 回答里用的资源名：单字矿种补成「铜矿 / 铁矿」，其余直接用层名 */
    static String displayName(Layer layer) {
        String name = layer.name() == null ? "" : layer.name().strip();
        if ("ore".equals(layer.kind()) && name.length() == 1) {
            return name + "矿";
        }
        return name.isBlank() ? layer.key() : name;
    }

    /** 一个图层能被哪些说法命中。中文按规则生成，英文与特例来自 {@link #CURATED} */
    static List<String> aliasesFor(Layer layer) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String name = layer.name() == null ? "" : layer.name().strip();
        if (name.length() >= 2) {
            out.add(name);
        }
        if ("ore".equals(layer.kind()) && !name.isEmpty()) {
            if (!name.endsWith("矿")) {
                out.add(name + "矿");
                // 单字矿种才有「铜矿石 / 铜矿脉」这种说法；「煤炭矿石」不成立
                if (name.length() == 1) {
                    out.add(name + "矿石");
                    out.add(name + "矿脉");
                }
            }
            out.addAll(CURATED.getOrDefault(layer.subtype(), List.of()));
        }
        return new ArrayList<>(out);
    }

    /**
     * 子串命中。英文别名要求前后都是非字母 —— 否则 {@code iron} 会命中 {@code environment}。
     * 中文不设边界（本来就没有词边界）。
     */
    private static boolean contains(String query, String alias) {
        int from = 0;
        while (true) {
            int i = query.indexOf(alias, from);
            if (i < 0) {
                return false;
            }
            int end = i + alias.length();
            boolean left = i == 0 || !isLetter(query.charAt(i - 1))
                    || !isLetter(alias.charAt(0));
            boolean right = end >= query.length() || !isLetter(query.charAt(end))
                    || !isLetter(alias.charAt(alias.length() - 1));
            if (left && right) {
                return true;
            }
            from = i + 1;
        }
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * 同一张地图、同一张主画布上的地标。热点坐标与 wiki 点位是同一套坐标系，
     * 但不同画布原点不同，跨画布算出来的方位是垃圾。
     */
    private List<PlacePoint> landmarks(String map) {
        if (!places.isAvailable()) {
            return List.of();
        }
        String primary = "";
        try {
            primary = WikiPlaceDirectory.primaryCanvasOf(mapStore.canvases())
                    .getOrDefault(map, "");
        } catch (Exception e) {
            log.debug("[RES] 取主画布失败，地标不做画布过滤：{}", e.getMessage());
        }
        List<PlacePoint> out = new ArrayList<>();
        for (PlacePoint p : places.all()) {
            if (!map.equals(p.map()) || !p.landmark()) {
                continue;
            }
            if (!primary.isBlank() && !primary.equals(p.canvas())) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /**
     * 热点落在哪个区域：取热点半径内点数最多的那个区域。
     * 区域只存了英文名（来自 points.json），回答时再由 {@link PlaceNames#regionZh} 转中文。
     */
    private static String regionOf(ResourceHotspots.Hotspot h, List<MapResourceService.PointView> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (MapResourceService.PointView p : rows) {
            if (p.regionName() == null || p.regionName().isBlank()) {
                continue;
            }
            if (Math.hypot(p.x() - h.x(), p.z() - h.y()) > ResourceHotspots.HOT_RADIUS) {
                continue;
            }
            counts.merge(p.regionName(), 1, Integer::sum);
        }
        String best = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount
                    || (e.getValue() == bestCount && e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static Hotspot withAnchor(ResourceHotspots.Hotspot h, List<PlacePoint> anchors, String region) {
        PlacePoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PlacePoint p : anchors) {
            double d = Math.hypot(p.x() - h.x(), p.y() - h.y());
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        if (best == null) {
            return new Hotspot(h.x(), h.y(), h.count(), region,
                    h.minX(), h.minY(), h.maxX(), h.maxY(), "", 0, 0, 0);
        }
        double dx = h.x() - best.x();
        double dy = h.y() - best.y();
        return new Hotspot(h.x(), h.y(), h.count(), region,
                h.minX(), h.minY(), h.maxX(), h.maxY(),
                PlaceNames.zh(best.name()), Math.hypot(dx, dy), dx, dy);
    }
}
