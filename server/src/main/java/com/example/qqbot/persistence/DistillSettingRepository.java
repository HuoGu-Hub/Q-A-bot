package com.example.qqbot.persistence;

import com.example.qqbot.distill.DistillPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 自动蒸馏设置与游标。单行表，id 固定为 1。
 *
 * <p>为什么放库里而不是 yml：触发条数是使用中会反复调的东西，
 * 每改一次就重启服务不现实；游标也必须跟任务一起持久化。
 */
@Repository
public class DistillSettingRepository {

    private static final Logger log = LoggerFactory.getLogger(DistillSettingRepository.class);

    /** 旧版本默认全群采集；这次迁移只执行一次，之后由管理员显式选择 */
    private static final String CAPTURE_DEFAULT_KEY = "distill.capture_all_default_off_v1";

    public record Settings(boolean autoEnabled, int triggerMessages, int triggerImages,
                           int batchMessages, int batchChars, int overlap, int maxGapMinutes,
                           boolean autoPublish, double minConfidence, String focus,
                           String modelProvider, String captureGroups,
                           boolean captureAllGroups, long lastMessageId, String updatedAt) {
    }

    private static final Jdbc.RowMapper<Settings> MAPPER = r -> new Settings(
            r.intOf("auto_enabled") != 0,
            r.intOf("trigger_messages"), r.intOf("trigger_images"),
            r.intOf("batch_messages"), r.intOf("batch_chars"),
            r.intOf("overlap"), r.intOf("max_gap_minutes"),
            r.intOf("auto_publish") != 0, r.dbl("min_confidence"), r.str("focus"),
            r.str("model_provider"), r.str("capture_groups"),
            r.intOf("capture_all_groups") != 0,
            r.longOf("last_message_id"), r.str("updated_at"));

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
                + " trigger_images   INTEGER NOT NULL DEFAULT 0,"
                + " batch_messages   INTEGER NOT NULL DEFAULT 80,"
                + " batch_chars      INTEGER NOT NULL DEFAULT 10000,"
                + " overlap          INTEGER NOT NULL DEFAULT 5,"
                + " max_gap_minutes  INTEGER NOT NULL DEFAULT 30,"
                + " auto_publish     INTEGER NOT NULL DEFAULT 1,"
                + " min_confidence   REAL NOT NULL DEFAULT 0.75,"
                + " focus            TEXT NOT NULL DEFAULT '',"
                + " model_provider   TEXT NOT NULL DEFAULT '',"
                + " capture_groups   TEXT NOT NULL DEFAULT '',"
                + " capture_all_groups INTEGER NOT NULL DEFAULT 0,"
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
        boolean hasGroups = jdbc.query("PRAGMA table_info(distill_setting)",
                r -> r.str("name")).contains("capture_groups");
        if (!hasGroups) {
            jdbc.execute("ALTER TABLE distill_setting ADD COLUMN capture_groups TEXT NOT NULL DEFAULT ''");
            // 旧库没有这一列：把 yml 里的白名单迁移成初始值，之后由页面接管
            jdbc.update("UPDATE distill_setting SET capture_groups = ? WHERE id = 1",
                    normalizeGroups(String.join(",", policy.getCaptureGroups().stream()
                            .map(String::valueOf).toList())));
        }
        boolean hasAll = jdbc.query("PRAGMA table_info(distill_setting)",
                r -> r.str("name")).contains("capture_all_groups");
        if (!hasAll) {
            jdbc.execute("ALTER TABLE distill_setting ADD COLUMN capture_all_groups INTEGER NOT NULL DEFAULT 0");
        }
        // 采集关闭时 chat_message 可能还没建；此时游标只能是 0，不能直接查表
        long lastMessageId = 0;
        if (jdbc.count("SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='table' AND name='chat_message'") > 0) {
            lastMessageId = jdbc.count("SELECT COALESCE(MAX(id),0) FROM chat_message");
        }
        jdbc.update("INSERT OR IGNORE INTO distill_setting"
                        + " (id, auto_enabled, trigger_messages, trigger_images, batch_messages,"
                        + " batch_chars, overlap, max_gap_minutes, auto_publish, min_confidence,"
                        + " capture_groups, capture_all_groups, last_message_id, updated_at)"
                        + " VALUES (1, 0, 100, 0, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                policy.getBatchMaxMessages(), policy.getBatchMaxChars(), policy.getBatchOverlap(),
                policy.getBatchMaxGapMinutes(), policy.isAutoPublish() ? 1 : 0,
                policy.getMinConfidence(),
                normalizeGroups(String.join(",", policy.getCaptureGroups().stream()
                        .map(String::valueOf).toList())),
                lastMessageId, Instant.now().toString());
        // 触发图片数不能超过单批视觉上限，否则多出来的图根本送不进模型
        int maxImages = Math.max(1, policy.getMaxImagesPerBatch());
        jdbc.update("UPDATE distill_setting SET trigger_images = ?"
                + " WHERE id = 1 AND trigger_images > ?", maxImages, maxImages);
        migrateCaptureDefaults();
    }

    /**
     * 旧版本把 {@code capture_all_groups} 默认成 1，首次启动就会采集机器人所在的所有群。
     * 这次升级强制关一次，之后管理员可以在蒸馏页重新打开；用 bot_meta 标记，避免每次重启都覆盖。
     */
    private void migrateCaptureDefaults() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bot_meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
        if (jdbc.count("SELECT COUNT(*) FROM bot_meta WHERE k = ?", CAPTURE_DEFAULT_KEY) > 0) {
            return;
        }
        int changed = jdbc.update("UPDATE distill_setting SET capture_all_groups = 0"
                + " WHERE id = 1 AND capture_all_groups <> 0");
        jdbc.update("INSERT INTO bot_meta(k, v) VALUES(?, ?)"
                + " ON CONFLICT(k) DO UPDATE SET v = excluded.v", CAPTURE_DEFAULT_KEY, "1");
        if (changed > 0) {
            log.warn("[DISTILL] 已关闭全群采集。旧默认值会保存机器人所在的所有群，"
                    + "请到蒸馏页确认采集范围后再重新开启");
        }
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
                        + " capture_groups=?, capture_all_groups=?, updated_at=? WHERE id=1",
                s.autoEnabled() ? 1 : 0, s.triggerMessages(), s.triggerImages(),
                s.batchMessages(), s.batchChars(), s.overlap(), s.maxGapMinutes(),
                s.autoPublish() ? 1 : 0, s.minConfidence(), s.focus() == null ? "" : s.focus(),
                s.modelProvider() == null ? "" : s.modelProvider(),
                s.captureGroups() == null ? "" : s.captureGroups(),
                s.captureAllGroups() ? 1 : 0, Instant.now().toString());
        return getOrCreate();
    }

    public List<Long> captureGroups() {
        return parseGroups(getOrCreate().captureGroups());
    }

    public boolean captureAllGroups() {
        return getOrCreate().captureAllGroups();
    }

    public static List<Long> parseGroups(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        for (String part : raw.split("[,，\\s]+")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) {
                    out.add(id);
                }
            } catch (NumberFormatException ignored) {
                // 页面输入里的空段或坏值直接跳过
            }
        }
        return List.copyOf(out);
    }

    public static String normalizeGroups(String raw) {
        return parseGroups(raw).stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    public void updateCursor(long messageId) {
        jdbc.update("UPDATE distill_setting SET last_message_id=?, updated_at=? WHERE id=1",
                Math.max(0, messageId), Instant.now().toString());
    }

}
