package com.example.qqbot.admin;

import com.example.qqbot.persistence.Jdbc;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 后台 QQ 验证码的持久化。
 *
 * <p>只存哈希，不存明文；challenge_id 把验证码绑定到一次具体操作，
 * 防止拿别的登录流程的码来换会话。
 */
@Component
public class AdminQqCodeRepository {

    public record Row(long id, long userId, String qq, String purpose, String challengeId,
                      String codeHash, long createdAt, long expiresAt, int attempts,
                      boolean used, String payload) {
    }

    private final Jdbc jdbc;

    public AdminQqCodeRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS admin_qq_code ("
                + " id           INTEGER PRIMARY KEY,"
                + " user_id      INTEGER NOT NULL,"
                + " qq           TEXT NOT NULL,"
                + " purpose      TEXT NOT NULL,"
                + " challenge_id TEXT NOT NULL UNIQUE,"
                + " code_hash    TEXT NOT NULL,"
                + " created_at   INTEGER NOT NULL,"
                + " expires_at   INTEGER NOT NULL,"
                + " attempts     INTEGER NOT NULL DEFAULT 0,"
                + " used         INTEGER NOT NULL DEFAULT 0,"
                + " payload      TEXT"
                + " )");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_admin_qq_code_user"
                + " ON admin_qq_code(user_id, purpose, created_at)");
    }

    private static final String SELECT = "SELECT id, user_id, qq, purpose, challenge_id, code_hash,"
            + " created_at, expires_at, attempts, used, payload FROM admin_qq_code";

    private static Row map(Jdbc.Row r) {
        return new Row(r.longOf("id"), r.longOf("user_id"), r.str("qq"), r.str("purpose"),
                r.str("challenge_id"), r.str("code_hash"), r.longOf("created_at"),
                r.longOf("expires_at"), (int) r.longOf("attempts"), r.bool("used"),
                r.str("payload"));
    }

    public long create(long userId, String qq, String purpose, String challengeId,
                       String codeHash, long createdAt, long expiresAt, String payload) {
        return jdbc.insert("INSERT INTO admin_qq_code"
                        + " (user_id, qq, purpose, challenge_id, code_hash, created_at, expires_at, payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                userId, qq, purpose, challengeId, codeHash, createdAt, expiresAt, payload);
    }

    public Row find(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            return null;
        }
        return jdbc.queryOne(SELECT + " WHERE challenge_id = ?", AdminQqCodeRepository::map, challengeId);
    }

    public void markUsed(long id) {
        jdbc.update("UPDATE admin_qq_code SET used = 1 WHERE id = ?", id);
    }

    public void incrementAttempts(long id) {
        jdbc.update("UPDATE admin_qq_code SET attempts = attempts + 1 WHERE id = ?", id);
    }

    public long countSince(long userId, String purpose, long since) {
        return jdbc.count("SELECT COUNT(*) FROM admin_qq_code"
                + " WHERE user_id = ? AND purpose = ? AND created_at >= ?", userId, purpose, since);
    }

    public long latestCreatedAt(long userId, String purpose) {
        Long v = jdbc.queryOne("SELECT MAX(created_at) AS v FROM admin_qq_code"
                        + " WHERE user_id = ? AND purpose = ?",
                r -> r.longOf("v"), userId, purpose);
        return v == null ? 0 : v;
    }

    public void deleteExpired(long before) {
        jdbc.update("DELETE FROM admin_qq_code WHERE expires_at < ?", before);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM admin_qq_code WHERE id = ?", id);
    }
}
