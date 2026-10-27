package com.example.qqbot.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 蒸馏禁学名单。
 *
 * <p>按 sender_hash 屏蔽，不用昵称：群友可以改名，AI 也会换显示名；
 * hash 由群号 + QQ 号生成，改名不影响。消息仍落库，只是不进入蒸馏查询。
 */
@Repository
public class DistillBlocklistRepository {

    public record Blocked(String senderHash, String senderId, String senderName,
                          long messageCount, String createdAt) {
    }

    public record Sender(String senderHash, String senderId, String senderName,
                         long messageCount, boolean blocked) {
    }

    private static final Jdbc.RowMapper<Blocked> BLOCKED = r -> new Blocked(
            r.str("sender_hash"), r.str("sender_id"), r.str("sender_name"),
            r.longOf("message_count"), r.str("created_at"));

    private final Jdbc jdbc;

    public DistillBlocklistRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        initSchema();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS distill_blocked_sender ("
                + " sender_hash TEXT PRIMARY KEY,"
                + " sender_id   TEXT NOT NULL DEFAULT '',"
                + " sender_name TEXT NOT NULL DEFAULT '',"
                + " created_at  TEXT NOT NULL)");
        boolean hasSenderId = jdbc.query("PRAGMA table_info(distill_blocked_sender)",
                r -> r.str("name")).contains("sender_id");
        if (!hasSenderId) {
            jdbc.execute("ALTER TABLE distill_blocked_sender ADD COLUMN sender_id TEXT NOT NULL DEFAULT ''");
        }
    }

    public List<Blocked> list() {
        return jdbc.query("SELECT b.sender_hash, b.sender_id, b.sender_name, b.created_at,"
                        + " (SELECT COUNT(*) FROM chat_message m WHERE"
                        + " (b.sender_id <> '' AND m.sender_id = b.sender_id)"
                        + " OR (b.sender_id = '' AND m.sender_hash = b.sender_hash))"
                        + " AS message_count"
                        + " FROM distill_blocked_sender b ORDER BY b.created_at DESC",
                BLOCKED);
    }

    /** 最近发言的群成员，按消息量倒序；给页面选人用 */
    public List<Sender> senders(int limit) {
        return jdbc.query("SELECT m.sender_hash, MAX(m.sender_id) AS sender_id,"
                        + " MAX(m.sender_name) AS sender_name, COUNT(*) AS message_count,"
                        + " EXISTS(SELECT 1 FROM distill_blocked_sender b WHERE"
                        + " (b.sender_id <> '' AND b.sender_id = m.sender_id)"
                        + " OR (b.sender_id = '' AND b.sender_hash = m.sender_hash)) AS blocked"
                        + " FROM chat_message m WHERE m.sender_hash <> ''"
                        + " GROUP BY m.sender_hash ORDER BY message_count DESC LIMIT ?",
                r -> new Sender(r.str("sender_hash"), r.str("sender_id"), r.str("sender_name"),
                        r.longOf("message_count"), r.intOf("blocked") != 0),
                Math.min(Math.max(1, limit), 500));
    }

    public void add(String senderHash, String senderName) {
        if (senderHash == null || senderHash.isBlank()) {
            throw new IllegalArgumentException("senderHash 不能为空");
        }
        jdbc.update("INSERT INTO distill_blocked_sender(sender_hash, sender_id, sender_name, created_at)"
                        + " VALUES (?,?,'',?) ON CONFLICT(sender_hash) DO UPDATE SET"
                        + " sender_name = excluded.sender_name",
                senderHash.strip(), senderName == null ? "" : senderName.strip(),
                Instant.now().toString());
    }

    /** 按 QQ 号屏蔽：写入 sender_id 记录，并把已知群里的历史消息补上 sender_id */
    public int blockQq(String qq, String senderName) {
        String id = qq == null ? "" : qq.strip();
        if (id.isEmpty() || !id.matches("\\d{5,12}")) {
            throw new IllegalArgumentException("QQ 号格式不正确");
        }
        int updated = 0;
        for (Long groupId : jdbc.query("SELECT DISTINCT group_id FROM chat_message WHERE group_id > 0",
                r -> r.longOf("group_id"))) {
            updated += jdbc.update("UPDATE chat_message SET sender_id = ?"
                    + " WHERE group_id = ? AND sender_hash = ?",
                    id, groupId, senderHash(groupId, id));
        }
        jdbc.update("INSERT INTO distill_blocked_sender(sender_hash, sender_id, sender_name, created_at)"
                        + " VALUES (?,?,?,?) ON CONFLICT(sender_hash) DO UPDATE SET"
                        + " sender_id = excluded.sender_id, sender_name = excluded.sender_name",
                "qq:" + id, id, senderName == null ? "" : senderName.strip(),
                Instant.now().toString());
        return updated;
    }

    public boolean remove(String senderHash) {
        return jdbc.update("DELETE FROM distill_blocked_sender WHERE sender_hash = ?",
                senderHash == null ? "" : senderHash.strip()) > 0;
    }

    public boolean removeQq(String qq) {
        return jdbc.update("DELETE FROM distill_blocked_sender WHERE sender_id = ?",
                qq == null ? "" : qq.strip()) > 0;
    }

    private static String senderHash(long groupId, String qq) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest((groupId + ":" + qq).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算 senderHash 失败", e);
        }
    }
}
