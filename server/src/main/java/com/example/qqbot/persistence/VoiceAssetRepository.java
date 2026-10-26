package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 语音素材的**数据访问** —— SQL 只在这里。
 *
 * <p>文件在磁盘、元数据在库；两边靠 {@code file} 关联。
 * 业务侧（{@code voice.VoiceLibrary}）负责校验、落盘与挑选，这里只管存与取。
 */
@Repository
public class VoiceAssetRepository {

    /** 一行素材 */
    public record Row(long id, String title, String aliases, String tags, String file, String mime,
                      long sizeBytes, long durationMs, boolean enabled, String createdAt) {
    }

    private static final String COLS = "SELECT id, title, aliases, tags, file, mime, size_bytes,"
            + " duration_ms, enabled, created_at FROM voice_asset";

    private final Jdbc jdbc;

    public VoiceAssetRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS voice_asset ("
                + " id          INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " title       TEXT NOT NULL DEFAULT '',"
                + " aliases     TEXT NOT NULL DEFAULT '',"
                + " tags        TEXT NOT NULL DEFAULT '',"
                + " file        TEXT NOT NULL,"
                + " mime        TEXT NOT NULL DEFAULT 'audio/mpeg',"
                + " size_bytes  INTEGER NOT NULL DEFAULT 0,"
                + " duration_ms INTEGER NOT NULL DEFAULT 0,"
                + " enabled     INTEGER NOT NULL DEFAULT 1,"
                + " created_at  TEXT NOT NULL DEFAULT '')");
        // 同一个文件只登记一次：上传重复内容时直接返回已有行
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_voice_asset_file ON voice_asset(file)");
    }

    public List<Row> all() {
        return jdbc.query(COLS + " ORDER BY id DESC", VoiceAssetRepository::read);
    }

    public Row byId(long id) {
        return jdbc.queryOne(COLS + " WHERE id = ?", VoiceAssetRepository::read, id);
    }

    public Row byFile(String file) {
        return jdbc.queryOne(COLS + " WHERE file = ?", VoiceAssetRepository::read, file);
    }

    public int count() {
        return (int) jdbc.count("SELECT COUNT(*) FROM voice_asset");
    }

    public long insert(String title, String aliases, String tags, String file, String mime,
                       long sizeBytes, long durationMs, String createdAt) {
        return jdbc.insert("INSERT INTO voice_asset"
                        + " (title, aliases, tags, file, mime, size_bytes, duration_ms, enabled, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,1,?)",
                title, aliases, tags, file, mime, sizeBytes, durationMs, createdAt);
    }

    public void update(long id, String title, String aliases, String tags, boolean enabled) {
        jdbc.update("UPDATE voice_asset SET title = ?, aliases = ?, tags = ?, enabled = ? WHERE id = ?",
                title, aliases, tags, enabled ? 1 : 0, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM voice_asset WHERE id = ?", id);
    }

    private static Row read(Jdbc.Row r) {
        return new Row(r.longOf("id"), r.str("title"), r.str("aliases"), r.str("tags"),
                r.str("file"), r.str("mime"), r.longOf("size_bytes"), r.longOf("duration_ms"),
                r.bool("enabled"), r.str("created_at"));
    }
}
