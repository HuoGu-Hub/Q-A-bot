package com.example.qqbot.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 板块表（{@code bot_board}）。
 *
 * <p>板块是配置而不是代码：管理员能加一个"开黑"板块、改它的参数表，不用改程序。
 * 所以它必须落库，而不是写死在某个枚举里。
 *
 * <p>参数表以 JSON 原文存放，解析留给业务侧 —— 持久层只负责"这一行里到底是什么字"。
 */
@Repository
public class BoardRepository {

    private static final Logger log = LoggerFactory.getLogger(BoardRepository.class);

    /** 一行板块配置 */
    public record Row(long id, String path, String title, String paramsJson, String delivery,
                      String access, String scope, String groupsJson,
                      boolean showGroup, boolean showUser, boolean allowPrivate,
                      boolean enabled, int sortOrder, boolean builtin,
                      String listHeader, String listItem, String listEmpty, int listLimit) {

        /** 兼容旧构造：没有列表格式时由 Board 的默认排版兜底 */
        public Row(long id, String path, String title, String paramsJson, String delivery,
                   String access, String scope, String groupsJson,
                   boolean showGroup, boolean showUser, boolean allowPrivate,
                   boolean enabled, int sortOrder, boolean builtin) {
            this(id, path, title, paramsJson, delivery, access, scope, groupsJson,
                    showGroup, showUser, allowPrivate, enabled, sortOrder, builtin,
                    "", "", "", 20);
        }
    }

    private static final String COLUMNS =
            "id, path, title, params, delivery, access, data_scope, data_groups,"
                    + " show_origin_group, show_origin_user, allow_private,"
                    + " enabled, sort_order, builtin,"
                    + " list_header, list_item, list_empty, list_limit";

    private static final Jdbc.RowMapper<Row> MAP = r -> new Row(
            r.longOf("id"), r.str("path"), r.str("title"), r.str("params"), r.str("delivery"),
            r.str("access"), r.str("data_scope"), r.str("data_groups"),
            r.intOf("show_origin_group") == 1, r.intOf("show_origin_user") == 1,
            r.intOf("allow_private") == 1, r.intOf("enabled") == 1, r.intOf("sort_order"),
            r.intOf("builtin") == 1, r.str("list_header"), r.str("list_item"),
            r.str("list_empty"), r.intOf("list_limit"));

    private final Jdbc jdbc;

    public BoardRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bot_meta ("
                + " k TEXT PRIMARY KEY,"
                + " v TEXT NOT NULL"
                + " )");
        jdbc.execute("CREATE TABLE IF NOT EXISTS bot_board ("
                + " id          INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " path        TEXT NOT NULL UNIQUE,"
                + " title       TEXT NOT NULL DEFAULT '',"
                + " params      TEXT NOT NULL DEFAULT '[]',"
                + " delivery    TEXT NOT NULL DEFAULT 'direct',"
                + " access      TEXT NOT NULL DEFAULT 'all',"
                + " data_scope  TEXT NOT NULL DEFAULT 'GROUP',"
                + " data_groups TEXT NOT NULL DEFAULT '[]',"
                + " show_origin_group INTEGER NOT NULL DEFAULT 0,"
                + " show_origin_user  INTEGER NOT NULL DEFAULT 0,"
                + " allow_private     INTEGER NOT NULL DEFAULT 0,"
                + " enabled     INTEGER NOT NULL DEFAULT 1,"
                + " sort_order  INTEGER NOT NULL DEFAULT 0,"
                + " builtin     INTEGER NOT NULL DEFAULT 0,"
                + " list_header TEXT NOT NULL DEFAULT '',"
                + " list_item   TEXT NOT NULL DEFAULT '',"
                + " list_empty  TEXT NOT NULL DEFAULT '',"
                + " list_limit  INTEGER NOT NULL DEFAULT 20"
                + " )");
        // 老库升级：CREATE TABLE IF NOT EXISTS 不会补列
        addColumnIfMissing("list_header", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing("list_item", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing("list_empty", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing("list_limit", "INTEGER NOT NULL DEFAULT 20");
    }

    private void addColumnIfMissing(String column, String ddl) {
        boolean exists = jdbc.query("PRAGMA table_info(bot_board)", r -> r.str("name")).stream()
                .anyMatch(n -> column.equalsIgnoreCase(n));
        if (exists) {
            return;
        }
        jdbc.execute("ALTER TABLE bot_board ADD COLUMN " + column + " " + ddl);
        log.info("[BOARD] bot_board 补列：{} {}", column, ddl);
    }

    /** 种子：已经有了就不动，管理员改过的配置不能被重启覆盖 */
    public void seed(Row row) {
        if (findByPath(row.path()) != null) {
            return;
        }
        jdbc.insert("INSERT INTO bot_board"
                        + " (path, title, params, delivery, access, data_scope, data_groups,"
                        + "  show_origin_group, show_origin_user, allow_private,"
                        + "  enabled, sort_order, builtin,"
                        + "  list_header, list_item, list_empty, list_limit)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.path(), row.title(), row.paramsJson(), row.delivery(), row.access(),
                row.scope(), row.groupsJson(),
                row.showGroup() ? 1 : 0, row.showUser() ? 1 : 0, row.allowPrivate() ? 1 : 0,
                row.enabled() ? 1 : 0, row.sortOrder(), row.builtin() ? 1 : 0,
                row.listHeader(), row.listItem(), row.listEmpty(), row.listLimit());
    }

    public boolean hasMeta(String key) {
        return jdbc.count("SELECT COUNT(*) FROM bot_meta WHERE k = ?", key) > 0;
    }

    public void setMeta(String key, String value) {
        jdbc.update("INSERT INTO bot_meta(k, v) VALUES(?, ?)"
                + " ON CONFLICT(k) DO UPDATE SET v = excluded.v", key, value);
    }

    public Row findByPath(String path) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM bot_board WHERE path = ?", MAP, path);
    }

    public List<Row> listAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_board ORDER BY sort_order, id", MAP);
    }

