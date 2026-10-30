package com.example.qqbot.persistence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MOD 资源点位（{@code kb_map_resource*}）的持久层。
 *
 * <p>与 {@code kb_map_marker} 分开：wiki 的 marker 以页面为键、每次同步整页替换，
 * 而这里的点位是一次导入的静态快照，按版本整批替换。两者混在一张表里，
 * 一次 wiki 同步就会把资源点清掉。
 *
 * <h2>三张表</h2>
 * <ul>
 *   <li>{@code kb_map_resource_meta} —— 这份快照的版本、文件哈希、点数；判重用。</li>
 *   <li>{@code kb_map_resource_layer} —— 资源面板的每一行：种类、显示名、颜色、点数。</li>
 *   <li>{@code kb_map_resource} —— 点位本身。{@code layer} 指向上一张表的 key。</li>
 * </ul>
 */
@Repository
public class MapResourceRepository {

    /** 一行图层定义 */
    public record LayerRow(String key, String kind, String subtype, String name, String nameZh,
                           String color, String icon, int count, int sort) {
    }

    /** 一个资源点 */
    public record PointRow(String layer, String name, int region, String regionName,
                           double x, double z, String sourceId) {
    }

    /** 一块林地矩形（世界坐标，x0/z0 是西南角，x1/z1 是东北角） */
    public record AreaRow(String layer, String name, int region, double x0, double z0,
                          double x1, double z1, String sourceId) {
    }

    /** 一次导入的快照信息 */
    public record MetaRow(String map, String version, String sourceHash, String canvasFp,
                          int points, int areas, String importedAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(MapResourceRepository.class);

    private final Jdbc jdbc;

    private volatile boolean ready;

    public MapResourceRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        if (!jdbc.isAvailable()) {
            log.warn("[MAP-RES] 问答库不可用，资源点位存储关闭");
            return;
        }
        try {
            initSchema();
            ready = true;
        } catch (Exception e) {
            log.warn("[MAP-RES] 建表失败，资源点位存储关闭：{}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return ready && jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_resource_meta ("
                + " map         TEXT PRIMARY KEY,"
                + " version     TEXT NOT NULL DEFAULT '',"
                + " source_hash TEXT NOT NULL DEFAULT '',"
                + " canvas_fp   TEXT NOT NULL DEFAULT '',"
                + " points      INTEGER NOT NULL DEFAULT 0,"
                + " areas       INTEGER NOT NULL DEFAULT 0,"
                + " imported_at TEXT NOT NULL DEFAULT '')");

        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_resource_layer ("
                + " map      TEXT NOT NULL,"
                + " key      TEXT NOT NULL,"
                + " kind     TEXT NOT NULL DEFAULT '',"
                + " subtype  TEXT NOT NULL DEFAULT '',"
                + " name     TEXT NOT NULL DEFAULT '',"
                + " name_zh  TEXT NOT NULL DEFAULT '',"
                + " color    TEXT NOT NULL DEFAULT '',"
                + " icon     TEXT NOT NULL DEFAULT '',"
                + " count    INTEGER NOT NULL DEFAULT 0,"
                + " sort     INTEGER NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (map, key))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_resource ("
                + " map       TEXT NOT NULL,"
                + " layer     TEXT NOT NULL,"
                + " name      TEXT NOT NULL DEFAULT '',"
                + " region    INTEGER NOT NULL DEFAULT -1,"
                + " region_name TEXT NOT NULL DEFAULT '',"
                + " x         REAL NOT NULL DEFAULT 0,"
                + " z         REAL NOT NULL DEFAULT 0,"
                + " source_id TEXT NOT NULL,"
                + " PRIMARY KEY (map, source_id))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_resource_area ("
                + " map       TEXT NOT NULL,"
                + " layer     TEXT NOT NULL,"
                + " name      TEXT NOT NULL DEFAULT '',"
                + " region    INTEGER NOT NULL DEFAULT -1,"
                + " x0        REAL NOT NULL DEFAULT 0,"
                + " z0        REAL NOT NULL DEFAULT 0,"
                + " x1        REAL NOT NULL DEFAULT 0,"
                + " z1        REAL NOT NULL DEFAULT 0,"
                + " source_id TEXT NOT NULL,"
                + " PRIMARY KEY (map, source_id))");

        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_map_resource_layer"
                + " ON kb_map_resource(map, layer)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_map_resource_area_layer"
                + " ON kb_map_resource_area(map, layer)");
        // 旧库没有 canvas_fp：加列是 additive DDL，不重建表
        try {
            jdbc.update("ALTER TABLE kb_map_resource_meta ADD COLUMN canvas_fp TEXT NOT NULL DEFAULT ''");
        } catch (Exception ignored) {
            // 列已经存在
        }
        // 旧库没有 region_name：先加空列，导入器会在下一次启动时补一次快照
        try {
            jdbc.update("ALTER TABLE kb_map_resource ADD COLUMN region_name TEXT NOT NULL DEFAULT ''");
        } catch (Exception ignored) {
            // 列已经存在
        }
        try {
            jdbc.update("ALTER TABLE kb_map_resource_layer ADD COLUMN icon TEXT NOT NULL DEFAULT ''");
        } catch (Exception ignored) {
            // 列已经存在
        }
    }

