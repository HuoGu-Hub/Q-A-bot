package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 个人补充提示表（{@code bot_prompt}）。
 *
 * <p>每个人的提示只影响自己：它不写进 system.md，也不在用户之间共享。
 * 这里只存原文与归属，拼接格式由 {@code PromptStore} 决定。
 */
@Repository
public class PromptRepository {

    public record Row(long id, long userId, String content, boolean enabled, String createdAt) {
    }

    private static final String COLUMNS = "id, user_id, content, enabled, created_at";

    private static final Jdbc.RowMapper<Row> MAP = r -> new Row(
            r.longOf("id"), r.longOf("user_id"), r.str("content"),
            r.intOf("enabled") == 1, r.str("created_at"));

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
                + " user_id    INTEGER NOT NULL,"
                + " content    TEXT NOT NULL,"
                + " enabled    INTEGER NOT NULL DEFAULT 1,"
                + " created_at TEXT NOT NULL"
                + " )");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_prompt_user ON bot_prompt(user_id)");
    }

    public List<Row> listEnabled(long userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_prompt"
                + " WHERE user_id = ? AND enabled = 1 ORDER BY id", MAP, userId);
    }

    public long countEnabled(long userId) {
        return jdbc.count("SELECT COUNT(*) FROM bot_prompt WHERE user_id = ? AND enabled = 1", userId);
    }

    public long insert(long userId, String content, String createdAt) {
        return jdbc.insert("INSERT INTO bot_prompt (user_id, content, enabled, created_at)"
                + " VALUES (?,?,1,?)", userId, content, createdAt);
    }

    public int delete(long id, long userId) {
        return jdbc.update("DELETE FROM bot_prompt WHERE id = ? AND user_id = ?", id, userId);
    }

    public int deleteAll(long userId) {
        return jdbc.update("DELETE FROM bot_prompt WHERE user_id = ?", userId);
    }
}
