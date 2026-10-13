package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * wiki 点位的**管理员备注**（{@code kb_map_marker_note}）的数据访问。
 *
 * <h2>为什么用 (map, page, marker_id) 做主键</h2>
 * 和 {@code kb_map_marker} 的主键**同形**，这是刻意的：
 * 同步时按页先删后插的是 marker 表，备注在另一张表里，因此
 * <b>同步既不会丢备注，也不需要同步器知道备注的存在</b>。
 * 换任何别的主键（比如自增 id + 外键）都会让同步必须"搬备注"，
 * 而同步器一旦要懂备注，wiki 数据就不再是可整体重刷的镜像了。
 *
 * <h2>为什么 wiki 点位也要能加备注</h2>
 * 「远古尖塔」在 wiki 上叫 {@code Ancient Spire}，群友不会这么问；
 * 而管理员知道哪几个尖塔"离出生点最近"。这类信息属于本地知识，
 * 不该写回 wiki，也不该混进可重刷的镜像表。
 */
@Repository
public class KbMapNoteRepository {

    /** 一行备注。{@code category} 留空表示"不改动原分组" */
    public record NoteRow(String map, String page, String markerId, String alias, String note,
                          String category, boolean landmark, int priority, boolean hidden,
                          String updatedAt) {
    }

    private static final String COLUMNS = "map, page, marker_id, alias, note, category,"
            + " landmark, priority, hidden, updated_at";

    private static final Jdbc.RowMapper<NoteRow> NOTE = r -> new NoteRow(
            r.str("map"), r.str("page"), r.str("marker_id"), r.str("alias"), r.str("note"),
            r.str("category"), r.intOf("landmark") != 0, r.intOf("priority"),
            r.intOf("hidden") != 0, r.str("updated_at"));

    private static final String UPSERT =
            "INSERT INTO kb_map_marker_note (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT(map, page, marker_id) DO UPDATE SET alias=excluded.alias,"
                    + " note=excluded.note, category=excluded.category, landmark=excluded.landmark,"
                    + " priority=excluded.priority, hidden=excluded.hidden,"
                    + " updated_at=excluded.updated_at";

    private final Jdbc jdbc;

    public KbMapNoteRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_marker_note ("
                + " map        TEXT NOT NULL,"
                + " page       TEXT NOT NULL,"
                + " marker_id  TEXT NOT NULL,"
                + " alias      TEXT NOT NULL DEFAULT '',"
                + " note       TEXT NOT NULL DEFAULT '',"
                + " category   TEXT NOT NULL DEFAULT '',"
                + " landmark   INTEGER NOT NULL DEFAULT 0,"
                + " priority   INTEGER NOT NULL DEFAULT 0,"
                + " hidden     INTEGER NOT NULL DEFAULT 0,"
                + " updated_at TEXT NOT NULL DEFAULT '',"
                + " PRIMARY KEY (map, page, marker_id))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_map_note_landmark"
                + " ON kb_map_marker_note(landmark)");
    }

    public void upsert(NoteRow n) {
        jdbc.update(UPSERT, n.map(), nz(n.page()), nz(n.markerId()), nz(n.alias()), nz(n.note()),
                nz(n.category()), n.landmark() ? 1 : 0, n.priority(), n.hidden() ? 1 : 0,
                nz(n.updatedAt()));
    }

    public boolean delete(String map, String page, String markerId) {
        return jdbc.update("DELETE FROM kb_map_marker_note WHERE map = ? AND page = ? AND marker_id = ?",
                map, nz(page), nz(markerId)) > 0;
    }

    /** 一张地图的全部备注 —— 渲染点位时一次性取回，避免逐点查询 */
    public List<NoteRow> byMap(String map) {
        if (map == null || map.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM kb_map_marker_note", NOTE);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM kb_map_marker_note WHERE map = ? ORDER BY page, marker_id",
                NOTE, map);
    }

    public long count() {
        return jdbc.count("SELECT COUNT(*) FROM kb_map_marker_note");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
