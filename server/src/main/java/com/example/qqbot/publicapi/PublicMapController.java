package com.example.qqbot.publicapi;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.web.ApiException;
import com.example.qqbot.kb.PlaceDirectory;
import com.example.qqbot.kb.PlacePoint;
import com.example.qqbot.kb.PlaceAnswer;
import com.example.qqbot.kb.RelativeLocation;
import com.example.qqbot.kb.map.MapImageCache;
import com.example.qqbot.kb.map.MapImageWorker;
import com.example.qqbot.kb.map.MapResourceService;
import com.example.qqbot.kb.map.MapViewService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 公开站地图接口（{@code /api/public/v1/map/**}）。
 *
 * <p><b>三条约束与 {@link PublicController} 一致</b>：只读、只吐公开字段、按 IP 限流。
 * 唯一的例外是图片接口 —— 一次打开就是几十个请求，走限流会让地图直接打不开。
 * 它靠<b>资源白名单</b>兜底：只放行确实属于我们地图数据的文件名，见
 * {@link MapViewService#allowedAsset}。
 *
 * <p><b>为什么单独一个控制器</b>：地图这条线有自己的缓存、图片、坐标语义，
 * 塞进 400 多行的 {@code PublicController} 只会让两边都更难找。
 */
@Tag(name = "Public")
@RestController
@RequestMapping("/api/public/v1/map")
public class PublicMapController {

    private static final Logger log = LoggerFactory.getLogger(PublicMapController.class);

    /** 公开搜索最多返回几条 */
    private static final int MAX_SEARCH = 50;

    /** 图片缓存时长。瓦片内容按文件名固定，但不带内容版本号，所以用 7 天而不是 immutable */
    private static final CacheControl IMAGE_CACHE =
            CacheControl.maxAge(Duration.ofDays(7)).cachePublic();

    /**
     * 一张图最多让用户等多久。
     *
     * <p>比上游的最坏情况留了余量：串行闸门 + 429 退避下，一张图可能耗二十几秒。
     * 超时给 404，前端下次重新渲染时会再要一次 —— 比让请求永远挂着强。
     */
    private static final long TILE_TIMEOUT_MS = Duration.ofSeconds(90).toMillis();

    private final MapViewService view;
    private final MapImageCache images;
    private final MapOptions options;
    private final PlaceDirectory directory;
    private final PublicRateLimiter limiter;
    private final MapImageWorker imageWorker;
    private final MapResourceService resources;

    public PublicMapController(MapViewService view, MapImageCache images, MapOptions options,
                               PlaceDirectory directory, PublicRateLimiter limiter,
                               MapImageWorker imageWorker, MapResourceService resources) {
        this.view = view;
        this.images = images;
        this.options = options;
        this.directory = directory;
        this.limiter = limiter;
        this.imageWorker = imageWorker;
        this.resources = resources;
    }

    /**
     * 地图目录：有哪几张图、各自的画布与图层。
     *
     * <p>不含点位 —— 点位是最大的一块，单独一个接口拉，页面首屏才能先出骨架。
     */
    @GetMapping("/catalog")
    public ResponseEntity<PublicDtos.MapCatalog> catalog(HttpServletRequest request) {
        rateLimit(request);
        return ResponseEntity.ok(new PublicDtos.MapCatalog(
                options.isEnabled(),
                options.getUnitsPerMeter(),
                // 缩放上限交给前端：它是"屏幕像素 / 原图像素"的比例，只有前端知道屏幕
                options.getMaxZoom(),
                MapViewService.categoryLabels(),
                view.catalog()));
    }

    /** 一张地图的全部点位（wiki 点 + 自定义点，已合并为同一形状） */
    @GetMapping("/markers")
    public ResponseEntity<PublicDtos.MapMarkers> markers(@RequestParam("map") String map,
                                                          HttpServletRequest request) {
        rateLimit(request);
        if (map == null || map.isBlank()) {
            throw ApiException.badRequest("缺少 map 参数");
        }
        return ResponseEntity.ok(new PublicDtos.MapMarkers(map.strip(), view.markers(map.strip())));
    }

    /**
     * 资源图层的目录：248 个图层，按宝箱 / 采矿 / 植物 / 生物等分区。
     *
     * <p>只回图层定义与点数，不回坐标。点位走 {@link #resources} 按层取 ——
     * 64k 个点一次下发，首屏就白屏了。
     */
    @GetMapping("/resource-groups")
    public ResponseEntity<PublicDtos.ResourceCatalog> resourceGroups(
            @RequestParam("map") String map, HttpServletRequest request) {
        rateLimit(request);
        if (map == null || map.isBlank()) {
            throw ApiException.badRequest("缺少 map 参数");
        }
        MapResourceService.CatalogView catalog = resources.catalog(map.strip());
        return ResponseEntity.ok(new PublicDtos.ResourceCatalog(
                map.strip(), catalog.imported(), catalog.version(), catalog.importedAt(),
                catalog.message(), catalog.categories()));
    }

    /**
     * 一层资源点。坐标用 {@code [x, z, name, region]} 的紧凑数组，
     * 13,000 个点的层也不会因为重复的字段名膨胀。
     */
    @GetMapping("/resources")
    public ResponseEntity<PublicDtos.ResourcePoints> resources(
            @RequestParam("map") String map,
            @RequestParam("layer") String layer,
            HttpServletRequest request) {
        rateLimit(request);
        if (map == null || map.isBlank() || layer == null || layer.isBlank()) {
            throw ApiException.badRequest("缺少 map 或 layer 参数");
        }
        List<PublicDtos.ResourcePoint> points = new ArrayList<>();
        for (MapResourceService.PointView p : resources.points(map.strip(), layer.strip())) {
            points.add(new PublicDtos.ResourcePoint(
                    p.x(), p.z(), p.name(), p.region(), p.regionName()));
        }
        return ResponseEntity.ok(new PublicDtos.ResourcePoints(
                map.strip(), layer.strip(), points));
    }

    /**
     * 一层面数据（林地）。坐标是矩形的西南角与东北角，前端直接画成矩形。
     */
    @GetMapping("/resource-areas")
    public ResponseEntity<PublicDtos.ResourceAreas> resourceAreas(
            @RequestParam("map") String map,
            @RequestParam("layer") String layer,
            HttpServletRequest request) {
        rateLimit(request);
        if (map == null || map.isBlank() || layer == null || layer.isBlank()) {
            throw ApiException.badRequest("缺少 map 或 layer 参数");
        }
        List<PublicDtos.ResourceArea> areas = new ArrayList<>();
        for (MapResourceService.AreaView a : resources.areas(map.strip(), layer.strip())) {
            areas.add(new PublicDtos.ResourceArea(a.x0(), a.z0(), a.x1(), a.z1(), a.name()));
        }
        return ResponseEntity.ok(new PublicDtos.ResourceAreas(
                map.strip(), layer.strip(), areas));
    }

    /**
     * 资源图层的小图标。只认 {@code map-icons} 下的小写文件名，不接受路径，
     * 所以这个接口不需要再走一层资源白名单。
     */
    @GetMapping("/resource-icon")
    public ResponseEntity<byte[]> resourceIcon(@RequestParam("name") String name) {
        if (name == null || !name.matches("[a-z0-9-]+\\.png")) {
            return ResponseEntity.badRequest().build();
        }
        ClassPathResource resource = new ClassPathResource("map-icons/" + name);
        if (!resource.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try (var in = resource.getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                    .body(in.readAllBytes());
        } catch (Exception e) {
            log.warn("[MAP-RES] 读图标失败 {}：{}", name, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** 按名字 / 别名 / 备注搜点位 */
    @GetMapping("/search")
    public ResponseEntity<PublicDtos.MapSearch> search(
            @RequestParam("q") String q,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            HttpServletRequest request) {
        rateLimit(request);
        String term = q == null ? "" : q.strip();
        if (term.isEmpty()) {
            return ResponseEntity.ok(new PublicDtos.MapSearch("", List.of()));
        }
        int want = Math.min(Math.max(limit, 1), MAX_SEARCH);
        return ResponseEntity.ok(new PublicDtos.MapSearch(term, view.search(term, want)));
    }

    /**
     * 「哪里有 X」——**算好的相对位置描述**。
     *
     * <p>这是查看器上"搜一下就把你带到那儿"的入口，同时也是机器人回答那条链路的
     * 可观测版本：同一个算法、同一份数据，页面上看到什么，群里就该看到什么。
     */
    @GetMapping("/locate")
    public ResponseEntity<PublicDtos.LocateResult> locate(
            @RequestParam("q") String q,
            @RequestParam(name = "limit", defaultValue = "5") int limit,
            HttpServletRequest request) {
        rateLimit(request);
        String term = q == null ? "" : q.strip();
        if (term.isEmpty()) {
            throw ApiException.badRequest("缺少 q 参数");
        }
        double perMeter = options.getUnitsPerMeter();
        int want = Math.min(Math.max(limit, 1), MAX_SEARCH);
        PlaceAnswer.Answer answer = PlaceAnswer.answer(term, directory, want, perMeter);

        List<PublicDtos.LocateHit> hits = new ArrayList<>();
        for (PlaceAnswer.Hit h : answer.hits()) {
            PlacePoint p = h.place();
            RelativeLocation.Neighbour n = h.neighbour();
            PublicDtos.LocateRelative rel = null;
            if (n != null) {
                rel = new PublicDtos.LocateRelative(
                        n.direction(),
                        n.screenHint(),
                        Math.round(n.distance()),
                        n.bucket(),
                        Math.round(RelativeLocation.bearingDegrees(n.dx(), n.dy())),
                        n.phrase(perMeter),
                        new PublicDtos.LocateAnchor(
                                n.anchor().ref(), n.anchor().name(), n.anchor().x(),
                                n.anchor().y(), n.anchor().category()));
            }
            hits.add(new PublicDtos.LocateHit(
                    p.ref(), p.map(), p.canvas(), p.name(), p.x(), p.y(),
                    p.category(), p.note(), p.source(), rel));
        }
        return ResponseEntity.ok(new PublicDtos.LocateResult(
                term, answer.found(), answer.text(perMeter), hits));
    }

    /**
     * 图片字节（底图瓦片 / 底图整图 / 分组图标）。
     *
     * <p>{@code w} 是目标宽度：不传就是原图。可用宽度由
     * {@link MapOptions#getWidths()} 白名单限定，白名单外的值静默退回原图 ——
     * 这个参数只影响清晰度，为它报 400 不划算（与轮播图同一套约定）。
     *
     * <h2>⚠️ 为什么返回 {@link DeferredResult} 而不是直接返回字节</h2>
     * 取图是**同步等上游**的（串行节流 + 重试），单张最坏二十几秒。同步返回就等于
     * 一个瓦片占住一个 Tomcat 线程，而群消息的 {@code /onebot/event} 走的是**同一个**
     * 线程池 —— 几十个瓦片就能把机器人拖到"@ 不活，连 /ping 都没反应"（2026-10-14 实测）。
     * 所以这里立刻把 Tomcat 线程交还，真正取图的活交给 {@link MapImageWorker}。
     */
    @GetMapping("/tile")
    public DeferredResult<ResponseEntity<byte[]>> tile(@RequestParam("map") String map,
                                                       @RequestParam(name = "page", required = false) String page,
                                                       @RequestParam("name") String name,
                                                       @RequestParam(name = "w", required = false) String w) {
        if (!options.isEnabled()) {
            return immediate(ResponseEntity.notFound().build());
        }
        if (map == null || name == null || !view.allowedAsset(map, page, name)) {
            log.debug("[MAP-IMG] 拒绝不在白名单里的资源：map={} page={} name={}", map, page, name);
            return immediate(ResponseEntity.notFound().build());
        }

        int width = parseInt(w);
        // 超时兜底：worker 永远不回来的话，这里至少给用户一个明确的 404，
        // 而不是让连接挂到容器超时
        DeferredResult<ResponseEntity<byte[]>> pending = new DeferredResult<>(TILE_TIMEOUT_MS,
                ResponseEntity.notFound().build());
        boolean accepted = imageWorker.submit(() -> pending.setResult(loadTile(map, page, name, width)));
        if (!accepted) {
            // 上游是串行的，排下去只会让用户等几分钟才看到一张破图；
            // 明确回 503 + Retry-After，前端重新渲染时会再要一次
            log.warn("[MAP-IMG] 取图队列已满（在跑 {} / 排队 {}），快速失败：{}",
                    imageWorker.inFlight(), imageWorker.queued(), name);
            pending.setResult(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "2")
                    .build());
        }
        return pending;
    }

    /**
     * 真正取图。
     *
     * <p>这一段跑在 {@link MapImageWorker} 的线程上，**不是** Tomcat 线程 ——
     * 这正是"机器人 @ 不活"那个问题的修法：慢的是取图，不是网络入口。
     */
    private ResponseEntity<byte[]> loadTile(String map, String page, String name, int width) {
        Optional<MapImageCache.Image> got = images.get(map, page, name, width);
        if (got.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        MapImageCache.Image img = got.get();
        return ResponseEntity.ok()
                .cacheControl(IMAGE_CACHE)
                .contentType(MediaType.parseMediaType(img.mime()))
                .body(img.bytes());
    }

    /** 不需要异步派发的结果（功能关闭、白名单没过）直接给出去，不占取图线程 */
    private static DeferredResult<ResponseEntity<byte[]>> immediate(ResponseEntity<byte[]> response) {
        DeferredResult<ResponseEntity<byte[]>> done = new DeferredResult<>();
        done.setResult(response);
        return done;
    }

    /* ==================== 兜底 ==================== */

    private static int parseInt(String w) {
        if (w == null || w.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(w.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void rateLimit(HttpServletRequest request) {
        String ip = clientIp(request);
        if (!limiter.allow(ip)) {
            throw ApiException.tooManyRequests("请求太频繁，请稍后再试",
                    limiter.retryAfterSeconds(ip));
        }
    }

    /** 取真实客户端 IP —— 与 {@link PublicController} 读同一个头，两处必须一致 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
