package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 原始聊天消息、聊天图片与导入记录的数据访问。
 *
 * <h2>为什么单独一张消息表</h2>
 * 现有的 {@code qa_raw} 只存"机器人回答过的那一次问答"，不是完整群聊。
 * 蒸馏需要的是群友之间的上下文，所以必须有自己的一张原文表。
 *
 * <p>消息以 {@code raw_hash} 去重：协议端重复上报、文件重复导入都不会写两次。
 */
@Repository
public class ChatRepository {

    /** 一条群聊消息 */
    public record Message(long id, String source, long importId, long groupId, long messageId,
                          String ts, String senderHash, String senderName, String text,
                          int imageCount, String rawHash, String createdAt) {
    }

    /** 一张聊天图片 */
    public record Image(long id, long messageId, String md5, String path, String mime,
                        int bytes, String caption, String createdAt) {
    }

    /** 一次聊天文件导入 */
    public record ImportRow(long id, String filename, String format, long groupId,
                            int messageCount, String status, String error, String createdAt) {
    }

    /** 待写入的消息 */
    public record NewMessage(String source, long importId, long groupId, long messageId,
                             String ts, String senderHash, String senderName, String text,
                             int imageCount, String rawHash, String createdAt) {
    }

    /** 待写入的图片 */
    public record NewImage(long messageId, String md5, String path, String mime,
                           int bytes, String caption, String createdAt) {
    }

    /** 分批只需要这几个字段，不要把正文整段取出来 */
    public record MessageRef(long id, String ts, int chars, int imageCount) {
    }

    /** 一次任务最多取多少条消息，防止把整库拖进内存 */
    public static final int MAX_JOB_MESSAGES = 20_000;

    private static final String MESSAGE_COLUMNS =
            "id, source, import_id, group_id, message_id, ts, sender_hash, sender_name,"
                    + " text, image_count, raw_hash, created_at";

    private static final Jdbc.RowMapper<Message> MESSAGE = r -> new Message(
            r.longOf("id"), r.str("source"), r.longOf("import_id"), r.longOf("group_id"),
            r.longOf("message_id"), r.str("ts"), r.str("sender_hash"), r.str("sender_name"),
            r.str("text"), r.intOf("image_count"), r.str("raw_hash"), r.str("created_at"));

    private static final Jdbc.RowMapper<Image> IMAGE = r -> new Image(
            r.longOf("id"), r.longOf("message_id"), r.str("md5"), r.str("path"),
            r.str("mime"), r.intOf("bytes"), r.str("caption"), r.str("created_at"));

    private static final Jdbc.RowMapper<ImportRow> IMPORT = r -> new ImportRow(
            r.longOf("id"), r.str("filename"), r.str("format"), r.longOf("group_id"),
            r.intOf("message_count"), r.str("status"), r.str("error"), r.str("created_at"));

    private final Jdbc jdbc;

