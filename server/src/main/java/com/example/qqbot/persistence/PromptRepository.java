package com.example.qqbot.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 补充提示表（{@code bot_prompt}）。
 *
 * <p>一条提示要么属于一个群，要么属于一个 QQ 号：
 * 群提示对群内所有人生效，个人提示只对那个人生效。
 * 持久层只存归属与原文，怎么校验权限由业务侧决定。
 */
@Repository
public class PromptRepository {

    private static final Logger log = LoggerFactory.getLogger(PromptRepository.class);

    public record Row(long id, String scope, long groupId, long userId,
                      String content, boolean enabled, String createdAt) {
    }

    private static final String COLUMNS =
            "id, scope, group_id, user_id, content, enabled, created_at";

    private static final Jdbc.RowMapper<Row> MAP = r -> new Row(
            r.longOf("id"), r.str("scope"), r.longOf("group_id"), r.longOf("user_id"),
            r.str("content"), r.intOf("enabled") == 1, r.str("created_at"));

    private final Jdbc jdbc;

    public PromptRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bot_prompt ("
                + " id         INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " scope      TEXT NOT NULL DEFAULT 'group',"
                + " group_id   INTEGER NOT NULL DEFAULT 0,"
                + " user_id    INTEGER NOT NULL DEFAULT 0,"
                + " content    TEXT NOT NULL,"
                + " enabled    INTEGER NOT NULL DEFAULT 1,"
                + " created_at TEXT NOT NULL"
                + " )");
        // 老库升级：原先只有 user_id，补上 scope/group_id
        addColumnIfMissing("scope", "TEXT NOT NULL DEFAULT 'user'");
        addColumnIfMissing("group_id", "INTEGER NOT NULL DEFAULT 0");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_prompt_scope ON bot_prompt(scope, group_id, user_id)");
    }

    private void addColumnIfMissing(String column, String ddl) {
        boolean exists = jdbc.query("PRAGMA table_info(bot_prompt)", r -> r.str("name")).stream()
                .anyMatch(n -> column.equalsIgnoreCase(n));
        if (exists) {
            return;
        }
        jdbc.execute("ALTER TABLE bot_prompt ADD COLUMN " + column + " " + ddl);
        log.info("[PROMPT] bot_prompt 补列：{} {}", column, ddl);
    }

    /** 当前群提示 + 当前用户提示 */
    public List<Row> listFor(long groupId, long userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_prompt"
                + " WHERE enabled = 1 AND ((scope = 'group' AND group_id = ?)"
                + " OR (scope = 'user' AND user_id = ?))"
                + " ORDER BY CASE scope WHEN 'group' THEN 1 ELSE 2 END, id",
                MAP, groupId, userId);
    }

    /** 后台管理页用：全部提示，群在前、个人在后 */
    public List<Row> listAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_prompt"
                + " WHERE enabled = 1"
                + " ORDER BY CASE scope WHEN 'group' THEN 1 ELSE 2 END, group_id, user_id, id", MAP);
    }

    public Row find(long id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM bot_prompt WHERE id = ?", MAP, id);
    }

    public long count(String scope, long groupId, long userId) {
        return jdbc.count("SELECT COUNT(*) FROM bot_prompt"
                + " WHERE enabled = 1 AND scope = ? AND group_id = ? AND user_id = ?",
                scope, groupId, userId);
    }

    public long insert(String scope, long groupId, long userId, String content, String createdAt) {
        return jdbc.insert("INSERT INTO bot_prompt"
                        + " (scope, group_id, user_id, content, enabled, created_at)"
                        + " VALUES (?,?,?,?,1,?)",
                scope, groupId, userId, content, createdAt);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM bot_prompt WHERE id = ?", id);
    }

    public int deleteGroup(long groupId) {
        return jdbc.update("DELETE FROM bot_prompt WHERE scope = 'group' AND group_id = ?", groupId);
    }

    public int deleteUser(long userId) {
        return jdbc.update("DELETE FROM bot_prompt WHERE scope = 'user' AND user_id = ?", userId);
    }
}
