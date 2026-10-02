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

    /** 某个分组有多少 marker */
    public record GroupCount(String group, long count) {
    }

    /** 某张地图有多少 marker */
    public record MapCount(String map, long count) {
    }

    private static final String MARKER_COLUMNS =
            "map, page, marker_id, grp, name, description, article, x, y, image";

    private static final Jdbc.RowMapper<MarkerRow> MARKER = r -> new MarkerRow(
            r.str("map"), r.str("page"), r.str("grp"), r.str("marker_id"), r.str("name"),
            r.str("description"), r.str("article"), r.dbl("x"), r.dbl("y"), r.str("image"));

    private static final String UPSERT_GROUP =
            "INSERT INTO kb_map_group (map, key, name, icon, collectible) VALUES (?,?,?,?,?) "
                    + "ON CONFLICT(map, key) DO UPDATE SET name=excluded.name,"
                    + " icon=excluded.icon, collectible=excluded.collectible";

    private static final String INSERT_MARKER =
            "INSERT INTO kb_map_marker (" + MARKER_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)";

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
                           List<GroupRow> groups, List<MarkerRow> markers, String syncedAt) {
        return jdbc.transaction(() -> {
            jdbc.update("DELETE FROM kb_map_marker WHERE page = ?", page);

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
}
