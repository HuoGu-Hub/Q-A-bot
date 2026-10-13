package com.example.qqbot.kb.map;

import com.example.qqbot.kb.PlaceCategory;
import com.example.qqbot.kb.PlacePoint;
import com.example.qqbot.persistence.KbMapPlaceRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * 一个自定义点位（落库/读出一致）。
     *
     * <p>{@code id <= 0} 表示"还没入库" —— 新增时用这个约定，不需要第二个类型。
     */
    public record Place(long id, String map, String page, double x, double y, String name,
                        List<String> aliases, String category, String note, String icon,
                        String color, boolean landmark, int priority, boolean visible,
                        String createdAt, String updatedAt) {

        /** 转成核心认识的地点 —— 检索与相对位置描述用的是这个 */
        public PlacePoint toPoint() {
            return new PlacePoint(map, page, name, aliases, x, y, category, note,
                    landmark, priority, "admin", "place:" + id);
        }
    }

    private final KbMapPlaceRepository repo;

    private volatile boolean available;

    public MapPlaceService(KbMapPlaceRepository repo) {
        this.repo = repo;
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
            return repo.byMap(map, onlyVisible).stream().map(MapPlaceService::fromRow).toList();
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
            return row == null ? null : fromRow(row);
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
            return repo.search(term.strip(), onlyVisible, limit).stream()
                    .map(MapPlaceService::fromRow).toList();
        } catch (Exception e) {
            log.warn("[MAP-PLACE] 搜索失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 新增。校验不过抛 {@link IllegalArgumentException}（控制器翻成 400） */
    public Place create(Place input) {
        requireAvailable();
        Place clean = validate(input, true);
        String now = KbMapStore.now();
        long id = repo.insert(new KbMapPlaceRepository.PlaceRow(0, clean.map(), clean.page(),
                clean.x(), clean.y(), clean.name(), joinAliases(clean.aliases()), clean.category(),
                clean.note(), clean.icon(), clean.color(), clean.landmark(), clean.priority(),
                clean.visible(), now, now));
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
        boolean ok = repo.update(new KbMapPlaceRepository.PlaceRow(clean.id(), clean.map(),
                clean.page(), clean.x(), clean.y(), clean.name(), joinAliases(clean.aliases()),
                clean.category(), clean.note(), clean.icon(), clean.color(), clean.landmark(),
                clean.priority(), clean.visible(), "", KbMapStore.now()));
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
                p.createdAt(), p.updatedAt());
    }

    private static Place fromRow(KbMapPlaceRepository.PlaceRow r) {
        return new Place(r.id(), r.map(), r.page(), r.x(), r.y(), r.name(),
                splitAliases(r.aliases()), PlaceCategory.normalize(r.category()), r.note(),
                r.icon(), r.color(), r.landmark(), r.priority(), r.visible(),
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
