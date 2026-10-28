package com.example.qqbot.persistence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 后台操作审计。
 *
 * <p>为什么要有它：账号一旦不止一个人，"谁把这条知识删了"就必须查得出来。
 * 应用层原来只有 {@code admin_visit}，而它经 Nginx 之后来源 IP 全一样，等于没有。
 *
 * <p>只记**写操作**与登录事件；GET 请求量大又没有信息量，记进去只会把有用的行埋掉。
 */
@Component
public class AdminAuditRepository {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditRepository.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record Entry(
            long id,
            String ts,
            Long userId,
            String username,
            String action,
            String method,
            String path,
            String detail,
            String ipHash) {
    }

    private final Jdbc jdbc;

    public AdminAuditRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** 建表。公开是为了让测试能在临时库上自己装配，不依赖 Spring */
    @PostConstruct
    public void init() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS admin_audit ("
                    + " id       INTEGER PRIMARY KEY,"
                    + " ts       TEXT NOT NULL,"
                    + " user_id  INTEGER,"
                    + " username TEXT,"
                    + " action   TEXT,"
                    + " method   TEXT,"
                    + " path     TEXT,"
                    + " detail   TEXT,"
                    + " ip_hash  TEXT"
                    + " )");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_audit_ts ON admin_audit(ts)");
        } catch (Exception e) {
            log.warn("[ADMIN] 审计表初始化失败：{}", e.getMessage());
        }
    }

    public void record(Long userId, String username, String action, String method,
                       String path, String detail, String ipHash) {
        try {
            jdbc.update("INSERT INTO admin_audit"
                            + " (ts, user_id, username, action, method, path, detail, ip_hash)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    LocalDateTime.now().format(TS), userId, username, action, method,
                    truncate(path, 300), truncate(detail, 500), ipHash);
        } catch (Exception e) {
            // 审计失败不能影响业务请求，但必须在日志里留痕
            log.warn("[ADMIN] 审计写入失败：{}", e.getMessage());
        }
    }

    public List<Entry> recent(int limit) {
        int n = Math.min(Math.max(limit, 1), 500);
        return jdbc.query("SELECT id, ts, user_id, username, action, method, path, detail, ip_hash"
                        + " FROM admin_audit ORDER BY id DESC LIMIT ?",
                r -> new Entry(
                        r.longOf("id"),
                        r.str("ts"),
                        r.longOf("user_id"),
                        r.str("username"),
                        r.str("action"),
                        r.str("method"),
                        r.str("path"),
                        r.str("detail"),
                        r.str("ip_hash")),
                n);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