    public ChatRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_message ("
                + " id          INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " source      TEXT NOT NULL DEFAULT 'live',"
                + " import_id   INTEGER NOT NULL DEFAULT 0,"
                + " group_id    INTEGER NOT NULL DEFAULT 0,"
                + " message_id  INTEGER NOT NULL DEFAULT 0,"
                + " ts          TEXT NOT NULL,"
                + " sender_hash TEXT NOT NULL DEFAULT '',"
                + " sender_name TEXT NOT NULL DEFAULT '',"
                + " text        TEXT NOT NULL DEFAULT '',"
                + " image_count INTEGER NOT NULL DEFAULT 0,"
                + " raw_hash    TEXT NOT NULL,"
                + " created_at  TEXT NOT NULL)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_msg_hash ON chat_message(raw_hash)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_msg_group_ts ON chat_message(group_id, ts)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_msg_import ON chat_message(import_id)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_image ("
                + " id         INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " message_id INTEGER NOT NULL,"
                + " md5        TEXT NOT NULL DEFAULT '',"
                + " path       TEXT NOT NULL,"
                + " mime       TEXT NOT NULL DEFAULT '',"
                + " bytes      INTEGER NOT NULL DEFAULT 0,"
                + " caption    TEXT NOT NULL DEFAULT '',"
                + " created_at TEXT NOT NULL)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_img_msg_md5"
                + " ON chat_image(message_id, md5)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_img_md5 ON chat_image(md5)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS distill_import ("
                + " id            INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " filename      TEXT NOT NULL DEFAULT '',"
                + " format        TEXT NOT NULL DEFAULT '',"
                + " group_id      INTEGER NOT NULL DEFAULT 0,"
                + " message_count INTEGER NOT NULL DEFAULT 0,"
                + " status        TEXT NOT NULL DEFAULT 'done',"
                + " error         TEXT NOT NULL DEFAULT '',"
                + " created_at    TEXT NOT NULL)");
    }

    /** 写入结果：id 与是否真的新增 */
    public record Inserted(long id, boolean created) {
    }

    /**
     * 写入一条消息；已经存在时返回已有 id。
     *
     * <p>用 {@code INSERT OR IGNORE} + 再查一次，而不是先查再插：
     * 两个采集线程同时写同一条时，唯一索引是唯一的裁判。
     */
    public Inserted insertMessageIfAbsent(NewMessage m) {
        int n = jdbc.update("INSERT OR IGNORE INTO chat_message"
                        + " (source, import_id, group_id, message_id, ts, sender_hash, sender_name,"
                        + " text, image_count, raw_hash, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                m.source(), m.importId(), m.groupId(), m.messageId(), m.ts(), m.senderHash(),
                m.senderName(), m.text(), m.imageCount(), m.rawHash(), m.createdAt());
        Long id = jdbc.queryOne("SELECT id FROM chat_message WHERE raw_hash = ?",
                r -> r.longOf("id"), m.rawHash());
        return new Inserted(id == null ? 0 : id, n > 0);
    }

    public void insertImage(NewImage i) {
        jdbc.update("INSERT OR IGNORE INTO chat_image"
                        + " (message_id, md5, path, mime, bytes, caption, created_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                i.messageId(), i.md5(), i.path(), i.mime(), i.bytes(), i.caption(), i.createdAt());
    }

    public long insertImport(String filename, String format, long groupId,
                             int messageCount, String status, String error, String createdAt) {
        return jdbc.insert("INSERT INTO distill_import"
                        + " (filename, format, group_id, message_count, status, error, created_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                filename, format, groupId, messageCount, status, error, createdAt);
    }

    public Message findMessage(long id) {
        return jdbc.queryOne("SELECT " + MESSAGE_COLUMNS + " FROM chat_message WHERE id = ?",
                MESSAGE, id);
    }

    public List<Message> recentMessages(int limit) {
        int capped = Math.min(Math.max(1, limit), 500);
        return jdbc.query("SELECT " + MESSAGE_COLUMNS
                + " FROM chat_message ORDER BY id DESC LIMIT ?", MESSAGE, capped);
    }

    public List<Image> imagesOfMessage(long messageId) {
        return jdbc.query("SELECT * FROM chat_image WHERE message_id = ? ORDER BY id", IMAGE, messageId);
    }

    public long maxMessageId() {
        return jdbc.count("SELECT COALESCE(MAX(id),0) FROM chat_message");
    }

    /** 游标之后还有多少条待蒸馏消息 */
    public long countAfter(long afterId, List<Long> groupIds) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM chat_message WHERE id > ?");
        List<Object> args = new ArrayList<>();
        args.add(afterId);
        if (groupIds != null && !groupIds.isEmpty()) {
            sql.append(" AND group_id IN (");
            for (int i = 0; i < groupIds.size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
                args.add(groupIds.get(i));
            }
            sql.append(')');
        }
        return jdbc.count(sql.toString(), args.toArray());
    }

    /** 自动蒸馏用：按 id 顺序取游标之后、指定群的新消息 */
    public List<Message> messagesAfter(long afterId, List<Long> groupIds, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + MESSAGE_COLUMNS
                + " FROM chat_message WHERE id > ?");
        List<Object> args = new ArrayList<>();
        args.add(afterId);
        if (groupIds != null && !groupIds.isEmpty()) {
            sql.append(" AND group_id IN (");
            for (int i = 0; i < groupIds.size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
                args.add(groupIds.get(i));
            }
            sql.append(')');
        }
        sql.append(" ORDER BY id LIMIT ?");
        args.add(Math.min(Math.max(1, limit), 2000));
        return jdbc.query(sql.toString(), MESSAGE, args.toArray());
    }

    /** 取一个批次范围内的消息。批次按候选顺序连续切分，通常 id 也是连续的。 */
    public List<Message> messagesBetween(long fromId, long toId) {
        return jdbc.query("SELECT " + MESSAGE_COLUMNS
                + " FROM chat_message WHERE id BETWEEN ? AND ? ORDER BY ts, id",
                MESSAGE, fromId, toId);
    }

    /** 一次取多条消息的图片，避免逐条查询 */
    public List<Image> imagesOfMessages(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM chat_image WHERE message_id IN (");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < messageIds.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
            args.add(messageIds.get(i));
        }
        sql.append(") ORDER BY message_id, id");
        return jdbc.query(sql.toString(), IMAGE, args.toArray());
    }

    public Image findImage(long id) {
        return jdbc.queryOne("SELECT * FROM chat_image WHERE id = ?", IMAGE, id);
    }

    /**
     * 取一批可供分批的消息。只返回 id / 时间 / 字数 / 图片数。
     *
     * @param source   live / file
     * @param groups   空表示不按群过滤
     * @param from     空表示不限
     * @param to       空表示不限
     * @param importId source=file 时指定导入批次；0 表示不限
     */
    public List<MessageRef> candidates(String source, List<Long> groups, String from,
                                       String to, long importId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, ts, image_count, length(text) AS chars FROM chat_message WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if ("file".equalsIgnoreCase(source) && importId > 0) {
            sql.append(" AND import_id = ?");
            args.add(importId);
        }
        if (groups != null && !groups.isEmpty()) {
            sql.append(" AND group_id IN (");
            for (int i = 0; i < groups.size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
                args.add(groups.get(i));
            }
            sql.append(")");
        }
        if (from != null && !from.isBlank()) {
            sql.append(" AND ts >= ?");
            args.add(from);
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND ts <= ?");
            args.add(to);
        }
        sql.append(" ORDER BY ts, id LIMIT ?");
        args.add(MAX_JOB_MESSAGES);
        return jdbc.query(sql.toString(), r -> new MessageRef(
                r.longOf("id"), r.str("ts"), r.intOf("chars"), r.intOf("image_count")),
                args.toArray());
    }

    public int updateImport(long id, int messageCount, String status, String error) {
        return jdbc.update("UPDATE distill_import SET message_count = ?, status = ?, error = ? WHERE id = ?",
                messageCount, status, error, id);
    }

    public long countMessages() {
        return jdbc.count("SELECT COUNT(*) FROM chat_message");
    }

    public long countImages() {
        return jdbc.count("SELECT COUNT(*) FROM chat_image");
    }

    public long countImports() {
        return jdbc.count("SELECT COUNT(*) FROM distill_import");
    }

    public List<ImportRow> recentImports(int limit) {
        int capped = Math.min(Math.max(1, limit), 200);
        return jdbc.query("SELECT * FROM distill_import ORDER BY id DESC LIMIT ?", IMPORT, capped);
    }
}