    public MetaRow meta(String map) {
        return jdbc.queryOne("SELECT map, version, source_hash, canvas_fp, points, areas, imported_at"
                        + " FROM kb_map_resource_meta WHERE map = ?",
                r -> new MetaRow(r.str("map"), r.str("version"), r.str("source_hash"),
                        r.str("canvas_fp"), r.intOf("points"), r.intOf("areas"),
                        r.str("imported_at")), map);
    }

    public List<LayerRow> layers(String map) {
        return jdbc.query("SELECT key, kind, subtype, name, name_zh, color, icon, count, sort"
                        + " FROM kb_map_resource_layer WHERE map = ? ORDER BY sort, key",
                r -> new LayerRow(r.str("key"), r.str("kind"), r.str("subtype"),
                        r.str("name"), r.str("name_zh"), r.str("color"), r.str("icon"),
                        r.intOf("count"), r.intOf("sort")), map);
    }

    public List<PointRow> points(String map, String layer) {
        return jdbc.query("SELECT layer, name, region, region_name, x, z, source_id"
                        + " FROM kb_map_resource WHERE map = ? AND layer = ? ORDER BY x, z",
                r -> new PointRow(r.str("layer"), r.str("name"), r.intOf("region"),
                        r.str("region_name"), r.dbl("x"), r.dbl("z"), r.str("source_id")),
                map, layer);
    }

    /** 旧库刚加列时 region_name 还是空的：导入器据此补一次快照 */
    public boolean regionNamesMissing(String map) {
        return jdbc.count("SELECT COUNT(*) FROM kb_map_resource"
                + " WHERE map = ? AND region >= 0 AND (region_name IS NULL OR region_name = '')", map) > 0;
    }

    public List<AreaRow> areas(String map, String layer) {
        return jdbc.query("SELECT layer, name, region, x0, z0, x1, z1, source_id"
                        + " FROM kb_map_resource_area WHERE map = ? AND layer = ?"
                        + " ORDER BY x0, z0",
                r -> new AreaRow(r.str("layer"), r.str("name"), r.intOf("region"),
                        r.dbl("x0"), r.dbl("z0"), r.dbl("x1"), r.dbl("z1"),
                        r.str("source_id")), map, layer);
    }

    public int pointCount(String map) {
        return (int) jdbc.count("SELECT COUNT(*) FROM kb_map_resource WHERE map = ?", map);
    }

    public int areaCount(String map) {
        return (int) jdbc.count("SELECT COUNT(*) FROM kb_map_resource_area WHERE map = ?", map);
    }

    /**
     * 整批替换一张地图的资源点。先删后插，整段一个事务：
     * 导入到一半失败时，旧快照仍然完整可用。
     */
    public int replaceAll(String map, String version, String sourceHash, String canvasFp,
                          List<LayerRow> layers, List<PointRow> points, List<AreaRow> areas,
                          String importedAt) {
        return jdbc.transaction(() -> {
            jdbc.update("DELETE FROM kb_map_resource WHERE map = ?", map);
            jdbc.update("DELETE FROM kb_map_resource_area WHERE map = ?", map);
            jdbc.update("DELETE FROM kb_map_resource_layer WHERE map = ?", map);

            batch("INSERT INTO kb_map_resource_layer"
                            + " (map, key, kind, subtype, name, name_zh, color, icon, count, sort)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    layers.stream().map(l -> new Object[]{map, l.key(), nz(l.kind()),
                            nz(l.subtype()), nz(l.name()), nz(l.nameZh()), nz(l.color()),
                            nz(l.icon()), l.count(), l.sort()}).toList());

            batch("INSERT INTO kb_map_resource (map, layer, name, region, region_name, x, z, source_id)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    points.stream().map(p -> new Object[]{map, p.layer(), nz(p.name()),
                            p.region(), nz(p.regionName()), p.x(), p.z(), p.sourceId()}).toList());

            batch("INSERT INTO kb_map_resource_area"
                            + " (map, layer, name, region, x0, z0, x1, z1, source_id)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    areas.stream().map(a -> new Object[]{map, a.layer(), nz(a.name()),
                            a.region(), a.x0(), a.z0(), a.x1(), a.z1(), a.sourceId()}).toList());

            jdbc.update("INSERT INTO kb_map_resource_meta"
                            + " (map, version, source_hash, canvas_fp, points, areas, imported_at)"
                            + " VALUES (?,?,?,?,?,?,?)"
                            + " ON CONFLICT(map) DO UPDATE SET version=excluded.version,"
                            + " source_hash=excluded.source_hash, canvas_fp=excluded.canvas_fp,"
                            + " points=excluded.points, areas=excluded.areas,"
                            + " imported_at=excluded.imported_at",
                    map, nz(version), nz(sourceHash), nz(canvasFp), points.size(), areas.size(),
                    nz(importedAt));
            return points.size();
        });
    }

    /** SQLite 的单条语句参数有上限，分块写；事务由外层统一控制 */
    private void batch(String sql, List<Object[]> rows) {
        final int chunk = 5000;
        for (int from = 0; from < rows.size(); from += chunk) {
            jdbc.batch(sql, new ArrayList<>(rows.subList(from, Math.min(rows.size(), from + chunk))));
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
