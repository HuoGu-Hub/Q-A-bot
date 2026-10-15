package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.PlacePoint;
import com.example.qqbot.media.ImageSource;
import com.example.qqbot.onebot.model.OutboundImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 地图截图作为出站图片的来源 —— 标记 {@code [[img:map:<点位身份>]]} 由它解析。
 *
 * <h2>它只做四件事</h2>
 * <ol>
 *   <li>按身份找到点位；</li>
 *   <li>找到它该画在哪张画布上 —— <b>用现成的归属，不自己猜</b>：同一张地图有多套坐标系，
 *       猜错会让标记偏上万像素，而且只是"图不对"，不报错；</li>
 *   <li>把这张图上**默认显示**的点位一并画进去（图标 + 名字）—— 只有目标点一个标记时，
 *       群友面对的是一片地形纹理，看不出这是地图的哪一块，更看不出旁边有什么；</li>
 *   <li>只给目标点套红环：环的意思是"看这里"，一圈好几个等于一个都没圈；</li>
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
    private final MapImageCache images;
    private final MapShotRenderer renderer;
    private final MapShotWarmer warmer;
    private final MapOptions options;

    public MapShotSource(MapViewService view, MapImageCache images,
                         MapShotRenderer renderer, MapShotWarmer warmer, MapOptions options) {
        this.view = view;
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
                return render(map, marker);
            }
        }
        log.info("[MAP-SHOT] 没有这个身份的点位，本次不出图：{}", key);
        return Optional.empty();
    }

    private Optional<OutboundImage> render(MapViewService.MapView mapView, MapViewService.MarkerView marker) {
        String map = mapView.map();
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

        Map<String, String> iconOf = iconsOf(mapView);
        // 只有公开站也看得见的点位才配进图 —— 藏起来的不能借截图漏出去
        List<MapViewService.MarkerView> visible = view.markers(map, true);

        // 目标点：带自己那一层的图标，画的时候再套一圈红环
        MapProjection.Point target = MapProjection.worldOf(geometry, marker.x(), marker.y());
        List<MapShotRenderer.Mark> marks = new ArrayList<>();
        marks.add(new MapShotRenderer.Mark(target.x(), target.y(), true, marker.name(),
                iconOf.getOrDefault(nz(marker.group()), "")));

        List<MapShotRenderer.Mark> context = contextIcons(mapView, marker, geometry, target, iconOf, visible);

        long deadline = System.currentTimeMillis() + options.getShot().getDeadlineMs();
        // 缺口清单有两个来源：取失败的（get 返回空）与**超预算没去取的**（need）。
        // 少记后者就等于"不漏图"这件事只对取失败的瓦片成立 —— 见 TileFetcher#need。
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
                // 图标按分组走、不带档位、也没有页面归属 —— 与预热时同一个调用形状。
                // ★ 必须**只读缓存**：出图跑在回复路径上，一张没预热到的图标就是一次
                //   好几秒的上游请求（出站全局串行节流），十几个图标能把回复拖到不可用。
                //   没预热到就少画一个图标，由预热任务负责补齐。
                return images.cached(map, null, image, 0);
            }
        };

        Optional<byte[]> shot = renderer.render(geometry, canvas.tiles(), canvas.background(),
                marks, context, fetcher, deadline);
        if (shot.isEmpty() && !missing.isEmpty()) {
            // 这次没图，但缺的那几张已经交给后台了 —— 同一个点位再问一次就有
            warmer.warmAsync(map, canvas.page(), missing, usedWidth[0]);
        }
        return shot.map(bytes -> OutboundImage.ofBytes(bytes, "image/jpeg"));
    }

    /** 分组 key → 图标文件名。没有图标的分组不进表 —— 出图时"查不到"就是"不画" */
    private static Map<String, String> iconsOf(MapViewService.MapView map) {
        Map<String, String> out = new HashMap<>();
        for (MapViewService.GroupView g : map.groups()) {
            if (g.icon() != null && !g.icon().isBlank()) {
                out.put(g.key(), g.icon());
            }
        }
        return out;
    }

    /**
     * 背景图标：把这张图上**默认显示**的点位一并截进来。
     *
     * <h2>为什么非有不可</h2>
     * 只有目标点与地标两三个圈时，读图的人面对的是一片地形纹理 —— 看不出这是地图的哪一块，
     * 更看不出旁边有什么。把默认显示的图标一起画上，截图才像"地图上的一角"。
     *
     * <h2>为什么图标全画、名字只标几个</h2>
     * 图标回答"这里有什么"，一屏几十个正是地图该有的样子；名字回答"这是哪一处"，
     * 一屏几十个就会互相压住，一个都读不成。所以名字只给**离目标点最近**的几个
     * （{@code app.kb.map.shot.label-max}）—— 认方位用的正是它们，其余点位有图标就够了。
     *
     * <p>筛选口径与公开站一致：<b>当前画布 + 默认显示的分组</b>。目标点跳过 —— 它由
     * {@code marks} 单独画（多一圈红环），名字也一定写。
     */
    private List<MapShotRenderer.Mark> contextIcons(MapViewService.MapView mapView,
                                                    MapViewService.MarkerView marker,
                                                    MapProjection.Canvas geometry,
                                                    MapProjection.Point target,
                                                    Map<String, String> iconOf,
                                                    List<MapViewService.MarkerView> visible) {
        record Candidate(double distance, MapShotRenderer.Mark mark) {
        }
        Set<String> shown = mapView.shownGroups();
        List<Candidate> found = new ArrayList<>();
        for (MapViewService.MarkerView m : visible) {
            if (!nz(marker.canvas()).equals(nz(m.canvas())) || marker.id().equals(m.id())
                    || !shown.contains(nz(m.group()))) {
                continue;
            }
            MapProjection.Point at = MapProjection.worldOf(geometry, m.x(), m.y());
            double dx = at.x() - target.x();
            double dy = at.y() - target.y();
            found.add(new Candidate(dx * dx + dy * dy, MapShotRenderer.Mark.icon(
                    at.x(), at.y(), nz(m.name()), iconOf.getOrDefault(nz(m.group()), ""))));
        }
        found.sort(Comparator.comparingDouble(Candidate::distance));

        int labelMax = Math.max(0, options.getShot().getLabelMax());
        List<MapShotRenderer.Mark> out = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            MapShotRenderer.Mark m = found.get(i).mark();
            // 名额用完就把名字摘掉 —— 图标留着，那才是地图本来的样子
            out.add(i < labelMax ? m : new MapShotRenderer.Mark(m.x(), m.y(), false, "", m.icon()));
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
