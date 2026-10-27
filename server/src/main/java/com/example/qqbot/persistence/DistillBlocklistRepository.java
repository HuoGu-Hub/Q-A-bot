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

    public record Blocked(String senderHash, String senderName, long messageCount, String createdAt) {
    }

    public record Sender(String senderHash, String senderName, long messageCount, boolean blocked) {
    }

    private static final Jdbc.RowMapper<Blocked> BLOCKED = r -> new Blocked(
            r.str("sender_hash"), r.str("sender_name"), r.longOf("message_count"),
            r.str("created_at"));

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
                + " sender_name TEXT NOT NULL DEFAULT '',"
                + " created_at  TEXT NOT NULL)");
    }

    public List<Blocked> list() {
        return jdbc.query("SELECT b.sender_hash, b.sender_name, b.created_at,"
                        + " (SELECT COUNT(*) FROM chat_message m WHERE m.sender_hash = b.sender_hash)"
                        + " AS message_count"
                        + " FROM distill_blocked_sender b ORDER BY b.created_at DESC",
                BLOCKED);
    }

    /** 最近发言的群成员，按消息量倒序；给页面选人用 */
    public List<Sender> senders(int limit) {
        return jdbc.query("SELECT m.sender_hash, MAX(m.sender_name) AS sender_name,"
                        + " COUNT(*) AS message_count,"
                        + " EXISTS(SELECT 1 FROM distill_blocked_sender b"
                        + "        WHERE b.sender_hash = m.sender_hash) AS blocked"
                        + " FROM chat_message m WHERE m.sender_hash <> ''"
                        + " GROUP BY m.sender_hash ORDER BY message_count DESC LIMIT ?",
                r -> new Sender(r.str("sender_hash"), r.str("sender_name"),
                        r.longOf("message_count"), r.intOf("blocked") != 0),
                Math.min(Math.max(1, limit), 500));
    }

    public void add(String senderHash, String senderName) {
        if (senderHash == null || senderHash.isBlank()) {
            throw new IllegalArgumentException("senderHash 不能为空");
        }
        jdbc.update("INSERT INTO distill_blocked_sender(sender_hash, sender_name, created_at)"
                        + " VALUES (?,?,?) ON CONFLICT(sender_hash) DO UPDATE SET"
                        + " sender_name = excluded.sender_name",
                senderHash.strip(), senderName == null ? "" : senderName.strip(),
                Instant.now().toString());
    }

    public boolean remove(String senderHash) {
        return jdbc.update("DELETE FROM distill_blocked_sender WHERE sender_hash = ?",
                senderHash == null ? "" : senderHash.strip()) > 0;
    }
}
