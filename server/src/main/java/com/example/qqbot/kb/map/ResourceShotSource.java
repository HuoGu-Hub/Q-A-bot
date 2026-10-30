package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.ResourceDirectory;
import com.example.qqbot.kb.ResourceHotspots;
import com.example.qqbot.media.ImageSource;
import com.example.qqbot.onebot.model.OutboundImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 资源层截图 —— 标记 {@code [[img:oreshot:地图/图层/第几片]]} 由它解析。
 *
 * <h2>与地点截图的分工</h2>
 * {@link MapShotSource} 画的是"一个目标点 + 邻近地标图标"；资源层要画的是
 * <b>一层点云</b>，所以用单独一个来源，不复用 {@code map:} 前缀。
 * 底图取用同一套 {@link MapShotRenderer} 与同一套缺图/预算语义。
 *
 * <h2>取哪一片</h2>
 * 第几片就是 {@link ResourceHotspots#find} 排出来的顺序，与 {@code locate} 文字里的
 * 第一、第二、第三片一一对应。图和文字因此不会各说各的。
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
        List<ResourceHotspots.Hotspot> hotspots = ResourceHotspots.find(points);
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

        // 取景只框热点那一小片；整层的点都画，但视口外的会被裁掉
        MapProjection.Point topLeft = MapProjection.worldOf(geometry, hotspot.minX(), hotspot.maxY());
        MapProjection.Point bottomRight = MapProjection.worldOf(geometry, hotspot.maxX(), hotspot.minY());
        MapProjection.Point center = MapProjection.worldOf(geometry, hotspot.x(), hotspot.y());
        List<MapProjection.Point> focus = List.of(topLeft, bottomRight, center);

        Color color = colorOf(map, layerKey);
        List<MapShotRenderer.Dot> dots = new ArrayList<>(rows.size());
        for (MapResourceService.PointView p : rows) {
            MapProjection.Point at = MapProjection.worldOf(geometry, p.x(), p.z());
            dots.add(new MapShotRenderer.Dot(at.x(), at.y(), color));
        }

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
        };

        Optional<byte[]> shot = renderer.renderDots(geometry, canvas.tiles(), canvas.background(),
                dots, focus, center, ResourceHotspots.HOT_RADIUS, fetcher, deadline);
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

    /** 图层颜色来自资源目录；取不到就用默认色，绝不因为配色失败不出图 */
    private Color colorOf(String map, String layerKey) {
        try {
            for (MapResourceService.CategoryView category : resources.catalog(map).categories()) {
                for (MapResourceService.LayerView layer : category.layers()) {
                    if (layer.key().equals(layerKey) && layer.color() != null
                            && !layer.color().isBlank()) {
                        return Color.decode(layer.color());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[RES-SHOT] 取图层颜色失败，用默认色：{}", e.getMessage());
        }
        return DEFAULT_DOT;
    }
}
