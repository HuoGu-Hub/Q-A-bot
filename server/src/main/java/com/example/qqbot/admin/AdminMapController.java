package com.example.qqbot.admin;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.map.KbMapStore;
import com.example.qqbot.kb.map.MapImageCache;
import com.example.qqbot.kb.map.MapJobs;
import com.example.qqbot.kb.map.MapLayerService;
import com.example.qqbot.kb.map.MapNoteService;
import com.example.qqbot.kb.map.MapPlaceService;
import com.example.qqbot.kb.map.MapScope;
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
    private final MapLayerService layers;
    private final MapScope scope;

    public AdminMapController(MapViewService view, MapPlaceService places, MapNoteService notes,
                              KbMapStore mapStore, MapImageCache images, MapJobs jobs,
                              MapOptions options, MapLayerService layers, MapScope scope) {
        this.view = view;
        this.places = places;
        this.notes = notes;
        this.mapStore = mapStore;
        this.images = images;
        this.jobs = jobs;
        this.options = options;
        this.layers = layers;
        this.scope = scope;
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

        // 底图变了导致点位可能整体偏移 —— "待复核几条"必须一眼可见，
        // 这是这套机制存在的全部意义（否则它只是个没人看的字段）
        out.put("anchorPending", places.pendingReview().size());
        out.put("placeTotal", places.count());

        out.put("jobs", jobs.states());

        // 只输出界面真正会读的四个字段。它原先还带 page / hasCrs / extent / syncedAt，
        // 那些是给已经删掉的「画布」表用的 —— 没人读的字段留着只会让下一个人以为它在被用。
        // 注意这里**不过滤启用范围**：候选地图表要靠它显示"未启用那张能不能渲染"
        List<Map<String, Object>> canvases = new ArrayList<>();
        for (KbMapStore.Canvas c : mapStore.canvases()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("map", c.map());
            m.put("renderable", c.renderable());
            m.put("background", c.backgroundImage());
            m.put("tiles", view.tilesOf(c.tilesJson()).size());
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
        out.put("maxZoom", options.getMaxZoom());
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
    /* ==================== 默认图层 ==================== */

    /**
     * 每张地图的分组清单 + 当前默认。
     *
     * <p>把"可选的分组"和"当前选了什么"一起给出去，管理端不需要再拉一次目录 ——
     * 两份数据来自同一次 {@code catalog()}，也就不会出现"界面上的分组和实际对不上"。
     */
    @GetMapping("/layers")
    public Map<String, Object> layers() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("available", layers.isAvailable());
        List<Map<String, Object>> maps = new ArrayList<>();
        for (MapViewService.MapView m : view.catalog()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("map", m.map());
            item.put("label", m.label());
            item.put("markerCount", m.markerCount());
            // configured 与"选中了几个"是两件事：前者决定前端要不要用经验值兜底
            item.put("configured", m.defaultGroups() != null);
            List<Map<String, Object>> groups = new ArrayList<>();
            for (MapViewService.GroupView g : m.groups()) {
                Map<String, Object> gi = new LinkedHashMap<>();
                gi.put("key", g.key());
                gi.put("name", g.name());
                gi.put("icon", g.icon());
                gi.put("category", g.category());
                gi.put("count", g.count());
                gi.put("on", m.defaultGroups() != null && m.defaultGroups().contains(g.key()));
                groups.add(gi);
            }
            item.put("groups", groups);
            maps.add(item);
        }
        out.put("maps", maps);
        return out;
    }

    /* ==================== 当前用哪几张地图 ==================== */

    /**
     * 地图候选列表：wiki 上现在有的**每一张**地图 + 点数 + 最后改动时间 + 是否启用。
     *
     * <p>这是"不把判断写死在配置里"的落点：同步把命名空间里的地图全拉进来（谁也不丢），
     * 用哪几张在这里由人决定。游戏更新后 wiki 另起一页，它会自己出现在这份列表里等人决定 ——
     * 而不是像白名单那样被静默跳过、让旧图继续以"最新"的样子留着。
     */
    @GetMapping("/maps")
    public Map<String, Object> maps() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("active", scope.activeMaps());
        out.put("candidates", scope.candidates());
        return out;
    }

    /** 启停请求。{@code active} 缺省视为 true */
    public record ActiveRequest(String map, Boolean active) {
    }

    @PutMapping("/maps/active")
    public ResponseEntity<?> setActive(@RequestBody ActiveRequest body) {
        return handle(() -> {
            if (body == null || body.map() == null || body.map().isBlank()) {
                throw new IllegalArgumentException("缺少地图名");
            }
            boolean on = body.active() == null || body.active();
            if (!scope.setActive(body.map(), on)) {
                throw new IllegalStateException("地图启用状态存不了（问答库没起来）");
            }
            return ResponseEntity.ok(Map.of("ok", true, "active", scope.activeMaps()));
        });
    }

    /** 保存请求。{@code groups} 为 null = 恢复"未配置" */
    public record LayerRequest(String map, List<String> groups) {
    }

    @PutMapping("/layers")
    public ResponseEntity<?> saveLayers(@RequestBody LayerRequest body) {
        if (body == null || body.map() == null || body.map().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "缺少 map"));
        }
        if (!layers.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "error", "数据库不可用，配置无法保存"));
        }
        layers.save(body.map(), body.groups());
        return ResponseEntity.ok(Map.of("ok", true));
    }

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

    /**
     * 底图变过、需要人复核的点位。
     *
     * <p>自定义点位存的是世界坐标，而"世界坐标落在底图的哪个像素上"由画布决定 ——
     * 画布是从 wiki 同步来的。对方换了取景窗口，点位就会整体偏移，
     * 而且**不会有任何报错**。这个接口就是那个"不会有报错"的替代品。
     */
    @GetMapping("/anchor")
    public Map<String, Object> anchor() {
        List<MapPlaceService.Review> pending = places.pendingReview();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", places.count());
        out.put("pending", pending.size());
        out.put("items", pending);
        return out;
    }

    /**
     * "位置我看过了，没错" —— 接受当前底图，把这条点位从待复核里放出来。
     *
     * <p>只更新指纹，不碰坐标。真的偏了的话应该走 {@code PUT /places/{id}} 把坐标改对。
     */
    @PostMapping("/places/{id}/confirm-anchor")
    public ResponseEntity<?> confirmAnchor(@PathVariable long id) {
        return handle(() -> {
            if (!places.confirmAnchor(id)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "没有 id=" + id + " 的点位，或者它的画布现在找不到"));
            }
            return ResponseEntity.ok(Map.of("ok", true));
        });
    }

    /** 批量搬移请求。{@code dx/dy} 由前端算：它手里同时有"原来在哪"和"现在拖到哪" */
    public record OffsetRequest(Long referenceId, Double dx, Double dy) {
    }

    /**
     * 以人工修正过的那个点为基准，把同一张底图上**其余待复核**的点按同样的偏移一起搬。
     *
     * <p>为什么偏移要由前端给：新旧坐标之间的对应关系只有人知道，而人已经用一个点表达过了
     * —— 就是他把它从哪拖到了哪。剩下的点照搬即可，不必逐个再拖一遍。
     */
    @PostMapping("/anchor/apply-offset")
    public ResponseEntity<?> applyOffset(@RequestBody OffsetRequest body) {
        return handle(() -> {
            if (body == null || body.referenceId() == null) {
                throw new IllegalArgumentException("缺少基准点位 id");
            }
            MapPlaceService.OffsetReport r = places.applyOffset(body.referenceId(),
                    body.dx() == null ? 0 : body.dx(), body.dy() == null ? 0 : body.dy());
            if (r == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "没有 id=" + body.referenceId() + " 的点位"));
            }
            return ResponseEntity.ok(Map.of("ok", true, "moved", r.moved(), "skipped", r.skipped()));
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

    /**
     * 全量预热（轻量档 + 原图档）。同样立即返回。
     *
     * <p>和上面那个的区别是**要不要提前把原图拉下来**：原图只在放大到 1 倍以上时用到，
     * 那时视野里只有个位数张、按需取也就几秒。所以它划算与否取决于有没有人在看 ——
     * 首次部署后点一次，或者交给 {@code MapWarmScheduler} 在非高峰自动跑；
     * 高峰期点它等于拿访客的首屏去换一张没人立刻要的图。
     */
    @PostMapping("/warm-full")
    public ResponseEntity<?> warmFull() {
        if (!jobs.startFullWarm()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "预热已经在跑了"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "started", true));
    }

    /**
     * 清空**同步进来**的地图数据 —— "从头再同步一遍"。
     *
     * <p>⚠️ 它**不碰人建的东西**：自定义点位、手写备注、每张图的图层配置、启用范围。
     * 后三张表按 {@code (map, page)} / {@code (map, page, markerId)} 挂，
     * 同步回来的键与之前一致，所以它们会自己重新挂上。
     * 见 {@link com.example.qqbot.persistence.KbMapRepository#clearSynced()}。
     *
     * @param tiles 连瓦片缓存一起删。删了要重新预热，所以默认 true 但要由调用方明说
     */
    @PostMapping("/reset")
    public ResponseEntity<?> reset(@RequestParam(name = "tiles", defaultValue = "true") boolean tiles) {
        if (!jobs.startReset(tiles)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "清空任务已经在跑了"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "started", true, "tiles", tiles));
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
        // canvasFp / anchorOk 由服务层在写入与读出时算，这里只占位
        return new MapPlaceService.Place(id, b.map(), b.page(), num(b.x()), num(b.y()),
                b.name(), b.aliases() == null ? List.of() : b.aliases(), b.category(),
                b.note(), b.icon(), b.color(), bool(b.landmark()), num(b.priority()),
                b.visible() == null || b.visible(), "", true, "", "");
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
