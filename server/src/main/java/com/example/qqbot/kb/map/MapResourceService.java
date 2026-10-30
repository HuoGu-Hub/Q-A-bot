package com.example.qqbot.kb.map;

import com.example.qqbot.persistence.MapResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源图层的只读视图。
 *
 * <p>目录和点位分开取：94 个图层、36,126 个点，一次全下发会让首屏多出几百 KB。
 * 前端先拿目录画开关，用户打开哪一层再取哪一层的坐标。
 */
@Service
public class MapResourceService {

    private static final Logger log = LoggerFactory.getLogger(MapResourceService.class);

    private static final List<String> CATEGORY_ORDER = List.of(
            "chest", "beacon", "water", "ore", "material", "plant", "bait",
            "lore", "boss", "unique", "collectible", "summon", "tree");

    private static final Map<String, String> CATEGORY_NAMES = Map.ofEntries(
            Map.entry("chest", "宝箱"),
            Map.entry("beacon", "快速旅行"),
            Map.entry("water", "水源"),
            Map.entry("ore", "采矿"),
            Map.entry("material", "材料"),
            Map.entry("plant", "植物"),
            Map.entry("bait", "诱饵"),
            Map.entry("lore", "笔记"),
            Map.entry("boss", "首领"),
            Map.entry("unique", "独特装备"),
            Map.entry("collectible", "收藏品"),
            Map.entry("summon", "堕落召唤石"),
            Map.entry("tree", "林地"));

    public record LayerView(String key, String name, String kind, String subtype,
                            int count, String color, String icon, String shape) {
    }

    public record CategoryView(String key, String name, int count, List<LayerView> layers) {
    }

    public record CatalogView(boolean imported, String version, String importedAt, String message,
                              List<CategoryView> categories) {
    }

    public record PointView(double x, double z, String name, int region, String regionName) {
    }

    public record AreaView(double x0, double z0, double x1, double z1, String name) {
    }

    private final MapResourceRepository repo;
    private final MapResourceImporter importer;

    public MapResourceService(MapResourceRepository repo, MapResourceImporter importer) {
        this.repo = repo;
        this.importer = importer;
    }

    public CatalogView catalog(String map) {
        if (!repo.isAvailable()) {
            return new CatalogView(false, "", "", "问答库不可用", List.of());
        }
        MapResourceImporter.ImportReport report = importer.ensureLoaded();
        List<MapResourceRepository.LayerRow> layers;
        try {
            layers = repo.layers(map);
        } catch (Exception e) {
            log.warn("[MAP-RES] 读图层失败：{}", e.getMessage());
            return new CatalogView(false, "", "", "读取图层失败", List.of());
        }
        if (layers.isEmpty()) {
            return new CatalogView(false, report.version(), "",
                    report.message().isBlank() ? "还没有导入资源点位" : report.message(), List.of());
        }
        MapResourceRepository.MetaRow meta = repo.meta(map);
        Map<String, List<LayerView>> grouped = new LinkedHashMap<>();
        for (MapResourceRepository.LayerRow l : layers) {
            String category = categoryOf(l.kind());
            grouped.computeIfAbsent(category, k -> new ArrayList<>())
                    .add(new LayerView(l.key(), l.nameZh().isBlank() ? l.name() : l.nameZh(),
                            l.kind(), l.subtype(), l.count(), l.color(), l.icon(),
                            "tree".equals(l.kind()) ? "area" : "point"));
        }
        List<CategoryView> categories = new ArrayList<>();
        for (String key : CATEGORY_ORDER) {
            List<LayerView> list = grouped.get(key);
            if (list == null || list.isEmpty()) {
                continue;
            }
            int count = list.stream().mapToInt(LayerView::count).sum();
            categories.add(new CategoryView(key, CATEGORY_NAMES.getOrDefault(key, key), count, list));
        }
        return new CatalogView(true, meta == null ? report.version() : meta.version(),
                meta == null ? "" : meta.importedAt(), "", categories);
    }

    public List<PointView> points(String map, String layer) {
        if (!repo.isAvailable() || layer == null || layer.isBlank()) {
            return List.of();
        }
        importer.ensureLoaded();
        try {
            List<PointView> out = new ArrayList<>();
            for (MapResourceRepository.PointRow p : repo.points(map, layer)) {
                out.add(new PointView(p.x(), p.z(), p.name(), p.region(), p.regionName()));
            }
            return out;
        } catch (Exception e) {
            log.warn("[MAP-RES] 读点位失败 layer={}：{}", layer, e.getMessage());
            return List.of();
        }
    }

    public List<AreaView> areas(String map, String layer) {
        if (!repo.isAvailable() || layer == null || layer.isBlank()) {
            return List.of();
        }
        importer.ensureLoaded();
        try {
            List<AreaView> out = new ArrayList<>();
            for (MapResourceRepository.AreaRow a : repo.areas(map, layer)) {
                out.add(new AreaView(a.x0(), a.z0(), a.x1(), a.z1(), a.name()));
            }
            return out;
        } catch (Exception e) {
            log.warn("[MAP-RES] 读林地面失败 layer={}：{}", layer, e.getMessage());
            return List.of();
        }
    }

    public String version(String map) {
        MapResourceRepository.MetaRow meta = repo.meta(map);
        return meta == null ? "" : meta.version();
    }

    private static String categoryOf(String kind) {
        if ("unique_weapon".equals(kind) || "unique_equipment".equals(kind)) {
            return "unique";
        }
        return kind == null ? "world" : kind;
    }
}
