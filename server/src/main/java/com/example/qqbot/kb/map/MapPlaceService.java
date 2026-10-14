package com.example.qqbot.kb.map;

import com.example.qqbot.kb.PlaceCategory;
import com.example.qqbot.kb.PlacePoint;
import com.example.qqbot.persistence.KbMapPlaceRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理员自定义点位的**读写服务** —— 地图页上"点一下就能标地点"的那一半。
 *
 * <h2>为什么校验放在这里而不是控制器</h2>
 * 控制器只该管"HTTP 长什么样"。而"名字不能为空、坐标必须是有限数、
 * 别名不能带逗号"这些规则<b>和协议无关</b> —— 后台按钮、未来的批量导入脚本、
 * 测试用例都会写同一批数据，规则写在服务里才只有一处。
 *
 * <h2>为什么别名用逗号串而不是列表存库</h2>
 * 见 {@link KbMapPlaceRepository.PlaceRow#aliases()}。这里负责两边转换，
 * 并且**在入口把逗号从单个别名里剔掉** —— 否则 {@code "铁矿,富矿"} 这样一个别名
 * 读回来会变成两个，写进去什么就读不出来什么。
 */
@Service
public class MapPlaceService {

    private static final Logger log = LoggerFactory.getLogger(MapPlaceService.class);

    /** 字段上限。宁可截断也不要因为一个超长备注把整行写坏 */
    private static final int MAX_NAME = 80;
    private static final int MAX_ALIAS = 60;
    private static final int MAX_ALIASES = 12;
    private static final int MAX_NOTE = 500;
    private static final int MAX_ICON = 120;
    private static final int MAX_COLOR = 24;

    /** 一次批量搬移允许的最大偏移（坐标单位）。超过它基本可以断定是参数传错了 */
    private static final double MAX_OFFSET = 200_000;

    /**
     * 一个自定义点位（落库/读出一致）。
     *
     * <p>{@code id <= 0} 表示"还没入库" —— 新增时用这个约定，不需要第二个类型。
     *
     * <p>{@code canvasFp} 是标记它时**底图的几何指纹**，{@code anchorOk} 是"这份指纹
     * 与当前底图是否一致"的即时判断（不落库，每次读的时候算）。两者见
     * {@link CanvasFingerprint} 与 {@link #pendingReview()}。
     */
    public record Place(long id, String map, String page, double x, double y, String name,
                        List<String> aliases, String category, String note, String icon,
                        String color, boolean landmark, int priority, boolean visible,
                        String canvasFp, boolean anchorOk,
                        String createdAt, String updatedAt) {

        /** 转成核心认识的地点 —— 检索与相对位置描述用的是这个 */
        public PlacePoint toPoint() {
            return new PlacePoint(map, page, name, aliases, x, y, category, note,
                    landmark, priority, "admin", "place:" + id);
        }
    }

    /**
     * 一条"底图变了，需要人复核"的点位。
     *
     * @param reason 人话说明变了哪一块，直接显示给管理员
     */
    public record Review(long id, String map, String page, String name, double x, double y,
                         String storedFp, String currentFp, String reason) {
    }

    /**
     * 批量搬移的结果。
     *
     * @param moved   跟着基准点一起挪了的点数
     * @param skipped 没动的：基准点自己、别的画布上的点、以及**本来就没问题的点**
     */
    public record OffsetReport(int moved, int skipped) {
    }

    private final KbMapPlaceRepository repo;
    private final KbMapStore mapStore;

    private volatile boolean available;

    public MapPlaceService(KbMapPlaceRepository repo, KbMapStore mapStore) {
        this.repo = repo;
        this.mapStore = mapStore;
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[MAP-PLACE] 问答库不可用，自定义点位一并关闭");
            available = false;
            return;
        }
        try {
            repo.initSchema();
            available = true;
            long n = repo.count();
            if (n > 0) {
                log.info("[MAP-PLACE] 自定义点位就绪：{} 条（其中公开 {} 条）", n, repo.countVisible());
            }
        } catch (Exception e) {
            log.warn("[MAP-PLACE] 初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public List<Place> list(String map, boolean onlyVisible) {
        if (!available) {
            return List.of();
        }
        try {
            Fingerprints idx = currentFingerprints();
            return repo.byMap(map, onlyVisible).stream().map(r -> fromRow(r, idx)).toList();
        } catch (Exception e) {
            log.warn("[MAP-PLACE] 读取失败：{}", e.getMessage());
            return List.of();
        }
    }

    public Place get(long id) {
        if (!available) {
            return null;
        }
        try {
            KbMapPlaceRepository.PlaceRow row = repo.byId(id);
            return row == null ? null : fromRow(row, currentFingerprints());
        } catch (Exception e) {
            log.warn("[MAP-PLACE] 读取 {} 失败：{}", id, e.getMessage());
            return null;
        }
    }

    public List<Place> search(String term, boolean onlyVisible, int limit) {
        if (!available || term == null || term.isBlank()) {
            return List.of();
        }
        try {
            Fingerprints idx = currentFingerprints();
            return repo.search(term.strip(), onlyVisible, limit).stream()
                    .map(r -> fromRow(r, idx)).toList();
        } catch (Exception e) {
            log.warn("[MAP-PLACE] 搜索失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 底图变过的点位 —— 标它的时候底图是那样，现在不是了。
     *
     * <p>这是 {@link CanvasFingerprint} 的全部意义所在：它**不能**把点位挪回正确位置，
     * 但能让"这个点现在指错地方了"这件事出现在管理端，而不是安静地错下去。
     */
    public List<Review> pendingReview() {
        if (!available) {
            return List.of();
        }
        try {
            Fingerprints idx = currentFingerprints();
            if (!idx.usable()) {
                // 画布一张都读不到（地图存储没起来）—— 这时候判"全部待复核"只会制造恐慌
                return List.of();
            }
            List<Review> out = new ArrayList<>();
            for (KbMapPlaceRepository.PlaceRow r : repo.byMap(null, false)) {
                String current = idx.forPlace(r.map(), r.page());
                if (anchorOk(r.canvasFp(), current)) {
                    continue;
                }
                out.add(new Review(r.id(), r.map(), r.page(), r.name(), r.x(), r.y(),
                        r.canvasFp(), current, CanvasFingerprint.describe(r.canvasFp(), current)));
            }
            return out;
        } catch (Exception e) {
            log.warn("[MAP-PLACE] 复核清单读取失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * "位置我看过了，没错" —— 把底图指纹更新成当前这份。
     *
     * <p>只动指纹、不动坐标：确认动作不该有能力挪动点位。
     *
     * @return false 表示这条点位不存在，或者它的画布现在根本找不到（那就不是"确认无误"能解决的）
     */
    public boolean confirmAnchor(long id) {
        if (!available) {
            return false;
        }
        try {
            KbMapPlaceRepository.PlaceRow row = repo.byId(id);
            if (row == null) {
                return false;
            }
            String current = currentFingerprints().forPlace(row.map(), row.page());
            if (current.isEmpty()) {
                return false;
            }
            return repo.updateFingerprint(id, current);
        } catch (Exception e) {
            log.warn("[MAP-PLACE] 确认底图 {} 失败：{}", id, e.getMessage());
            return false;
        }
    }

    /**
     * 以**人工修正过的那个点**为基准，把同一张底图上其余待复核的点按同样的偏移搬过去。
     *
     * <h2>为什么是"人工给偏移"而不是自动算</h2>
     * 底图换了坐标系之后，新旧坐标之间那份对应关系<b>只有人知道</b> ——
     * 理论算不出来（对方什么时候换图、按什么范围重导，我们收不到任何通知）。
     * 但人只要把一个点拖对，那份对应关系就**已经用行动表达出来**了：
     * 就是它挪了多远。剩下的点照搬这个偏移即可。
     *
     * <p>只处理**待复核**的点：本来就没问题的点动它等于把它弄坏；
     * 已经人工修过的点因为保存时刷新了指纹，也已经不在待复核里了。
     *
     * @param referenceId 基准点，必须已经人工修正并保存过
     * @return {@code null} 表示基准点不存在（调用方翻成 404）
     */
    public OffsetReport applyOffset(long referenceId, double dx, double dy) {
        if (!available) {
            throw new IllegalStateException("地图点位存储不可用（问答库没起来）");
        }
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
            throw new IllegalArgumentException("偏移量必须是有限数字");
        }
        // 一个偏移大到这个量级，只可能是参数传错了 —— 与其把全图点位搬飞，不如拒绝
        if (Math.abs(dx) > MAX_OFFSET || Math.abs(dy) > MAX_OFFSET) {
            throw new IllegalArgumentException("偏移量超出合理范围（上限 " + (long) MAX_OFFSET + "）");
        }
        KbMapPlaceRepository.PlaceRow ref = repo.byId(referenceId);
        if (ref == null) {
            return null;
        }
        Fingerprints idx = currentFingerprints();
        String refCanvas = idx.canvasOf(ref.map(), ref.page());

        int moved = 0;
        int skipped = 0;
        for (KbMapPlaceRepository.PlaceRow p : repo.byMap(ref.map(), false)) {
            String current = idx.forPlace(p.map(), p.page());
            boolean sameCanvas = idx.canvasOf(p.map(), p.page()).equals(refCanvas);
            if (p.id() == referenceId || !sameCanvas || anchorOk(p.canvasFp(), current)) {
                skipped++;
                continue;
            }
            boolean ok = repo.update(new KbMapPlaceRepository.PlaceRow(p.id(), p.map(), p.page(),
                    p.x() + dx, p.y() + dy, p.name(), p.aliases(), p.category(), p.note(),
                    p.icon(), p.color(), p.landmark(), p.priority(), p.visible(),
                    current, p.createdAt(), KbMapStore.now()));
            if (ok) {
                moved++;
            } else {
                skipped++;
            }
        }
        log.info("[MAP-PLACE] 按基准点 {} 的偏移 ({}, {}) 搬移 {} 个点位，跳过 {} 个",
                referenceId, dx, dy, moved, skipped);
        return new OffsetReport(moved, skipped);
    }

    /**
     * 点位记下的指纹与当前底图是否一致。
     *
     * <p>当前指纹为空 = 画布找不到（页面被改名或删除）→ 一律算不一致，需要人看一眼。
     */
    private static boolean anchorOk(String stored, String current) {
        return !current.isEmpty() && current.equals(trim(stored));
    }

    /**
     * 当前所有画布的指纹索引。
     *
     * <p>一次算好而不是每个点位扫一遍画布表：管理端一次列全表就有几百个点。
     */
    private Fingerprints currentFingerprints() {
        List<KbMapStore.Canvas> canvases = mapStore.canvases();
        Map<String, String> fpOfPage = new LinkedHashMap<>();
        for (KbMapStore.Canvas c : canvases) {
            fpOfPage.put(trim(c.page()), CanvasFingerprint.of(c));
        }
        return new Fingerprints(WikiPlaceDirectory.canvasIndex(canvases),
                WikiPlaceDirectory.primaryCanvasOf(canvases), fpOfPage);
    }

    /** 指纹索引三件套 —— 只为了把"算一次"表达清楚 */
    private record Fingerprints(Map<String, String> canvasOfPage, Map<String, String> primaryOfMap,
                                Map<String, String> fpOfPage) {

        /** 画布表读不到东西时不做任何判断（否则会一次性报出满屏"待复核"） */
        boolean usable() {
            return !fpOfPage.isEmpty();
        }

        /**
         * 一个点位真正落在哪张画布上、那张画布现在的指纹。
         *
         * <p>⚠️ 解析规则必须与渲染端（{@code MapViewService}）**逐字一致**：
         * 规则一旦不同，这里比的就是另一张画布，那比不检查更糟。
         */
        String forPlace(String map, String page) {
            return fpOfPage.getOrDefault(canvasOf(map, page), "");
        }

        /** 这个点位落在哪张画布上 —— 与渲染端同一条规则 */
        String canvasOf(String map, String page) {
            return canvasOfPage.getOrDefault(trim(page),
                    primaryOfMap.getOrDefault(trim(map), ""));
        }
    }

    /** 新增。校验不过抛 {@link IllegalArgumentException}（控制器翻成 400） */
    public Place create(Place input) {
        requireAvailable();
        Place clean = validate(input, true);
        String now = KbMapStore.now();
        // 记下"标它的时候底图长什么样" —— 以后画布变了，这个点会被列进待复核
        String fp = currentFingerprints().forPlace(clean.map(), clean.page());
        long id = repo.insert(new KbMapPlaceRepository.PlaceRow(0, clean.map(), clean.page(),
                clean.x(), clean.y(), clean.name(), joinAliases(clean.aliases()), clean.category(),
                clean.note(), clean.icon(), clean.color(), clean.landmark(), clean.priority(),
                clean.visible(), fp, now, now));
        if (id <= 0) {
            throw new IllegalStateException("写入自定义点位失败");
        }
        return get(id);
    }

    /** 修改。id 不存在时返回 {@code null}（控制器翻成 404） */
    public Place update(Place input) {
        requireAvailable();
        if (input.id() <= 0) {
            throw new IllegalArgumentException("缺少点位 id");
        }
        if (get(input.id()) == null) {
            return null;
        }
        Place clean = validate(input, false);
        // 人在界面上刚确认过位置，所以顺手把指纹刷成当前这份 ——
        // 否则"改了坐标"和"底图变了"两件事会一直同时挂着这个点
        String fp = currentFingerprints().forPlace(clean.map(), clean.page());
        boolean ok = repo.update(new KbMapPlaceRepository.PlaceRow(clean.id(), clean.map(),
                clean.page(), clean.x(), clean.y(), clean.name(), joinAliases(clean.aliases()),
                clean.category(), clean.note(), clean.icon(), clean.color(), clean.landmark(),
                clean.priority(), clean.visible(), fp, "", KbMapStore.now()));
        return ok ? get(clean.id()) : null;
    }

    public boolean delete(long id) {
        if (!available) {
            return false;
        }
        try {
            return repo.delete(id);
        } catch (Exception e) {
            log.warn("[MAP-PLACE] 删除 {} 失败：{}", id, e.getMessage());
            return false;
        }
    }

    public long count() {
        return available ? repo.count() : 0;
    }

    public long countVisible() {
        return available ? repo.countVisible() : 0;
    }

    /* ==================== 转换与校验 ==================== */

    private void requireAvailable() {
        if (!available) {
            throw new IllegalStateException("地图点位存储不可用（问答库没起来）");
        }
    }

    /**
     * 校验并归一化。
     *
     * @param isNew 新增时 page 允许留空（表示"该地图的主画布"）；
     *              修改时必须带上原值，否则会把点位从它的画布上悄悄挪走
     */
    private static Place validate(Place p, boolean isNew) {
        if (p == null) {
            throw new IllegalArgumentException("缺少点位内容");
        }
        String map = trim(p.map());
        if (map.isEmpty()) {
            throw new IllegalArgumentException("必须指定地图");
        }
        String name = trim(p.name());
        if (name.isEmpty()) {
            throw new IllegalArgumentException("地点名不能为空");
        }
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME);
        }
        if (!Double.isFinite(p.x()) || !Double.isFinite(p.y())) {
            throw new IllegalArgumentException("坐标必须是有限数字");
        }
        List<String> aliases = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (p.aliases() != null) {
            for (String a : p.aliases()) {
                String clean = trim(a).replace(',', ' ').replace('，', ' ').strip();
                if (clean.isEmpty() || clean.equals(name) || !seen.add(clean)) {
                    continue;
                }
                aliases.add(clean.length() > MAX_ALIAS ? clean.substring(0, MAX_ALIAS) : clean);
                if (aliases.size() >= MAX_ALIASES) {
                    break;
                }
            }
        }
        String note = trim(p.note());
        if (note.length() > MAX_NOTE) {
            note = note.substring(0, MAX_NOTE);
        }
        String page = trim(p.page());
        if (!isNew && page.isEmpty()) {
            throw new IllegalArgumentException("修改时必须带上原来的画布（page）");
        }
        return new Place(p.id(), map, page, p.x(), p.y(), name, aliases,
                PlaceCategory.normalize(p.category()), note,
                clip(trim(p.icon()), MAX_ICON), clip(trim(p.color()), MAX_COLOR),
                p.landmark(), Math.max(0, Math.min(100, p.priority())), p.visible(),
                p.canvasFp(), p.anchorOk(), p.createdAt(), p.updatedAt());
    }

    private static Place fromRow(KbMapPlaceRepository.PlaceRow r, Fingerprints idx) {
        String current = idx.usable() ? idx.forPlace(r.map(), r.page()) : "";
        return new Place(r.id(), r.map(), r.page(), r.x(), r.y(), r.name(),
                splitAliases(r.aliases()), PlaceCategory.normalize(r.category()), r.note(),
                r.icon(), r.color(), r.landmark(), r.priority(), r.visible(),
                r.canvasFp(), !idx.usable() || anchorOk(r.canvasFp(), current),
                r.createdAt(), r.updatedAt());
    }

    /** 别名列表 → 逗号串 */
    static String joinAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return "";
        }
        return String.join(",", aliases);
    }

    /** 逗号串 → 别名列表。中英文逗号都认 —— 管理员手输时两种都可能 */
    static List<String> splitAliases(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,，]")) {
            String s = part.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 分类的合法值 —— 管理端下拉框用同一份，避免界面写出一个服务层不认的词 */
    public static List<String> categories() {
        return List.of(PlaceCategory.RESOURCE, PlaceCategory.LANDMARK, PlaceCategory.POI,
                PlaceCategory.NPC, PlaceCategory.REGION, PlaceCategory.COLLECTION,
                PlaceCategory.CUSTOM);
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
