package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 管理员自定义点位（{@code kb_map_place}）的**数据访问**。
 *
 * <h2>为什么不复用 {@code kb_map_marker}</h2>
 * 那张表是「wiki 某一页的镜像」，同步时按页 <b>先删后插</b>
 * （见 {@link KbMapRepository#replacePage}）—— 管理员辛苦标的地点会被下一次同步静默清掉。
 * <b>写入方不同的数据必须分表</b>：wiki 的那张由同步器独占，这张只由管理端写。
 *
 * <h2>坐标属于哪张画布</h2>
 * {@code page} 指向 {@code kb_map_canvas} 的一行。默认留空表示"用这张地图的主画布" ——
 * 管理员在界面上看到的是哪张底图，落点就该记在哪张画布上。
 */
@Repository
public class KbMapPlaceRepository {

    /**
     * 一行自定义点位。
     *
     * <p>{@code aliases} 是**逗号分隔的字符串**而不是子表：别名只用于检索与展示，
     * 量级是个位数，为它多一张表换不来任何东西（没有按别名反查的独立需求）。
     */
    public record PlaceRow(long id, String map, String page, double x, double y, String name,
                           String aliases, String category, String note, String icon, String color,
                           boolean landmark, int priority, boolean visible,
                           String createdAt, String updatedAt) {
    }

    private static final String COLUMNS = "id, map, page, x, y, name, aliases, category, note,"
            + " icon, color, landmark, priority, visible, created_at, updated_at";

    private static final Jdbc.RowMapper<PlaceRow> PLACE = r -> new PlaceRow(
            r.longOf("id"), r.str("map"), r.str("page"), r.dbl("x"), r.dbl("y"), r.str("name"),
            r.str("aliases"), r.str("category"), r.str("note"), r.str("icon"), r.str("color"),
            r.intOf("landmark") != 0, r.intOf("priority"), r.intOf("visible") != 0,
            r.str("created_at"), r.str("updated_at"));

    private static final String INSERT =
            "INSERT INTO kb_map_place (map, page, x, y, name, aliases, category, note, icon, color,"
                    + " landmark, priority, visible, created_at, updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String UPDATE =
            "UPDATE kb_map_place SET map=?, page=?, x=?, y=?, name=?, aliases=?, category=?,"
                    + " note=?, icon=?, color=?, landmark=?, priority=?, visible=?, updated_at=?"
                    + " WHERE id=?";

    private final Jdbc jdbc;

    public KbMapPlaceRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_place ("
                + " id          INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " map         TEXT NOT NULL,"
                + " page        TEXT NOT NULL DEFAULT '',"
                + " x           REAL NOT NULL DEFAULT 0,"
                + " y           REAL NOT NULL DEFAULT 0,"
                + " name        TEXT NOT NULL DEFAULT '',"
                + " aliases     TEXT NOT NULL DEFAULT '',"
                + " category    TEXT NOT NULL DEFAULT 'custom',"
                + " note        TEXT NOT NULL DEFAULT '',"
                + " icon        TEXT NOT NULL DEFAULT '',"
                + " color       TEXT NOT NULL DEFAULT '',"
                + " landmark    INTEGER NOT NULL DEFAULT 0,"
                + " priority    INTEGER NOT NULL DEFAULT 0,"
                + " visible     INTEGER NOT NULL DEFAULT 1,"
                + " created_at  TEXT NOT NULL DEFAULT '',"
                + " updated_at  TEXT NOT NULL DEFAULT '')");
        // 按地图列点（查看器/管理端每次打开都要），按名字模糊搜（"哪里有铁矿"）
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_map_place_map ON kb_map_place(map)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_map_place_name ON kb_map_place(name)");
    }

    /** 新增，返回自增主键；失败返回 -1 */
    public long insert(PlaceRow p) {
        return jdbc.insert(INSERT, p.map(), nz(p.page()), p.x(), p.y(), nz(p.name()), nz(p.aliases()),
                nz(p.category()), nz(p.note()), nz(p.icon()), nz(p.color()),
                p.landmark() ? 1 : 0, p.priority(), p.visible() ? 1 : 0,
                nz(p.createdAt()), nz(p.updatedAt()));
    }

    /** 修改（按 id）。返回是否真的改到了行 —— 0 行说明 id 不存在 */
    public boolean update(PlaceRow p) {
        return jdbc.update(UPDATE, nz(p.map()), nz(p.page()), p.x(), p.y(), nz(p.name()),
                nz(p.aliases()), nz(p.category()), nz(p.note()), nz(p.icon()), nz(p.color()),
                p.landmark() ? 1 : 0, p.priority(), p.visible() ? 1 : 0,
                nz(p.updatedAt()), p.id()) > 0;
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM kb_map_place WHERE id = ?", id) > 0;
    }

    public PlaceRow byId(long id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM kb_map_place WHERE id = ?", PLACE, id);
    }

    /** 一张地图的全部点位；{@code onlyVisible} 为真时只给公开站要显示的那些 */
    public List<PlaceRow> byMap(String map, boolean onlyVisible) {
        String sql = "SELECT " + COLUMNS + " FROM kb_map_place WHERE map = ?"
                + (onlyVisible ? " AND visible = 1" : "")
                + " ORDER BY priority DESC, name";
        // 空 map = 不过滤，返回全部（管理端"所有地点"视图用）
        if (map == null || map.isBlank()) {
            sql = "SELECT " + COLUMNS + " FROM kb_map_place"
                    + (onlyVisible ? " WHERE visible = 1" : "")
                    + " ORDER BY map, priority DESC, name";
            return jdbc.query(sql, PLACE);
        }
        return jdbc.query(sql, PLACE, map);
    }

    /** 名称 / 别名 / 备注 / 分类 的模糊搜索 —— 问答侧解析"哪里有 X"用 */
    public List<PlaceRow> search(String keyword, boolean onlyVisible, int limit) {
        String like = "%" + keyword + "%";
        return jdbc.query("SELECT " + COLUMNS + " FROM kb_map_place"
                        + " WHERE (name LIKE ? OR aliases LIKE ? OR note LIKE ? OR category LIKE ?)"
                        + (onlyVisible ? " AND visible = 1" : "")
                        + " ORDER BY priority DESC, length(name), name LIMIT ?",
                PLACE, like, like, like, like, Math.max(1, limit));
    }

    public long count() {
        return jdbc.count("SELECT COUNT(*) FROM kb_map_place");
    }

    public long countVisible() {
        return jdbc.count("SELECT COUNT(*) FROM kb_map_place WHERE visible = 1");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
