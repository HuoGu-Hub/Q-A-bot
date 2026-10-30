package com.example.qqbot.admin;

import com.example.qqbot.persistence.Jdbc;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 信任设备 Cookie 的持久化。只存 token 哈希。 */
@Component
public class AdminTrustedDeviceRepository {

    public record Row(long id, long userId, String tokenHash, long createdAt, long lastSeenAt,
                      long expiresAt, boolean revoked, String userAgent) {
    }

    private final Jdbc jdbc;

    public AdminTrustedDeviceRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS admin_trusted_device ("
                + " id           INTEGER PRIMARY KEY,"
                + " user_id      INTEGER NOT NULL,"
                + " token_hash   TEXT NOT NULL UNIQUE,"
                + " created_at   INTEGER NOT NULL,"
                + " last_seen_at INTEGER NOT NULL,"
                + " expires_at   INTEGER NOT NULL,"
                + " revoked      INTEGER NOT NULL DEFAULT 0,"
                + " user_agent   TEXT"
                + " )");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_admin_device_user"
                + " ON admin_trusted_device(user_id)");
    }

    private static final String SELECT = "SELECT id, user_id, token_hash, created_at, last_seen_at,"
            + " expires_at, revoked, user_agent FROM admin_trusted_device";

    private static Row map(Jdbc.Row r) {
        return new Row(r.longOf("id"), r.longOf("user_id"), r.str("token_hash"),
                r.longOf("created_at"), r.longOf("last_seen_at"), r.longOf("expires_at"),
                r.bool("revoked"), r.str("user_agent"));
    }

    public void create(long userId, String tokenHash, long createdAt, long lastSeenAt,
                       long expiresAt, String userAgent) {
        jdbc.insert("INSERT INTO admin_trusted_device"
                        + " (user_id, token_hash, created_at, last_seen_at, expires_at, user_agent)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                userId, tokenHash, createdAt, lastSeenAt, expiresAt, userAgent);
    }

    public Row findByHash(String tokenHash) {
        return jdbc.queryOne(SELECT + " WHERE token_hash = ?", AdminTrustedDeviceRepository::map, tokenHash);
    }

    public void touch(long id, long lastSeenAt) {
        jdbc.update("UPDATE admin_trusted_device SET last_seen_at = ? WHERE id = ?", lastSeenAt, id);
    }

    public void revoke(long id) {
        jdbc.update("UPDATE admin_trusted_device SET revoked = 1 WHERE id = ?", id);
    }

    public void revokeAll(long userId) {
        jdbc.update("UPDATE admin_trusted_device SET revoked = 1 WHERE user_id = ? AND revoked = 0", userId);
    }

    public void deleteExpired(long before) {
        jdbc.update("DELETE FROM admin_trusted_device WHERE expires_at < ?", before);
    }
}
