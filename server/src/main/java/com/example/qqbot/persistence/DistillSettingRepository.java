package com.example.qqbot.persistence;

import com.example.qqbot.distill.DistillPolicy;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * 自动蒸馏设置与游标。单行表，id 固定为 1。
 *
 * <p>为什么放库里而不是 yml：触发条数是使用中会反复调的东西，
 * 每改一次就重启服务不现实；游标也必须跟任务一起持久化。
 */
@Repository
public class DistillSettingRepository {

    public record Settings(boolean autoEnabled, int triggerMessages, int triggerImages,
                           int batchMessages, int batchChars, int overlap, int maxGapMinutes,
                           boolean autoPublish, double minConfidence, String focus,
                           String modelProvider, long lastMessageId, String updatedAt) {
    }

    private static final Jdbc.RowMapper<Settings> MAPPER = r -> new Settings(
            r.intOf("auto_enabled") != 0,
            r.intOf("trigger_messages"), r.intOf("trigger_images"),
            r.intOf("batch_messages"), r.intOf("batch_chars"),
            r.intOf("overlap"), r.intOf("max_gap_minutes"),
            r.intOf("auto_publish") != 0, r.dbl("min_confidence"), r.str("focus"),
            r.str("model_provider"), r.longOf("last_message_id"), r.str("updated_at"));

    private final Jdbc jdbc;
    private final DistillPolicy policy;

    public DistillSettingRepository(Jdbc jdbc, DistillPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS distill_setting ("
                + " id               INTEGER PRIMARY KEY CHECK (id = 1),"
                + " auto_enabled     INTEGER NOT NULL DEFAULT 0,"
                + " trigger_messages INTEGER NOT NULL DEFAULT 100,"
                + " trigger_images   INTEGER NOT NULL DEFAULT 10,"
                + " batch_messages   INTEGER NOT NULL DEFAULT 80,"
                + " batch_chars      INTEGER NOT NULL DEFAULT 10000,"
                + " overlap          INTEGER NOT NULL DEFAULT 5,"
                + " max_gap_minutes  INTEGER NOT NULL DEFAULT 30,"
                + " auto_publish     INTEGER NOT NULL DEFAULT 1,"
                + " min_confidence   REAL NOT NULL DEFAULT 0.75,"
                + " focus            TEXT NOT NULL DEFAULT '',"
                + " model_provider   TEXT NOT NULL DEFAULT '',"
                + " last_message_id  INTEGER NOT NULL DEFAULT 0,"
                + " updated_at       TEXT NOT NULL)");
        // 旧库补列：表已经存在时 CREATE IF NOT EXISTS 不会加字段
        boolean hasFocus = jdbc.query("PRAGMA table_info(distill_setting)",
                r -> r.str("name")).contains("focus");
        if (!hasFocus) {
            jdbc.execute("ALTER TABLE distill_setting ADD COLUMN focus TEXT NOT NULL DEFAULT ''");
        }
        boolean hasProvider = jdbc.query("PRAGMA table_info(distill_setting)",
                r -> r.str("name")).contains("model_provider");
        if (!hasProvider) {
            jdbc.execute("ALTER TABLE distill_setting ADD COLUMN model_provider TEXT NOT NULL DEFAULT ''");
        }
        jdbc.update("INSERT OR IGNORE INTO distill_setting"
                        + " (id, auto_enabled, trigger_messages, trigger_images, batch_messages,"
                        + " batch_chars, overlap, max_gap_minutes, auto_publish, min_confidence,"
                        + " last_message_id, updated_at)"
                        + " VALUES (1, 0, 100, 10, ?, ?, ?, ?, ?, ?,"
                        + " COALESCE((SELECT MAX(id) FROM chat_message),0), ?)",
                policy.getBatchMaxMessages(), policy.getBatchMaxChars(), policy.getBatchOverlap(),
                policy.getBatchMaxGapMinutes(), policy.isAutoPublish() ? 1 : 0,
                policy.getMinConfidence(), Instant.now().toString());
    }

    public Settings getOrCreate() {
        Settings s = jdbc.queryOne("SELECT * FROM distill_setting WHERE id = 1", MAPPER);
        if (s != null) {
            return s;
        }
        initSchema();
        return jdbc.queryOne("SELECT * FROM distill_setting WHERE id = 1", MAPPER);
    }

    public Settings save(Settings s) {
        jdbc.update("UPDATE distill_setting SET auto_enabled=?, trigger_messages=?, trigger_images=?,"
                        + " batch_messages=?, batch_chars=?, overlap=?, max_gap_minutes=?,"
                        + " auto_publish=?, min_confidence=?, focus=?, model_provider=?,"
                        + " updated_at=? WHERE id=1",
                s.autoEnabled() ? 1 : 0, s.triggerMessages(), s.triggerImages(),
                s.batchMessages(), s.batchChars(), s.overlap(), s.maxGapMinutes(),
                s.autoPublish() ? 1 : 0, s.minConfidence(), s.focus() == null ? "" : s.focus(),
                s.modelProvider() == null ? "" : s.modelProvider(), Instant.now().toString());
        return getOrCreate();
    }

    public void updateCursor(long messageId) {
        jdbc.update("UPDATE distill_setting SET last_message_id=?, updated_at=? WHERE id=1",
                Math.max(0, messageId), Instant.now().toString());
    }

}
