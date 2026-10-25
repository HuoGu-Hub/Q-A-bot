package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 蒸馏笔记与来源、图片关联的数据访问。
 *
 * <p>笔记是"模型产出"与"知识库块"之间的一层：先落笔记，发布时再写块。
 * 这样模型失败不会留下半个知识库块，发布失败也能从草稿重试。
 */
@Repository
public class DistillNoteRepository {

    /** 一条蒸馏笔记 */
    public record Note(long id, long jobId, long batchId, String title, String titleNorm,
                       String aliases, String body, String category, double confidence,
                       String status, String blockId, long duplicateOf, String model,
                       String promptVersion, String bodyHash, String createdAt,
                       String updatedAt, String reviewedAt) {
    }

    /** 来源快照：原始消息可能被清理，笔记仍能追溯 */
    public record Source(long noteId, long messageId, String ts, String senderName, String text) {
    }

    /** 待写入的笔记 */
    public record NewNote(long jobId, long batchId, String title, String titleNorm, String aliases,
                          String body, String category, double confidence, String status,
                          String model, String promptVersion, String bodyHash, String createdAt) {
    }

    /** 写入结果：id 与是否真的新增。title_norm / body_hash 唯一，重复不会写两次 */
    public record Inserted(long id, boolean created) {
    }

    private static final String COLUMNS =
            "id, job_id, batch_id, title, title_norm, aliases, body, category, confidence,"
                    + " status, block_id, duplicate_of, model, prompt_version, body_hash,"
                    + " created_at, updated_at, reviewed_at";

    private static final Jdbc.RowMapper<Note> NOTE = r -> new Note(
            r.longOf("id"), r.longOf("job_id"), r.longOf("batch_id"), r.str("title"),
            r.str("title_norm"), r.str("aliases"), r.str("body"), r.str("category"),
            r.dbl("confidence"), r.str("status"), r.str("block_id"), r.longOf("duplicate_of"),
            r.str("model"), r.str("prompt_version"), r.str("body_hash"),
            r.str("created_at"), r.str("updated_at"), r.str("reviewed_at"));

    private static final Jdbc.RowMapper<Source> SOURCE = r -> new Source(
            r.longOf("note_id"), r.longOf("message_id"), r.str("ts"),
            r.str("sender_name"), r.str("text"));

    private final Jdbc jdbc;

