package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.PlaceNames;
import com.example.qqbot.kb.ResourceHotspots;
import com.example.qqbot.media.ImageSource;
import com.example.qqbot.onebot.model.OutboundImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 资源层截图 —— 标记 {@code [[img:oreshot:地图/图层/第几片]]} 由它解析。
 *
 * <h2>图上为什么还要画 wiki 地点</h2>
 * 资源点只有坐标，没有名字。只画一片橙色圆点，读图的人只看到地形纹理，
 * 认不出这是地图的哪一块 —— 所以要把同一张画布上的 wiki 地点（图标 + 名字）
 * 一起画进去，用玩家认得的地方来定位。这也正是不画大圈的原因：
 * 圈只说明"看这里"，地点名才说明"这里是哪"。
 *
 * <h2>与地点截图的分工</h2>
 * {@link MapShotSource} 画的是"一个目标点 + 邻近地标"；资源层要画的是
 * <b>一层点云 + 参照地点</b>，所以用单独一个来源，不复用 {@code map:} 前缀。
 * 底图取用同一套 {@link MapShotRenderer} 与同一套缺图/预算语义。
 *
 * <h2>取哪一片</h2>
 * 第几片就是 {@link ResourceHotspots#find} 排出来的顺序，与 {@code locate} 文字里的
 * 第一、第二、第三片一一对应。文字与图因此不会各说各的。
 *
 * <h2>任何一步失败都返回 empty</h2>
 * 图层不存在、热点不够、画布不可渲染、缺瓦片、超预算 —— 都只表现为"这次没有图"，
 * 正文照常发出。
 */
@Component
public class ResourceShotSource implements ImageSource {

    private static final Logger log = LoggerFactory.getLogger(ResourceShotSource.class);

    /** 图上的点用什么颜色 —— 与网页资源层的默认色一致 */
    private static final Color DEFAULT_DOT = new Color(201, 123, 60);

    private final MapViewService view;
    private final MapResourceService resources;
    private final MapImageCache images;
    private final MapShotRenderer renderer;
    private final MapShotWarmer warmer;
    private final MapOptions options;

    public ResourceShotSource(MapViewService view, MapResourceService resources, MapImageCache images,
                              MapShotRenderer renderer, MapShotWarmer warmer, MapOptions options) {
        this.view = view;
        this.resources = resources;
        this.images = images;
        this.renderer = renderer;
        this.warmer = warmer;
        this.options = options;
    }

    @Override
    public String scheme() {
        return "oreshot";
    }

    @Override
    public Optional<OutboundImage> resolve(String key) {
        if (key == null || key.isBlank() || !options.getShot().isEnabled() || !options.isModEnabled()) {
            return Optional.empty();
        }
        String[] parts = key.split("/", -1);
        if (parts.length != 3) {
            log.info("[RES-SHOT] 身份形状不对，本次不出图：{}", key);
            return Optional.empty();
        }
        String map = parts[0].strip();
        String layerKey = parts[1].strip();
        int index;
        try {
            index = Integer.parseInt(parts[2].strip());
        } catch (NumberFormatException e) {
            log.info("[RES-SHOT] 热点序号不是数字，本次不出图：{}", key);
            return Optional.empty();
        }
        if (map.isEmpty() || layerKey.isEmpty() || index < 1) {
            return Optional.empty();
        }

        List<MapResourceService.PointView> rows = resources.points(map, layerKey);
        if (rows == null || rows.isEmpty()) {
            log.info("[RES-SHOT] 图层没有点位，本次不出图：{}", key);
            return Optional.empty();
        }
        List<ResourceHotspots.Point> points = new ArrayList<>(rows.size());
        for (MapResourceService.PointView p : rows) {
            points.add(new ResourceHotspots.Point(p.x(), p.z()));
        }
        MapOptions.Resource cfg = resourceConfig();
        List<ResourceHotspots.Hotspot> hotspots = ResourceHotspots.find(points,
                cfg.getCellSize(), cfg.getNmsRadius(), cfg.getHotRadius(), cfg.getTop());
        if (index > hotspots.size()) {
            log.info("[RES-SHOT] 没有第 {} 片热点，本次不出图：{}", index, key);
            return Optional.empty();
        }
        ResourceHotspots.Hotspot hotspot = hotspots.get(index - 1);

        MapViewService.CanvasView canvas = primaryCanvas(map);
        if (canvas == null) {
            log.info("[RES-SHOT] 地图 {} 没有可渲染的画布，本次不出图", map);
            return Optional.empty();
        }
        MapProjection.Canvas geometry = MapProjection.Canvas.of(
                canvas.topLeft(), canvas.bottomRight(), canvas.tileAt(), canvas.tileSize());

        // 取景只框热点那一小片；整层的点都画，视口外的会被裁掉
        MapProjection.Point topLeft = MapProjection.worldOf(geometry, hotspot.minX(), hotspot.maxY());
        MapProjection.Point bottomRight = MapProjection.worldOf(geometry, hotspot.maxX(), hotspot.minY());
        MapProjection.Point center = MapProjection.worldOf(geometry, hotspot.x(), hotspot.y());
        List<MapProjection.Point> focus = List.of(topLeft, bottomRight, center);

        LayerStyle style = styleOf(map, layerKey);
        List<MapShotRenderer.Dot> dots = new ArrayList<>(rows.size());
        for (MapResourceService.PointView p : rows) {
            MapProjection.Point at = MapProjection.worldOf(geometry, p.x(), p.z());
            dots.add(new MapShotRenderer.Dot(at.x(), at.y(), style.color()));
        }
        List<MapShotRenderer.Mark> context = contextMarks(map, canvas.page(), geometry, hotspot,
                cfg.getLabelMax(), cfg.getMaxContext());

        long deadline = System.currentTimeMillis() + options.getShot().getDeadlineMs();
        List<String> missing = new ArrayList<>();
        int[] usedWidth = {0};
        MapShotRenderer.TileFetcher fetcher = new MapShotRenderer.TileFetcher() {
            @Override
            public Optional<MapImageCache.Image> get(String image, int width) {
                usedWidth[0] = width;
                Optional<MapImageCache.Image> got = images.get(map, canvas.page(), image, width);
                if (got.isEmpty()) {
                    missing.add(image);
                }
                return got;
            }

            @Override
            public void need(String image, int width) {
                usedWidth[0] = width;
                missing.add(image);
            }

            @Override
            public Optional<MapImageCache.Image> icon(String image) {
                // 图标只读缓存：回复路径上不能等上游
                return images.cached(map, null, image, 0);
            }
        };

        String caption = style.name() + " · " + rows.size() + " 个点";
        Optional<byte[]> shot = renderer.renderDots(geometry, canvas.tiles(), canvas.background(),
                dots, focus, context, caption, fetcher, deadline);
        if (shot.isEmpty() && !missing.isEmpty()) {
            warmer.warmAsync(map, canvas.page(), missing, usedWidth[0]);
        }
        return shot.map(bytes -> OutboundImage.ofBytes(bytes, "image/jpeg"));
    }

    /**
     * 资源图的底图：优先规范主图（{@code /Background}），否则取面积最大的那张。
     * 资源点与 wiki 点位是同一套坐标，但必须落在正确的那张画布上。
     */
    private MapViewService.CanvasView primaryCanvas(String map) {
        List<MapViewService.CanvasView> canvases = view.canvasesOf(map);
        if (canvases == null || canvases.isEmpty()) {
            return null;
        }
        for (MapViewService.CanvasView c : canvases) {
            if (c.page() != null && c.page().endsWith("/Background")) {
                return c;
            }
        }
        MapViewService.CanvasView best = null;
        double bestArea = -1;
        for (MapViewService.CanvasView c : canvases) {
            double[] extent = c.extent();
            double area = Math.abs(extent[0] * extent[1]);
            if (area > bestArea) {
                bestArea = area;
                best = c;
            }
        }
        return best;
    }

    /**
     * 同一张画布上的 wiki 地点，按离热点的距离取最近的若干个。
     *
     * <p>只画公开可见、默认显示的分组——与网页地图同一条规则。
     * 名字只给最近的 {@code labelMax} 个：再多会互相压住。
     */
    private List<MapShotRenderer.Mark> contextMarks(String map, String canvasPage,
                                                    MapProjection.Canvas geometry,
                                                    ResourceHotspots.Hotspot hotspot,
                                                    int labelMax, int maxContext) {
        MapViewService.MapView mapView = null;
        try {
            for (MapViewService.MapView m : view.catalog()) {
                if (map.equals(m.map())) {
                    mapView = m;
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("[RES-SHOT] 读地图目录失败，参照地点不做分组过滤：{}", e.getMessage());
        }
        Set<String> shown = mapView == null ? null : mapView.shownGroups();
        Map<String, String> icons = new HashMap<>();
        if (mapView != null) {
            for (MapViewService.GroupView g : mapView.groups()) {
                if (g.icon() != null && !g.icon().isBlank()) {
                    icons.put(g.key(), g.icon());
                }
            }
        }

        List<MapViewService.MarkerView> visible;
        try {
            visible = view.markers(map, true);
        } catch (Exception e) {
            log.debug("[RES-SHOT] 读 wiki 点位失败，本张图不画参照地点：{}", e.getMessage());
            return List.of();
        }
        if (visible == null || visible.isEmpty()) {
            return List.of();
        }

        record Candidate(double distance, MapViewService.MarkerView marker) {
        }
        List<Candidate> found = new ArrayList<>();
        for (MapViewService.MarkerView m : visible) {
            if (!nz(canvasPage).equals(nz(m.canvas()))) {
                continue;
            }
            if (shown != null && !shown.contains(nz(m.group()))) {
                continue;
            }
            if (m.name() == null || m.name().isBlank()) {
                continue;
            }
            double dx = m.x() - hotspot.x();
            double dy = m.y() - hotspot.y();
            found.add(new Candidate(dx * dx + dy * dy, m));
        }
        found.sort(Comparator.comparingDouble(Candidate::distance));

        int limit = Math.min(found.size(), Math.max(1, maxContext));
        List<MapShotRenderer.Mark> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            MapViewService.MarkerView m = found.get(i).marker();
            MapProjection.Point at = MapProjection.worldOf(geometry, m.x(), m.y());
            String label = i < labelMax ? PlaceNames.zh(m.name()) : "";
            out.add(MapShotRenderer.Mark.icon(at.x(), at.y(), label,
                    icons.getOrDefault(nz(m.group()), "")));
        }
        return out;
    }

    /** 图层颜色与说明文字；取不到就用默认色，绝不因为配色失败不出图 */
    private LayerStyle styleOf(String map, String layerKey) {
        try {
            for (MapResourceService.CategoryView category : resources.catalog(map).categories()) {
                for (MapResourceService.LayerView layer : category.layers()) {
                    if (!layer.key().equals(layerKey)) {
                        continue;
                    }
                    Color color = DEFAULT_DOT;
                    if (layer.color() != null && !layer.color().isBlank()) {
                        color = Color.decode(layer.color());
                    }
                    String name = layer.name() == null ? "" : layer.name().strip();
                    if (name.length() == 1) {
                        name = name + "矿";
                    }
                    return new LayerStyle(color, name.isBlank() ? layerKey : name);
                }
            }
        } catch (Exception e) {
            log.debug("[RES-SHOT] 取图层样式失败，用默认值：{}", e.getMessage());
        }
        return new LayerStyle(DEFAULT_DOT, layerKey);
    }

    private record LayerStyle(Color color, String name) {
    }

    /** 配置缺失时用默认值，无配置的调用方也能正常出图 */
    private MapOptions.Resource resourceConfig() {
        MapOptions.Resource cfg = options.getResource();
        return cfg == null ? new MapOptions.Resource() : cfg;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
