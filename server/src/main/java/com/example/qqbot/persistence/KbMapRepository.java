package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图数据（{@code kb_map_page} / {@code kb_map_group} / {@code kb_map_marker}）的**数据访问**。
 *
 * <h2>三张表，各管一件事</h2>
 * <ul>
 *   <li>{@code kb_map_page} —— **增量同步的状态**（每页的 revid）。没有它，
 *       每次同步都得把 32 页全拉一遍；有了它只拉变过的。</li>
 *   <li>{@code kb_map_group} —— marker 分组定义（显示名 / 图标 / 是否收集品）。</li>
 *   <li>{@code kb_map_marker} —— marker 本身。{@code article} 是通往知识库的外键。</li>
 * </ul>
 *
 * <h2>为什么主键是 (map, page, marker_id) 而不是 (map, marker_id)</h2>
 * 实测同一张地图的不同页会**重复定义同一批 marker**
 * （{@code Map:Embervale/Collectibles} 与 {@code Collectibles2} 是同一份数据的两个版本）。
 * 用 (map, marker_id) 做主键会让后同步的那页**静默覆盖**前一页。
 * 带上 page 就不会丢数据；问答侧按 article/名字聚合时再自行去重。
 *
 * <h2>三处不能顺手改掉的东西</h2>
 * <ul>
 *   <li>{@link #replacePage} 是「**先删该页旧 marker 再插新的**」：这样 wiki 上删掉的
 *       marker 在本地也会消失（幂等 + 能表达删除）。只插不删的话，删掉的会永远留着。</li>
 *   <li>{@link #replacePage} 整页在一个事务里 —— 中途失败不能留半个页面。</li>
 *   <li>{@link #markPageError} **不动 revid**：动了会让下一次同步误以为"这页已经同步过"，
 *       从而**永久跳过**它。</li>
 * </ul>
 */
@Repository
public class KbMapRepository {

    /** 一页的同步状态 */
    public record PageState(String page, String map, long revid, String pageUpdatedAt,
                            String syncedAt, int markerCount, String parseError) {
    }

    /**
     * 一个 marker 的**行形状**。
     *
     * <p>和业务侧的 {@code KbMapStore.Marker} 同形但**不能合并** ——
     * 让持久层返回业务类的嵌套类型会反向建立依赖。
     */
    public record MarkerRow(String map, String page, String group, String markerId, String name,
                            String description, String article, double x, double y, String image) {
    }

    /** 一个分组定义 */
    public record GroupRow(String key, String name, String icon, boolean collectible) {
    }

    /**
     * 一张**画布**：底图与坐标系。
     *
     * <p>为什么画布要按 {@code (map, page)} 存，而不是一张地图一条：
     * 实测同名地图的不同页面坐标系不同 —— {@code Map:Embervale/Main} 是
     * {@code topLeft=(0,10240)}，而 {@code Map:Embervale/Collectibles} 是
     * {@code topLeft=(-3695,-9045)}。按地图存只能留一个，另一个的点位会整体错位。
     *
     * <p>{@code tilesJson} 直接存 wiki 给的瓦片数组原文，本地不重排版；
     * {@code hasCrs} 才是"能不能用来换算像素"的判据 —— 没有 crs 的画布存了也没法画。
     */
    public record CanvasRow(String map, String page, String schemaVer, String fragment,
                            boolean hasCrs, double[] topLeft, double[] bottomRight,
                            double[] tileAt, double[] tileSize, String tilesJson,
                            String backgroundImage, String backdrop,
                            double zoomMin, double zoomMax, String syncedAt) {
    }

    /** 某个分组有多少 marker */
    public record GroupCount(String group, long count) {
    }

    /** 一个分组定义（带地图名，因为分组表是按地图隔离的） */
    public record GroupDef(String map, String key, String name, String icon, boolean collectible) {
    }

    /** 某张地图有多少 marker */
    public record MapCount(String map, long count) {
    }

    private static final String MARKER_COLUMNS =
            "map, page, marker_id, grp, name, description, article, x, y, image";

    private static final Jdbc.RowMapper<MarkerRow> MARKER = r -> new MarkerRow(
            r.str("map"), r.str("page"), r.str("grp"), r.str("marker_id"), r.str("name"),
            r.str("description"), r.str("article"), r.dbl("x"), r.dbl("y"), r.str("image"));

    /**
     * 分组 upsert —— **空值不覆盖已有值**。
     *
     * <p>实测踩到的坑：{@code Map:Embervale/Groups} 给了 43 个分组的完整显示名与图标，
     * 而 {@code Map:Embervale/Tabs/*}（tab 定义页）里同一批 key 只有图标、没有名字。
     * Tabs 页按字典序排在后面，于是"最后写入者胜"把 29 个分组的名字清成了空串 ——
     * 图层开关上只剩复选框和一个数字。
     *
     * <p>根因是"一个分组的完整信息分散在多页里"，所以正确语义是<b>合并</b>而不是覆盖：
     * 新值非空才覆盖，{@code collectible} 取或（任一处说是收集品就是）。
     */
    private static final String UPSERT_GROUP =
            "INSERT INTO kb_map_group (map, key, name, icon, collectible) VALUES (?,?,?,?,?) "
                    + "ON CONFLICT(map, key) DO UPDATE SET"
                    + " name=CASE WHEN excluded.name <> '' THEN excluded.name ELSE kb_map_group.name END,"
                    + " icon=CASE WHEN excluded.icon <> '' THEN excluded.icon ELSE kb_map_group.icon END,"
                    + " collectible=MAX(kb_map_group.collectible, excluded.collectible)";

    private static final String INSERT_MARKER =
            "INSERT INTO kb_map_marker (" + MARKER_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)";

    /** 画布列清单 —— 读与写共用一份，避免两边列序漂移 */
    private static final String CANVAS_COLUMNS =
            "map, page, schema_ver, fragment, has_crs, top_left_x, top_left_y, bottom_right_x,"
                    + " bottom_right_y, tile_at_x, tile_at_y, tile_w, tile_h, tiles_json,"
                    + " background_image, backdrop, zoom_min, zoom_max, synced_at";

    private static final Jdbc.RowMapper<CanvasRow> CANVAS = r -> new CanvasRow(
            r.str("map"), r.str("page"), r.str("schema_ver"), r.str("fragment"),
            r.intOf("has_crs") != 0,
            new double[]{r.dbl("top_left_x"), r.dbl("top_left_y")},
            new double[]{r.dbl("bottom_right_x"), r.dbl("bottom_right_y")},
            new double[]{r.dbl("tile_at_x"), r.dbl("tile_at_y")},
            new double[]{r.dbl("tile_w"), r.dbl("tile_h")},
            r.str("tiles_json"), r.str("background_image"), r.str("backdrop"),
            r.dbl("zoom_min"), r.dbl("zoom_max"), r.str("synced_at"));

    private static final String UPSERT_CANVAS =
            "INSERT INTO kb_map_canvas (" + CANVAS_COLUMNS + ")"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT(map, page) DO UPDATE SET schema_ver=excluded.schema_ver,"
                    + " fragment=excluded.fragment, has_crs=excluded.has_crs,"
                    + " top_left_x=excluded.top_left_x, top_left_y=excluded.top_left_y,"
                    + " bottom_right_x=excluded.bottom_right_x, bottom_right_y=excluded.bottom_right_y,"
                    + " tile_at_x=excluded.tile_at_x, tile_at_y=excluded.tile_at_y,"
                    + " tile_w=excluded.tile_w, tile_h=excluded.tile_h,"
                    + " tiles_json=excluded.tiles_json, background_image=excluded.background_image,"
                    + " backdrop=excluded.backdrop, zoom_min=excluded.zoom_min,"
                    + " zoom_max=excluded.zoom_max, synced_at=excluded.synced_at";

    private static final String UPSERT_PAGE =
            "INSERT INTO kb_map_page (page, map, revid, page_updated_at, synced_at, marker_count, parse_error)"
                    + " VALUES (?,?,?,?,?,?,'')"
                    + " ON CONFLICT(page) DO UPDATE SET map=excluded.map, revid=excluded.revid,"
                    + " page_updated_at=excluded.page_updated_at, synced_at=excluded.synced_at,"
                    + " marker_count=excluded.marker_count, parse_error=''";

    private final Jdbc jdbc;

    public KbMapRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_page ("
                + " page            TEXT PRIMARY KEY,"
                + " map             TEXT NOT NULL DEFAULT '',"
                + " revid           INTEGER NOT NULL DEFAULT 0,"
                + " page_updated_at TEXT NOT NULL DEFAULT '',"
                + " synced_at       TEXT NOT NULL DEFAULT '',"
                + " marker_count    INTEGER NOT NULL DEFAULT 0,"
                + " parse_error     TEXT NOT NULL DEFAULT '')");

        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_group ("
                + " map         TEXT NOT NULL,"
                + " key         TEXT NOT NULL,"
                + " name        TEXT NOT NULL DEFAULT '',"
                + " icon        TEXT NOT NULL DEFAULT '',"
                + " collectible INTEGER NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (map, key))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_marker ("
                + " map         TEXT NOT NULL,"
                + " page        TEXT NOT NULL,"
                + " marker_id   TEXT NOT NULL,"
                + " grp         TEXT NOT NULL DEFAULT '',"
                + " name        TEXT NOT NULL DEFAULT '',"
                + " description TEXT NOT NULL DEFAULT '',"
                + " article     TEXT NOT NULL DEFAULT '',"
                + " x           REAL NOT NULL DEFAULT 0,"
                + " y           REAL NOT NULL DEFAULT 0,"
                + " image       TEXT NOT NULL DEFAULT '',"
                + " PRIMARY KEY (map, page, marker_id))");
        // 画布：先于 marker 存在也无妨，同步时 was 一页一页 upsert。
        // ⚠️ 全部是 ADDITIVE DDL（IF NOT EXISTS / 默认值），不删列不重建表 ——
        //    生产库是 40 MB 的活库，任何重建都是风险。
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_canvas ("
                + " map              TEXT NOT NULL,"
                + " page             TEXT NOT NULL,"
                + " schema_ver       TEXT NOT NULL DEFAULT '',"
                + " fragment         TEXT NOT NULL DEFAULT '',"
                + " has_crs          INTEGER NOT NULL DEFAULT 0,"
                + " top_left_x       REAL NOT NULL DEFAULT 0,"
                + " top_left_y       REAL NOT NULL DEFAULT 0,"
                + " bottom_right_x   REAL NOT NULL DEFAULT 0,"
                + " bottom_right_y   REAL NOT NULL DEFAULT 0,"
                + " tile_at_x        REAL NOT NULL DEFAULT 0,"
                + " tile_at_y        REAL NOT NULL DEFAULT 0,"
                + " tile_w           REAL NOT NULL DEFAULT 0,"
                + " tile_h           REAL NOT NULL DEFAULT 0,"
                + " tiles_json       TEXT NOT NULL DEFAULT '',"
                + " background_image TEXT NOT NULL DEFAULT '',"
                + " backdrop         TEXT NOT NULL DEFAULT '',"
                + " zoom_min         REAL NOT NULL DEFAULT 0,"
                + " zoom_max         REAL NOT NULL DEFAULT 0,"
                + " synced_at        TEXT NOT NULL DEFAULT '',"
                + " PRIMARY KEY (map, page))");

        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_map_marker_article ON kb_map_marker(article)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_map_marker_grp ON kb_map_marker(map, grp)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_map_marker_name ON kb_map_marker(name)");
    }

    /** 每页上次同步的 revid —— 增量同步的判据 */
    public Map<String, Long> knownRevisions() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : jdbc.query("SELECT page, revid FROM kb_map_page",
                r -> Map.entry(r.str("page"), r.longOf("revid")))) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * 每张地图的页面**在 wiki 上**最后一次改动的时间 —— 后台判断"哪张是新的"用的客观信号。
     *
     * <p>为什么不用我们自己的 {@code synced_at}：那是"我们什么时候同步过"，
     * 每张图每次同步都会更新，区分不出新旧。要的是对方什么时候动过这页。
     */
    public Map<String, String> latestPageUpdate() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : jdbc.query(
                "SELECT map, MAX(page_updated_at) AS at FROM kb_map_page GROUP BY map",
                r -> Map.entry(r.str("map"), r.str("at")))) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 一页的同步状态（含出错原因），供管理端排查 */
    public List<PageState> pages() {
        return jdbc.query("SELECT page, map, revid, page_updated_at, synced_at, marker_count, parse_error"
                        + " FROM kb_map_page ORDER BY map, page",
                r -> new PageState(r.str("page"), r.str("map"), r.longOf("revid"),
                        r.str("page_updated_at"), r.str("synced_at"),
                        r.intOf("marker_count"), r.str("parse_error")));
    }

    /**
     * 写入一页的解析结果：**先删该页旧 marker 再插新的**，整页一个事务（见类注释）。
     *
     * @return 写入的 marker 条数
     */
    public int replacePage(String page, String map, long revid, String pageUpdatedAt,
                           List<GroupRow> groups, List<MarkerRow> markers, CanvasRow canvas,
                           String syncedAt) {
        return jdbc.transaction(() -> {
            jdbc.update("DELETE FROM kb_map_marker WHERE page = ?", page);

            // 画布与 marker 同一个事务：底图和它的点位必须一起生效、一起回滚。
            // 分开写会出现"底图更新了但点位还是旧的"这种半页状态，看起来就是整张图错位。
            if (canvas != null) {
                jdbc.update(UPSERT_CANVAS,
                        canvas.map(), canvas.page(), nz(canvas.schemaVer()), nz(canvas.fragment()),
                        canvas.hasCrs() ? 1 : 0,
                        at(canvas.topLeft(), 0), at(canvas.topLeft(), 1),
                        at(canvas.bottomRight(), 0), at(canvas.bottomRight(), 1),
                        at(canvas.tileAt(), 0), at(canvas.tileAt(), 1),
                        at(canvas.tileSize(), 0), at(canvas.tileSize(), 1),
                        nz(canvas.tilesJson()), nz(canvas.backgroundImage()), nz(canvas.backdrop()),
                        canvas.zoomMin(), canvas.zoomMax(), nz(canvas.syncedAt()));
            }

            jdbc.batch(UPSERT_GROUP, groups.stream()
                    .map(g -> new Object[]{map, g.key(), nz(g.name()), nz(g.icon()), g.collectible()})
                    .toList());

            jdbc.batch(INSERT_MARKER, markers.stream()
                    .map(m -> new Object[]{m.map(), page, m.markerId(), nz(m.group()), nz(m.name()),
                            nz(m.description()), nz(m.article()), m.x(), m.y(), nz(m.image())})
                    .toList());

            jdbc.update(UPSERT_PAGE, page, map, revid, nz(pageUpdatedAt), syncedAt, markers.size());
            return markers.size();
        });
    }

    /** 记录一页同步失败（不让一页坏掉整次同步，但要在库里能看见）—— **不动 revid**，见类注释 */
    public void markPageError(String page, String map, long revid, String syncedAt, String message) {
        jdbc.update("INSERT INTO kb_map_page (page, map, revid, synced_at, marker_count, parse_error)"
                        + " VALUES (?,?,?,?,0,?)"
                        + " ON CONFLICT(page) DO UPDATE SET parse_error=excluded.parse_error,"
                        + " synced_at=excluded.synced_at",
                page, map, revid, syncedAt, message);
    }

    /**
     * 按词条名找 marker —— 问答侧 join 知识库用。
     *
     * <p>{@code ORDER BY} 不是装饰：同一个 article 可能出现在多张地图里（实测 "Ancient Spire"
     * 在 Embervale 和 Blackmire 都有），没有它返回顺序就是不确定的，调用方和测试都会飘。
     */
    public List<MarkerRow> byArticle(String article, int limit) {
        return jdbc.query("SELECT " + MARKER_COLUMNS
                        + " FROM kb_map_marker WHERE article = ? ORDER BY map, page, marker_id LIMIT ?",
                MARKER, article, limit);
    }

    /** 全部 marker —— 派生语料时用 */
    public List<MarkerRow> allMarkers() {
        return jdbc.query("SELECT " + MARKER_COLUMNS
                + " FROM kb_map_marker ORDER BY map, page, marker_id", MARKER);
    }

    /** 按名字模糊找 marker（玩家常只记得大概的名字） */
    public List<MarkerRow> byNameLike(String keyword, int limit) {
        return jdbc.query("SELECT " + MARKER_COLUMNS
                        + " FROM kb_map_marker WHERE name LIKE ? ORDER BY map, page, marker_id LIMIT ?",
                MARKER, keyword, limit);
    }

    /**
     * 全部画布（**含画不出来的**）—— 管理端排查用。
     *
     * <p>{@code Map:Embervale/Groups} 这类页面没有 crs 也没有底图，但它确实是一页，
     * 在管理端看得见才有意义（否则会以为"这页没同步"）。
     */
    public List<CanvasRow> canvases() {
        return jdbc.query("SELECT " + CANVAS_COLUMNS + " FROM kb_map_canvas ORDER BY map, page", CANVAS);
    }

    /**
     * **能画出来的**画布 —— 查看器只认这些：有坐标系，且底图非空。
     *
     * <p>过滤放在 SQL 里而不是调用方：这是数据本身的属性，不是某个页面的偏好。
     */
    public List<CanvasRow> renderableCanvases() {
        return jdbc.query("SELECT " + CANVAS_COLUMNS + " FROM kb_map_canvas"
                + " WHERE has_crs = 1 AND (tiles_json <> '' OR background_image <> '')"
                + " ORDER BY map, page", CANVAS);
    }

    /** 每页同步时顺手写下的画布数 */
    public long canvasCount() {
        return jdbc.count("SELECT COUNT(*) FROM kb_map_canvas");
    }

    /** 每个分组有多少 marker —— 语料扩维时按这个决定先做哪类；{@code map} 空 = 全部地图 */
    public List<GroupCount> countsByGroup(String map) {
        boolean filtered = map != null && !map.isBlank();
        String sql = "SELECT grp, COUNT(*) n FROM kb_map_marker"
                + (filtered ? " WHERE map = ?" : "")
                + " GROUP BY grp ORDER BY n DESC";
        return filtered
                ? jdbc.query(sql, r -> new GroupCount(r.str("grp"), r.longOf("n")), map)
                : jdbc.query(sql, r -> new GroupCount(r.str("grp"), r.longOf("n")));
    }

    /**
     * 一张地图的分组定义（显示名 / 图标 / 是否收集品）。
     *
     * <p>为什么要单独取：查看器的图层开关要显示"火焰神龛"这种名字和它的图标 ——
     * 这些只存在分组表里，marker 行上只有分组的 key。
     */
    public List<GroupDef> groups(String map) {
        return jdbc.query("SELECT map, key, name, icon, collectible FROM kb_map_group"
                + (map == null || map.isBlank() ? "" : " WHERE map = ?")
                + " ORDER BY key",
                r -> new GroupDef(r.str("map"), r.str("key"), r.str("name"), r.str("icon"),
                        r.intOf("collectible") != 0),
                map == null || map.isBlank() ? new Object[0] : new Object[]{map});
    }

    /** 有哪些地图，各自多少 marker */
    public List<MapCount> mapCounts() {
        return jdbc.query("SELECT map, COUNT(*) n FROM kb_map_marker GROUP BY map ORDER BY n DESC",
                r -> new MapCount(r.str("map"), r.longOf("n")));
    }

    public long markerCount() {
        return jdbc.count("SELECT COUNT(*) FROM kb_map_marker");
    }

    public long pageCount() {
        return jdbc.count("SELECT COUNT(*) FROM kb_map_page");
    }

    public long groupCount() {
        return jdbc.count("SELECT COUNT(*) FROM kb_map_group");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 取数组里的第 i 项；数组缺失或太短一律给 0 —— 画布允许"只有一半信息" */
    private static double at(double[] arr, int i) {
        return arr != null && arr.length > i ? arr[i] : 0;
    }
}
