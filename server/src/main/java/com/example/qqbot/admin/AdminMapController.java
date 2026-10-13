package com.example.qqbot.admin;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.map.KbMapStore;
import com.example.qqbot.kb.map.MapImageCache;
import com.example.qqbot.kb.map.MapJobs;
import com.example.qqbot.kb.map.MapNoteService;
import com.example.qqbot.kb.map.MapPlaceService;
import com.example.qqbot.kb.map.MapViewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端地图接口（{@code /admin/api/map/**}）—— "在地图上点一下就能标地点"的后端。
 *
 * <p><b>鉴权不在这里</b>：{@link AdminAuthFilter} 在更前面拦下整个 {@code /admin/**}，
 * 没配密码就整体关闭、没会话就 401。本类只负责业务 —— 再写一遍鉴权只会多一处可能写错的地方。
 *
 * <p><b>写操作全部走服务层校验</b>：控制器只把"HTTP 的形状"翻译成服务层的类型，
 * 不重复实现"名字不能为空"这类规则（见 {@link MapPlaceService} 的注释）。
 */
@RestController
@RequestMapping("/admin/api/map")
public class AdminMapController {

    private static final Logger log = LoggerFactory.getLogger(AdminMapController.class);

    private final MapViewService view;
    private final MapPlaceService places;
    private final MapNoteService notes;
    private final KbMapStore mapStore;
    private final MapImageCache images;
    private final MapJobs jobs;
    private final MapOptions options;

    public AdminMapController(MapViewService view, MapPlaceService places, MapNoteService notes,
                              KbMapStore mapStore, MapImageCache images, MapJobs jobs,
                              MapOptions options) {
        this.view = view;
        this.places = places;
        this.notes = notes;
        this.mapStore = mapStore;
        this.images = images;
        this.jobs = jobs;
        this.options = options;
    }

    /* ==================== 状态 ==================== */

    /** 一眼看尽状态：库里有几张画布、点位从哪来、缓存多大、长任务跑到哪了 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("enabled", options.isEnabled());
        out.put("unitsPerMeter", options.getUnitsPerMeter());
        out.put("directory", view.describe());
        out.put("mapStoreAvailable", mapStore.isAvailable());
        out.put("placesAvailable", places.isAvailable());
        out.put("notesAvailable", notes.isAvailable());

        MapImageCache.Stats st = images.stats();
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("enabled", st.enabled());
        cache.put("root", st.root());
        cache.put("files", st.files());
        cache.put("bytes", st.bytes());
        out.put("tileCache", cache);

        out.put("jobs", jobs.states());

        List<Map<String, Object>> canvases = new ArrayList<>();
        for (KbMapStore.Canvas c : mapStore.canvases()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("map", c.map());
            m.put("page", c.page());
            m.put("hasCrs", c.hasCrs());
            m.put("renderable", c.renderable());
            m.put("background", c.backgroundImage());
            m.put("tiles", view.tilesOf(c.tilesJson()).size());
            m.put("extent", c.extent());
            m.put("syncedAt", c.syncedAt());
            canvases.add(m);
        }
        out.put("canvases", canvases);

        List<Map<String, Object>> pages = new ArrayList<>();
        for (KbMapStore.PageState p : mapStore.pages()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("page", p.page());
            m.put("map", p.map());
            m.put("revid", p.revid());
            m.put("markerCount", p.markerCount());
            m.put("syncedAt", p.syncedAt());
            m.put("error", p.parseError());
            pages.add(m);
        }
        out.put("pages", pages);
        out.put("categories", MapViewService.categoryLabels());
        return out;
    }

    /**
     * 地图目录 —— 与公开端同一份数据。
     *
     * <p>为什么管理端不直接调公开接口：公开接口按 IP 限流，而管理端一次打开就要
     * 目录 + 点位两份，反复调试时很容易撞到限流，看到的却是"地图坏了"。
     * 鉴权已经由 {@link AdminAuthFilter} 拦在前面，这里复用视图层即可。
     */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("enabled", options.isEnabled());
        out.put("unitsPerMeter", options.getUnitsPerMeter());
        out.put("categories", MapViewService.categoryLabels());
        out.put("maps", view.catalog());
        return out;
    }

    /** 一张地图的**全部**点位（含隐藏的），标注页要能看到自己藏了什么 */
    @GetMapping("/markers")
    public ResponseEntity<?> markers(@RequestParam("map") String map) {
        if (map == null || map.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 map 参数"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("map", map.strip());
        out.put("markers", view.markers(map.strip(), false));
        return ResponseEntity.ok(out);
    }

    /** 可供点位选用的图标（去重）—— 复用 wiki 已有的那套，不额外做图片上传 */
    @GetMapping("/icons")
    public Map<String, Object> icons() {
        List<String> icons = new ArrayList<>(view.allIcons());
        icons.sort(String::compareTo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("icons", icons);
        return out;
    }

    /* ==================== 自定义点位 ==================== */

    @GetMapping("/places")
    public Map<String, Object> listPlaces(@RequestParam(name = "map", required = false) String map,
                                          @RequestParam(name = "visibleOnly", defaultValue = "false")
                                          boolean visibleOnly) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("places", places.list(map, visibleOnly));
        out.put("categories", MapViewService.categoryLabels());
        return out;
    }

    /** 新增点位的请求体。字段全部用包装类型：缺省与"显式给了 0"必须能区分 */
    public record PlaceRequest(String map, String page, Double x, Double y, String name,
                               List<String> aliases, String category, String note, String icon,
                               String color, Boolean landmark, Integer priority, Boolean visible) {
    }

    @PostMapping("/places")
    public ResponseEntity<?> createPlace(@RequestBody PlaceRequest body) {
        return handle(() -> {
            MapPlaceService.Place created = places.create(toPlace(body, 0));
            return ResponseEntity.ok(Map.of("ok", true, "place", created));
        });
    }

    @PutMapping("/places/{id}")
    public ResponseEntity<?> updatePlace(@PathVariable long id, @RequestBody PlaceRequest body) {
        return handle(() -> {
            MapPlaceService.Place updated = places.update(toPlace(body, id));
            if (updated == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "没有 id=" + id + " 的点位"));
            }
            return ResponseEntity.ok(Map.of("ok", true, "place", updated));
        });
    }

    @DeleteMapping("/places/{id}")
    public ResponseEntity<?> deletePlace(@PathVariable long id) {
        return handle(() -> {
            if (!places.delete(id)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "没有 id=" + id + " 的点位"));
            }
            return ResponseEntity.ok(Map.of("ok", true));
        });
    }

    /* ==================== wiki 点位备注 ==================== */

    @GetMapping("/notes")
    public Map<String, Object> listNotes(@RequestParam(name = "map", required = false) String map) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("notes", map == null || map.isBlank() ? notes.all() : new ArrayList<>(notes.byMap(map).values()));
        return out;
    }

    public record NoteRequest(String map, String page, String markerId, String alias, String note,
                              String category, Boolean landmark, Integer priority, Boolean hidden) {
    }

    @PostMapping("/notes")
    public ResponseEntity<?> upsertNote(@RequestBody NoteRequest body) {
        return handle(() -> {
            if (body == null) {
                throw new IllegalArgumentException("缺少内容");
            }
            MapNoteService.Note saved = notes.upsert(new MapNoteService.Note(
                    body.map(), body.page(), body.markerId(), body.alias(), body.note(),
                    body.category(), bool(body.landmark()), num(body.priority()), bool(body.hidden()),
                    ""));
            return ResponseEntity.ok(Map.of("ok", true, "note", saved));
        });
    }

    @DeleteMapping("/notes")
    public ResponseEntity<?> deleteNote(@RequestParam("map") String map,
                                        @RequestParam(name = "page", required = false) String page,
                                        @RequestParam("markerId") String markerId) {
        return handle(() -> {
            if (!notes.delete(map, page, markerId)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "没有这条备注"));
            }
            return ResponseEntity.ok(Map.of("ok", true));
        });
    }

    /* ==================== 长任务 ==================== */

    /** 触发 wiki 地图同步。**立即返回**，进度看 /status 的 jobs */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(@RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        if (!jobs.startSync(dryRun)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "同步已经在跑了"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "started", true));
    }

    /**
     * 预热底图缓存。同样立即返回。
     *
     * <p>出站请求是串行节流的，拉满一张图要二十来秒 —— 这件事该由管理员点一次，
     * 而不是让第一个打开地图的群友在空白里等。
     */
    @PostMapping("/warm")
    public ResponseEntity<?> warm() {
        if (!jobs.startWarm()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "预热已经在跑了"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "started", true));
    }

    /** 派生或清除地点语料。同样立即返回 */
    @PostMapping("/corpus")
    public ResponseEntity<?> corpus(@RequestParam(name = "purge", defaultValue = "false") boolean purge,
                                    @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        if (!jobs.startCorpus(purge, dryRun)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "语料任务已经在跑了"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "started", true));
    }

    /* ==================== 转换与错误 ==================== */

    private static MapPlaceService.Place toPlace(PlaceRequest b, long id) {
        if (b == null) {
            throw new IllegalArgumentException("缺少内容");
        }
        return new MapPlaceService.Place(id, b.map(), b.page(), num(b.x()), num(b.y()),
                b.name(), b.aliases() == null ? List.of() : b.aliases(), b.category(),
                b.note(), b.icon(), b.color(), bool(b.landmark()), num(b.priority()),
                b.visible() == null || b.visible(), "", "");
    }

    private static boolean bool(Boolean b) {
        return b != null && b;
    }

    private static int num(Integer n) {
        return n == null ? 0 : n;
    }

    private static double num(Double d) {
        return d == null ? 0 : d;
    }

    /**
     * 统一的错误翻译。
     *
     * <p>三种错误对应三种 HTTP：参数问题 400、存储没起来 503、其余 500。
     * 分清它们是因为**排查方向完全不同** —— 400 是调用方写错了，
     * 503 是问答库没起来，500 才是真的代码出问题。
     */
    private ResponseEntity<?> handle(java.util.concurrent.Callable<ResponseEntity<?>> body) {
        try {
            return body.call();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[MAP-ADMIN] 操作失败：{}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "操作失败：" + e.getMessage()));
        }
    }
}
