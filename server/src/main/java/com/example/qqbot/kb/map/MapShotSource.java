package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.PlaceDirectory;
import com.example.qqbot.kb.PlacePoint;
import com.example.qqbot.kb.RelativeLocation;
import com.example.qqbot.media.ImageSource;
import com.example.qqbot.onebot.model.OutboundImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 地图截图作为出站图片的来源 —— 标记 {@code [[img:map:<点位身份>]]} 由它解析。
 *
 * <h2>它只做四件事</h2>
 * <ol>
 *   <li>按身份找到点位；</li>
 *   <li>找到它该画在哪张画布上 —— <b>用现成的归属，不自己猜</b>：同一张地图有多套坐标系，
 *       猜错会让标记偏上万像素，而且只是"图不对"，不报错；</li>
 *   <li>把最近的地标也算出来一起画进图里：群友靠地标认路，比看孤零零一个点有用；</li>
 *   <li>交给 {@link MapShotRenderer}，把取瓦片的预算卡死。</li>
 * </ol>
 *
 * <h2>任何一步失败都返回 empty</h2>
 * 找不到点位、画布不可用、缺瓦片、超预算 —— 一律只表现为"这次没有图"，
 * 正文照常发出。出图是锦上添花，绝不能让一次回答失败。
 */
@Component
public class MapShotSource implements ImageSource {

    private static final Logger log = LoggerFactory.getLogger(MapShotSource.class);

    private final MapViewService view;
    private final PlaceDirectory places;
    private final MapImageCache images;
    private final MapShotRenderer renderer;
    private final MapShotWarmer warmer;
    private final MapOptions options;

    public MapShotSource(MapViewService view, PlaceDirectory places, MapImageCache images,
                         MapShotRenderer renderer, MapShotWarmer warmer, MapOptions options) {
        this.view = view;
        this.places = places;
        this.images = images;
        this.renderer = renderer;
        this.warmer = warmer;
        this.options = options;
    }

    @Override
    public String scheme() {
        return "map";
    }

    @Override
    public Optional<OutboundImage> resolve(String key) {
        if (key == null || key.isBlank() || !options.getShot().isEnabled()) {
            return Optional.empty();
        }
        for (MapViewService.MapView map : view.catalog()) {
            for (MapViewService.MarkerView marker : view.markers(map.map(), false)) {
                if (!PlacePoint.keyOf(marker.id()).equals(key)) {
                    continue;
                }
                return render(map.map(), marker);
            }
        }
        log.info("[MAP-SHOT] 没有这个身份的点位，本次不出图：{}", key);
        return Optional.empty();
    }

    private Optional<OutboundImage> render(String map, MapViewService.MarkerView marker) {
        MapViewService.CanvasView canvas = view.canvasesOf(map).stream()
                .filter(c -> c.page().equals(marker.canvas()))
                .findFirst()
                .orElse(null);
        if (canvas == null) {
            log.info("[MAP-SHOT] 点位 {} 的画布 {} 不可渲染，本次不出图", marker.id(), marker.canvas());
            return Optional.empty();
        }
        MapProjection.Canvas geometry = MapProjection.Canvas.of(
                canvas.topLeft(), canvas.bottomRight(), canvas.tileAt(), canvas.tileSize());

        MapProjection.Point target = MapProjection.worldOf(geometry, marker.x(), marker.y());
        List<MapShotRenderer.Mark> marks = new ArrayList<>();
        marks.add(new MapShotRenderer.Mark(target.x(), target.y(), true, marker.name()));
        nearestLandmark(map, marker, geometry).ifPresent(point -> {
            MapProjection.Point at = MapProjection.worldOf(geometry, point.x(), point.y());
            marks.add(new MapShotRenderer.Mark(at.x(), at.y(), false, point.name()));
        });

        long deadline = System.currentTimeMillis() + options.getShot().getDeadlineMs();
        // 顺手记下缺口：渲染器会把相交的瓦片都试一遍，所以这里拿到的是**完整**的缺失清单
        List<String> missing = new ArrayList<>();
        int[] usedWidth = {0};
        MapShotRenderer.TileFetcher fetcher = (image, width) -> {
            usedWidth[0] = width;
            Optional<MapImageCache.Image> got = images.get(map, canvas.page(), image, width);
            if (got.isEmpty()) {
                missing.add(image);
            }
            return got;
        };

        Optional<byte[]> shot = renderer.render(geometry, canvas.tiles(), canvas.background(),
                marks, fetcher, deadline);
        if (shot.isEmpty() && !missing.isEmpty()) {
            // 这次没图，但缺的那几张已经交给后台了 —— 同一个点位再问一次就有
            warmer.warmAsync(map, canvas.page(), missing, usedWidth[0]);
        }
        return shot.map(bytes -> OutboundImage.ofBytes(bytes, "image/jpeg"));
    }

    /**
     * 目标点最近的地标，与 {@code locate} 用的是**同一套选择逻辑**
     * （{@link RelativeLocation#pickAnchor}），所以图上的参照物和文字里的方位说的是同一个地方。
     *
     * <p>只取同一张画布上的地标：不同画布的坐标不能直接比距离。
     */
    private Optional<PlacePoint> nearestLandmark(String map, MapViewService.MarkerView marker,
                                                 MapProjection.Canvas geometry) {
        PlacePoint target = null;
        List<PlacePoint> candidates = new ArrayList<>();
        for (PlacePoint p : places.all()) {
            if (!map.equals(nz(p.map()))) {
                continue;
            }
            if (marker.id().equals(p.ref())) {
                target = p;
            } else if (p.landmark() && nz(p.canvas()).equals(nz(marker.canvas()))) {
                candidates.add(p);
            }
        }
        if (target == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        double[] size = places.worldSizes().get(map);
        double diagonal = RelativeLocation.diagonal(
                size != null ? size : new double[]{geometry.width(), geometry.height()});
        RelativeLocation.Neighbour neighbour = RelativeLocation.pickAnchor(target, candidates, diagonal);
        return neighbour == null ? Optional.empty() : Optional.of(neighbour.anchor());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
