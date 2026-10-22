package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 命令别名表（{@code bot_system_alias}）。
 *
 * <p>别名分两类：command 指向完整命令路径，action 指向全局动作词。
 * 动作词只存一份，/prompt /新建 和 /预约 /新建 共用它，不需要按模块重复配置。
 */
@Repository
public class SystemAliasRepository {

    public record Row(String kind, String target, String alias) {
    }

    private static final Jdbc.RowMapper<Row> MAP =
            r -> new Row(r.str("kind"), r.str("target"), r.str("alias"));

    private final Jdbc jdbc;

    public SystemAliasRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bot_system_alias ("
                + " alias      TEXT PRIMARY KEY,"
                + " kind       TEXT NOT NULL DEFAULT 'command',"
                + " target     TEXT NOT NULL,"
                + " updated_at TEXT NOT NULL"
                + " )");
        addColumnIfMissing("kind", "TEXT NOT NULL DEFAULT 'command'");
    }

    private void addColumnIfMissing(String column, String ddl) {
        boolean exists = jdbc.query("PRAGMA table_info(bot_system_alias)", r -> r.str("name")).stream()
                .anyMatch(n -> column.equalsIgnoreCase(n));
        if (exists) {
            return;
        }
        jdbc.execute("ALTER TABLE bot_system_alias ADD COLUMN " + column + " " + ddl);
    }

    public List<Row> list() {
        return jdbc.query("SELECT alias, kind, target FROM bot_system_alias"
                + " ORDER BY kind, target, alias", MAP);
    }

    public void replaceAll(List<Row> rows, String now) {
        jdbc.transaction(() -> {
            jdbc.update("DELETE FROM bot_system_alias");
            for (Row row : rows) {
                jdbc.update("INSERT INTO bot_system_alias(alias, kind, target, updated_at)"
                        + " VALUES(?,?,?,?)", row.alias(), row.kind(), row.target(), now);
            }
            return null;
        });
    }
}
