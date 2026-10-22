package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 系统命令别名表（{@code bot_system_alias}）。
 *
 * <p>只给 list / help / prompt 三个系统命令声明中文调用名；
 * 普通命令仍然走自己的 trigger，不存在"别名改命令名"的歧义。
 */
@Repository
public class SystemAliasRepository {

    public record Row(String alias, String target) {
    }

    private static final Jdbc.RowMapper<Row> MAP =
            r -> new Row(r.str("alias"), r.str("target"));

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
                + " target     TEXT NOT NULL,"
                + " updated_at TEXT NOT NULL"
                + " )");
    }

    public List<Row> list() {
        return jdbc.query("SELECT alias, target FROM bot_system_alias ORDER BY target, alias", MAP);
    }

    public void replaceAll(List<Row> rows, String now) {
        jdbc.transaction(() -> {
            jdbc.update("DELETE FROM bot_system_alias");
            for (Row row : rows) {
                jdbc.update("INSERT INTO bot_system_alias(alias, target, updated_at) VALUES(?,?,?)",
                        row.alias(), row.target(), now);
            }
            return null;
        });
    }
}
