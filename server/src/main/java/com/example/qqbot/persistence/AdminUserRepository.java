package com.example.qqbot.persistence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 后台账号表的读写。
 *
 * <p>表结构里 {@code username} 带 {@code COLLATE NOCASE UNIQUE}：
 * 大小写不同视为同一个账号。这是刻意的 —— 允许 {@code Admin} 与 {@code admin} 并存，
 * 迟早会出现"我明明登录了却看不到自己改的设置"这种事故。
 *
 * <p>返回的是 {@link Row}（含密码哈希），**只在持久化与服务层之间流转**；
 * 对外的 {@code AdminUser} 由服务层映射，不含哈希。
 */
@Component
public class AdminUserRepository {

    private static final Logger log = LoggerFactory.getLogger(AdminUserRepository.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一行账号。passwordHash 只在服务层内部使用 */
    public record Row(
            long id,
            String username,
            String displayName,
            String passwordHash,
            String role,
            boolean enabled,
            String qq,
            boolean qqVerified,
            String createdAt,
            String lastLoginAt) {
    }

    private final Jdbc jdbc;

    public AdminUserRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** 建表。公开是为了让测试能在临时库上自己装配，不依赖 Spring */
    @PostConstruct
    public void init() {
        try {
            ensureSchema();
        } catch (Exception e) {
            // 数据库不可用时不让整个应用起不来：后台会退化成 503，而机器人照常回答
            log.warn("[ADMIN] 账号表初始化失败：{}", e.getMessage());
        }
    }

    private void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS admin_user ("
                + " id            INTEGER PRIMARY KEY,"
                + " username      TEXT NOT NULL COLLATE NOCASE UNIQUE,"
                + " display_name  TEXT,"
                + " password_hash TEXT NOT NULL,"
                + " role          TEXT NOT NULL DEFAULT 'viewer',"
                + " enabled       INTEGER NOT NULL DEFAULT 1,"
                + " created_at    TEXT NOT NULL,"
                + " last_login_at TEXT"
                + " )");
        addColumnIfMissing("qq", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing("qq_verified", "INTEGER NOT NULL DEFAULT 0");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_user_qq"
                + " ON admin_user(qq) WHERE qq <> ''");
    }

    /** SQLite 没有 IF NOT EXISTS 的 ADD COLUMN，升级老库时逐列检查 */
    private void addColumnIfMissing(String name, String definition) {
        long exists = jdbc.count("SELECT COUNT(*) FROM pragma_table_info('admin_user') WHERE name = ?", name);
        if (exists == 0) {
            jdbc.execute("ALTER TABLE admin_user ADD COLUMN " + name + " " + definition);
        }
    }

    private static final String SELECT = "SELECT id, username, display_name, password_hash, role,"
            + " enabled, qq, qq_verified, created_at, last_login_at FROM admin_user";

    private static Row map(Jdbc.Row r) {
        return new Row(
                r.longOf("id"),
                r.str("username"),
                r.str("display_name"),
                r.str("password_hash"),
                r.str("role"),
                r.bool("enabled"),
                r.str("qq"),
                r.bool("qq_verified"),
                r.str("created_at"),
                r.str("last_login_at"));
    }

    public List<Row> list() {
        return jdbc.query(SELECT + " ORDER BY id", AdminUserRepository::map);
    }

    public Row findById(long id) {
        return jdbc.queryOne(SELECT + " WHERE id = ?", AdminUserRepository::map, id);
    }

    public Row findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return jdbc.queryOne(SELECT + " WHERE username = ?", AdminUserRepository::map, username.trim());
    }

    public long countAll() {
        return jdbc.count("SELECT COUNT(*) FROM admin_user");
    }

    /** 仍然可用的管理员数量 —— 删人 / 降级 / 停用之前必须留一个，否则后台会永久锁死 */
    public long countEnabledAdmins() {
        return jdbc.count("SELECT COUNT(*) FROM admin_user WHERE enabled = 1 AND role = 'admin'");
    }

    public long create(String username, String displayName, String passwordHash, String role) {
        return create(username, displayName, passwordHash, role, "");
    }

    public long create(String username, String displayName, String passwordHash, String role, String qq) {
        return jdbc.insert("INSERT INTO admin_user"
                        + " (username, display_name, password_hash, role, enabled, qq, qq_verified, created_at)"
                        + " VALUES (?, ?, ?, ?, 1, ?, 0, ?)",
                username.trim(), displayName, passwordHash, role,
                qq == null ? "" : qq.trim(), LocalDateTime.now().format(TS));
    }

    public Row findByQq(String qq) {
        if (qq == null || qq.isBlank()) {
            return null;
        }
        return jdbc.queryOne(SELECT + " WHERE qq = ?", AdminUserRepository::map, qq.trim());
    }

    public void updateQq(long id, String qq, boolean verified) {
        jdbc.update("UPDATE admin_user SET qq = ?, qq_verified = ? WHERE id = ?",
                qq == null ? "" : qq.trim(), verified ? 1 : 0, id);
    }

    public void markQqVerified(long id) {
        jdbc.update("UPDATE admin_user SET qq_verified = 1 WHERE id = ?", id);
    }

    public void updateProfile(long id, String displayName, String role, boolean enabled) {
        jdbc.update("UPDATE admin_user SET display_name = ?, role = ?, enabled = ? WHERE id = ?",
                displayName, role, enabled ? 1 : 0, id);
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.update("UPDATE admin_user SET password_hash = ? WHERE id = ?", passwordHash, id);
    }

    public void touchLogin(long id) {
        jdbc.update("UPDATE admin_user SET last_login_at = ? WHERE id = ?",
                LocalDateTime.now().format(TS), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM admin_user WHERE id = ?", id);
    }
}
