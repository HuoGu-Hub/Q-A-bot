package com.example.qqbot.publicapi;

import com.example.qqbot.kb.MapOptions;
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
 * 公开站地图接口（{@code /api/public/map/**}）。
 *
 * <p><b>三条约束与 {@link PublicController} 一致</b>：只读、只吐公开字段、按 IP 限流。
 * 唯一的例外是图片接口 —— 一次打开就是几十个请求，走限流会让地图直接打不开。
 * 它靠<b>资源白名单</b>兜底：只放行确实属于我们地图数据的文件名，见
 * {@link MapViewService#allowedAsset}。
 *
 * <p><b>为什么单独一个控制器</b>：地图这条线有自己的缓存、图片、坐标语义，
 * 塞进 400 多行的 {@code PublicController} 只会让两边都更难找。
 */
@RestController
@RequestMapping("/api/public/map")
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
    public ResponseEntity<?> catalog(HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("enabled", options.isEnabled());
        out.put("unitsPerMeter", options.getUnitsPerMeter());
        // 缩放上限交给前端：它是"屏幕像素 / 原图像素"的比例，只有前端知道屏幕
        out.put("maxZoom", options.getMaxZoom());
        out.put("categories", MapViewService.categoryLabels());
        out.put("maps", view.catalog());
        return ResponseEntity.ok(out);
    }

    /** 一张地图的全部点位（wiki 点 + 自定义点，已合并为同一形状） */
    @GetMapping("/markers")
    public ResponseEntity<?> markers(@RequestParam("map") String map, HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        if (map == null || map.isBlank()) {
            return badRequest("缺少 map 参数");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("map", map.strip());
        out.put("markers", view.markers(map.strip()));
        return ResponseEntity.ok(out);
    }

    /**
     * 资源图层的目录：94 个图层，按宝箱 / 采矿 / 植物等分区。
     *
     * <p>只回图层定义与点数，不回坐标。点位走 {@link #resources} 按层取 ——
     * 36k 个点一次下发，首屏就白屏了。
     */
    @GetMapping("/resource-groups")
    public ResponseEntity<?> resourceGroups(@RequestParam("map") String map,
                                            HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        if (map == null || map.isBlank()) {
            return badRequest("缺少 map 参数");
        }
        MapResourceService.CatalogView catalog = resources.catalog(map.strip());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("map", map.strip());
        out.put("imported", catalog.imported());
        out.put("version", catalog.version());
        out.put("importedAt", catalog.importedAt());
        out.put("message", catalog.message());
        out.put("categories", catalog.categories());
        return ResponseEntity.ok(out);
    }

    /**
     * 一层资源点。坐标用 {@code [x, z, name, region]} 的紧凑数组，
     * 13,000 个点的层也不会因为重复的字段名膨胀。
     */
    @GetMapping("/resources")
    public ResponseEntity<?> resources(@RequestParam("map") String map,
                                       @RequestParam("layer") String layer,
                                       HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        if (map == null || map.isBlank() || layer == null || layer.isBlank()) {
            return badRequest("缺少 map 或 layer 参数");
        }
        List<Object[]> points = new ArrayList<>();
        for (MapResourceService.PointView p : resources.points(map.strip(), layer.strip())) {
            points.add(new Object[]{p.x(), p.z(), p.name(), p.region()});
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("map", map.strip());
        out.put("layer", layer.strip());
        out.put("points", points);
        return ResponseEntity.ok(out);
    }

    /** 按名字 / 别名 / 备注搜点位 */
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("q") String q,
                                    @RequestParam(name = "limit", defaultValue = "20") int limit,
                                    HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        String term = q == null ? "" : q.strip();
        if (term.isEmpty()) {
            return ResponseEntity.ok(Map.of("ok", true, "q", "", "results", List.of()));
        }
        int want = Math.min(Math.max(limit, 1), MAX_SEARCH);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("q", term);
        out.put("results", view.search(term, want));
        return ResponseEntity.ok(out);
    }

    /**
     * 「哪里有 X」——**算好的相对位置描述**。
     *
     * <p>这是查看器上"搜一下就把你带到那儿"的入口，同时也是机器人回答那条链路的
     * 可观测版本：同一个算法、同一份数据，页面上看到什么，群里就该看到什么。
     */
    @GetMapping("/locate")
    public ResponseEntity<?> locate(@RequestParam("q") String q,
                                    @RequestParam(name = "limit", defaultValue = "5") int limit,
                                    HttpServletRequest request) {
        ResponseEntity<?> denied = rateLimit(request);
        if (denied != null) {
            return denied;
        }
        String term = q == null ? "" : q.strip();
        if (term.isEmpty()) {
            return badRequest("缺少 q 参数");
        }
        double perMeter = options.getUnitsPerMeter();
        int want = Math.min(Math.max(limit, 1), MAX_SEARCH);
        PlaceAnswer.Answer answer = PlaceAnswer.answer(term, directory, want, perMeter);

        List<Map<String, Object>> hits = new ArrayList<>();
        for (PlaceAnswer.Hit h : answer.hits()) {
            PlacePoint p = h.place();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", p.ref());
            m.put("map", p.map());
            m.put("canvas", p.canvas());
            m.put("name", p.name());
            m.put("x", p.x());
            m.put("y", p.y());
            m.put("category", p.category());
            m.put("note", p.note());
            m.put("source", p.source());
            RelativeLocation.Neighbour n = h.neighbour();
            if (n != null) {
                Map<String, Object> rel = new LinkedHashMap<>();
                rel.put("direction", n.direction());
                rel.put("screenHint", n.screenHint());
                rel.put("distance", Math.round(n.distance()));
                rel.put("bucket", n.bucket());
                rel.put("bearing", Math.round(RelativeLocation.bearingDegrees(n.dx(), n.dy())));
                rel.put("phrase", n.phrase(perMeter));
                Map<String, Object> anchor = new LinkedHashMap<>();
                anchor.put("ref", n.anchor().ref());
                anchor.put("name", n.anchor().name());
                anchor.put("x", n.anchor().x());
                anchor.put("y", n.anchor().y());
                anchor.put("category", n.anchor().category());
                rel.put("anchor", anchor);
                m.put("relative", rel);
            }
            hits.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("q", term);
        out.put("found", answer.found());
        out.put("text", answer.text(perMeter));
        out.put("hits", hits);
        return ResponseEntity.ok(out);
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

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private ResponseEntity<?> rateLimit(HttpServletRequest request) {
        if (!limiter.allow(clientIp(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "请求太频繁，请稍后再试"));
        }
        return null;
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