    public List<Row> listEnabled() {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_board WHERE enabled = 1"
                + " ORDER BY sort_order, id", MAP);
    }

    public void save(Row row) {
        jdbc.update("INSERT INTO bot_board"
                        + " (path, title, params, delivery, access, data_scope, data_groups,"
                        + "  show_origin_group, show_origin_user, allow_private,"
                        + "  enabled, sort_order, builtin,"
                        + "  list_header, list_item, list_empty, list_limit)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT(path) DO UPDATE SET"
                        + "  title=excluded.title, params=excluded.params, delivery=excluded.delivery,"
                        + "  access=excluded.access, data_scope=excluded.data_scope,"
                        + "  data_groups=excluded.data_groups,"
                        + "  show_origin_group=excluded.show_origin_group,"
                        + "  show_origin_user=excluded.show_origin_user,"
                        + "  allow_private=excluded.allow_private, enabled=excluded.enabled,"
                        + "  sort_order=excluded.sort_order,"
                        + "  list_header=excluded.list_header, list_item=excluded.list_item,"
                        + "  list_empty=excluded.list_empty, list_limit=excluded.list_limit",
                row.path(), row.title(), row.paramsJson(), row.delivery(), row.access(),
                row.scope(), row.groupsJson(),
                row.showGroup() ? 1 : 0, row.showUser() ? 1 : 0, row.allowPrivate() ? 1 : 0,
                row.enabled() ? 1 : 0, row.sortOrder(), row.builtin() ? 1 : 0,
                row.listHeader(), row.listItem(), row.listEmpty(), row.listLimit());
    }

    public int delete(String path) {
        return jdbc.update("DELETE FROM bot_board WHERE path = ? AND builtin = 0", path);
    }

    /**
     * 改板块名，并把 {@code bot_record} 里引用旧名的行一起迁走。
     *
     * <p>为什么跨表：记录按板块名归属。只改配置、不迁记录，历史数据会变成
     * 一个谁都查不到的孤儿。两步必须在同一个事务里，不能出现"板改了、记录没改"。
     */
    public boolean rename(String oldPath, String newPath) {
        return jdbc.transaction(() -> {
            int board = jdbc.update(
                    "UPDATE bot_board SET path = ? WHERE path = ? AND builtin = 0",
                    newPath, oldPath);
            if (board == 0) {
                return false;
            }
            jdbc.update("UPDATE bot_record SET board = ? WHERE board = ?", newPath, oldPath);
            return true;
        });
    }
}
