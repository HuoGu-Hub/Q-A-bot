package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 语音触发关键词的**数据访问** —— SQL 只在这里。
 *
 * <p>一句话一行，业务侧（{@code voice.VoiceTriggerService}）负责匹配与冷却。
 */
@Repository
public class VoiceTriggerRepository {

    /** 一行规则 */
    public record Row(long id, String keyword, int cooldownSeconds, String groupScope,
                      boolean enabled, String note, String createdAt) {
    }

    private static final String COLS = "SELECT id, keyword, cooldown_seconds, group_scope,"
            + " enabled, note, created_at FROM voice_trigger";

    private final Jdbc jdbc;

    public VoiceTriggerRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS voice_trigger ("
                + " id               INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " keyword          TEXT NOT NULL,"
                + " cooldown_seconds INTEGER NOT NULL DEFAULT 120,"
                + " group_scope      TEXT NOT NULL DEFAULT '',"
                + " enabled          INTEGER NOT NULL DEFAULT 1,"
                + " note             TEXT NOT NULL DEFAULT '',"
                + " created_at       TEXT NOT NULL DEFAULT '')");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_voice_trigger_keyword ON voice_trigger(keyword)");
    }

    public List<Row> all() {
        return jdbc.query(COLS + " ORDER BY id", VoiceTriggerRepository::read);
    }

    public int count() {
        return (int) jdbc.count("SELECT COUNT(*) FROM voice_trigger");
    }

    public long insert(String keyword, int cooldownSeconds, String groupScope, String note, String createdAt) {
        return jdbc.insert("INSERT INTO voice_trigger"
                        + " (keyword, cooldown_seconds, group_scope, enabled, note, created_at)"
                        + " VALUES (?,?,?,1,?,?)",
                keyword, cooldownSeconds, groupScope, note, createdAt);
    }

    public void update(long id, int cooldownSeconds, String groupScope, String note, boolean enabled) {
        jdbc.update("UPDATE voice_trigger SET cooldown_seconds = ?, group_scope = ?,"
                + " note = ?, enabled = ? WHERE id = ?", cooldownSeconds, groupScope, note,
                enabled ? 1 : 0, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM voice_trigger WHERE id = ?", id);
    }

    private static Row read(Jdbc.Row r) {
        return new Row(r.longOf("id"), r.str("keyword"), r.intOf("cooldown_seconds"),
                r.str("group_scope"), r.bool("enabled"), r.str("note"), r.str("created_at"));
    }
}