    public DistillNoteRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS distill_note ("
                + " id             INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " job_id         INTEGER NOT NULL DEFAULT 0,"
                + " batch_id       INTEGER NOT NULL DEFAULT 0,"
                + " title          TEXT NOT NULL,"
                + " title_norm     TEXT NOT NULL DEFAULT '',"
                + " aliases        TEXT NOT NULL DEFAULT '',"
                + " body           TEXT NOT NULL,"
                + " category       TEXT NOT NULL DEFAULT '',"
                + " confidence     REAL NOT NULL DEFAULT 0,"
                + " status         TEXT NOT NULL DEFAULT 'draft',"
                + " block_id       TEXT NOT NULL DEFAULT '',"
                + " duplicate_of   INTEGER NOT NULL DEFAULT 0,"
                + " model          TEXT NOT NULL DEFAULT '',"
                + " prompt_version TEXT NOT NULL DEFAULT '',"
                + " body_hash      TEXT NOT NULL DEFAULT '',"
                + " created_at     TEXT NOT NULL,"
                + " updated_at     TEXT NOT NULL,"
                + " reviewed_at    TEXT NOT NULL DEFAULT '')");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_distill_note_title"
                + " ON distill_note(title_norm) WHERE title_norm <> ''");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_distill_note_body"
                + " ON distill_note(body_hash) WHERE body_hash <> ''");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_distill_note_status ON distill_note(status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_distill_note_job ON distill_note(job_id)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS distill_note_source ("
                + " note_id     INTEGER NOT NULL,"
                + " message_id  INTEGER NOT NULL,"
                + " ts          TEXT NOT NULL DEFAULT '',"
                + " sender_name TEXT NOT NULL DEFAULT '',"
                + " text        TEXT NOT NULL DEFAULT '',"
                + " PRIMARY KEY (note_id, message_id))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS distill_note_image ("
                + " note_id       INTEGER NOT NULL,"
                + " chat_image_id INTEGER NOT NULL,"
                + " kb_name       TEXT NOT NULL DEFAULT '',"
                + " PRIMARY KEY (note_id, chat_image_id))");
    }

    /**
     * 写一条笔记与来源、图片关联，一个事务。
     *
     * <p>标题/正文哈希唯一：同一批重复产出、或同一知识被不同批次再次总结，
     * 都不会写第二行。
     */
    public Inserted insert(NewNote n, List<Source> sources, List<Long> imageIds) {
        return jdbc.transaction(() -> {
            int changed = jdbc.update("INSERT OR IGNORE INTO distill_note"
                            + " (job_id, batch_id, title, title_norm, aliases, body, category,"
                            + " confidence, status, block_id, duplicate_of, model, prompt_version,"
                            + " body_hash, created_at, updated_at, reviewed_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,'',0,?,?,?,?,?, '')",
                    n.jobId(), n.batchId(), n.title(), n.titleNorm(), n.aliases(), n.body(),
                    n.category(), n.confidence(), n.status(), n.model(), n.promptVersion(),
                    n.bodyHash(), n.createdAt(), n.createdAt());
            Note existing = changed > 0 ? null : findDuplicate(n.titleNorm(), n.bodyHash());
            long id = changed > 0 ? jdbc.queryOne("SELECT id FROM distill_note WHERE body_hash = ?",
                    r -> r.longOf("id"), n.bodyHash()) : (existing == null ? 0 : existing.id());
            if (id <= 0) {
                throw new IllegalStateException("写入蒸馏笔记失败");
            }
            if (changed > 0) {
                for (Source s : sources == null ? List.<Source>of() : sources) {
                    jdbc.update("INSERT OR IGNORE INTO distill_note_source"
                                    + " (note_id, message_id, ts, sender_name, text) VALUES (?,?,?,?,?)",
                            id, s.messageId(), s.ts(), s.senderName(), s.text());
                }
                for (Long imageId : imageIds == null ? List.<Long>of() : imageIds) {
                    if (imageId != null && imageId > 0) {
                        jdbc.update("INSERT OR IGNORE INTO distill_note_image"
                                + " (note_id, chat_image_id, kb_name) VALUES (?,?,'')", id, imageId);
                    }
                }
            }
            return new Inserted(id, changed > 0);
        });
    }

    private Note findDuplicate(String titleNorm, String bodyHash) {
        Note byTitle = titleNorm == null || titleNorm.isBlank() ? null
                : jdbc.queryOne("SELECT " + COLUMNS + " FROM distill_note WHERE title_norm = ?",
                NOTE, titleNorm);
        return byTitle != null ? byTitle : jdbc.queryOne(
                "SELECT " + COLUMNS + " FROM distill_note WHERE body_hash = ?", NOTE, bodyHash);
    }

    public Note find(long id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM distill_note WHERE id = ?", NOTE, id);
    }

    public List<Note> list(String status, long jobId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM distill_note WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (jobId > 0) {
            sql.append(" AND job_id = ?");
            args.add(jobId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(Math.min(Math.max(1, limit), 500));
        return jdbc.query(sql.toString(), NOTE, args.toArray());
    }

    public List<Source> sourcesOf(long noteId) {
        return jdbc.query("SELECT * FROM distill_note_source WHERE note_id = ? ORDER BY message_id",
                SOURCE, noteId);
    }

    public List<Long> imageIdsOf(long noteId) {
        return jdbc.query("SELECT chat_image_id FROM distill_note_image WHERE note_id = ?",
                r -> r.longOf("chat_image_id"), noteId);
    }

    public boolean updateContent(long id, String title, String titleNorm, String aliases,
                                 String body, String bodyHash, String category, double confidence) {
        return jdbc.update("UPDATE distill_note SET title = ?, title_norm = ?, aliases = ?,"
                        + " body = ?, body_hash = ?, category = ?, confidence = ?, updated_at = ?"
                        + " WHERE id = ?",
                title, titleNorm, aliases, body, bodyHash, category, confidence,
                java.time.Instant.now().toString(), id) > 0;
    }

    public boolean updateStatus(long id, String status, String blockId) {
        return jdbc.update("UPDATE distill_note SET status = ?, block_id = ?, updated_at = ?,"
                        + " reviewed_at = ? WHERE id = ?",
                status, blockId == null ? "" : blockId, java.time.Instant.now().toString(),
                java.time.Instant.now().toString(), id) > 0;
    }

    public boolean delete(long id) {
        return jdbc.transaction(() -> {
            jdbc.update("DELETE FROM distill_note_source WHERE note_id = ?", id);
            jdbc.update("DELETE FROM distill_note_image WHERE note_id = ?", id);
            return jdbc.update("DELETE FROM distill_note WHERE id = ?", id) > 0;
        });
    }

    public long count() {
        return jdbc.count("SELECT COUNT(*) FROM distill_note");
    }

    public long countByStatus(String status) {
        return jdbc.count("SELECT COUNT(*) FROM distill_note WHERE status = ?", status);
    }
}
