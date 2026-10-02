package com.example.qqbot.admin;

import com.example.qqbot.config.AdminProperties;
import com.example.qqbot.files.ProjectFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理后台的会话认证。
 *
 * <p>刻意做得极简：一个内存里的 token 表 + 有效期，没有 JWT、没有用户体系、没有数据库。
 * 这是**个人自用的后台**，用一个密码足够；引入用户体系只会增加攻击面。
 *
 * <p>三个安全要点：
 * <ol>
 *   <li><b>没配密码就完全关闭</b> —— 绝不退化成"无密码可访问"</li>
 *   <li>密码比对用<b>常量时间比较</b>，避免时序侧信道泄露密码长度/前缀</li>
 *   <li><b>登录失败锁定</b>：同一来源在窗口内失败 N 次就锁一段时间（N/窗口/时长都可配，0 = 关闭）。
 *       后台会挂到公网上，没有这条的话脚本可以全速穷举</li>
 * </ol>
 */
@Component
public class AdminAuth {

    private static final Logger log = LoggerFactory.getLogger(AdminAuth.class);

    private final AdminProperties props;

    /** token → 过期时间 */
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();

    public AdminAuth(AdminProperties props) {
        this.props = props;
        if (isConfigured()) {
            log.info("[ADMIN] 管理后台已启用（已读到 ADMIN_PASSWORD）");
        } else {
            log.warn("""
                    [ADMIN] ⚠️ 管理后台未启用：没读到 ADMIN_PASSWORD，所有 /admin 请求都会返回 503。
                      {}
                      → 修法一：把 .env 放在【工程根目录】再重启（现在会从任意子目录向上找到它）
                      → 修法二：export ADMIN_PASSWORD=... 之后用同一个 shell 启动
                    """, ProjectFiles.describe());
        }
    }

    /** 后台是否可用（配了密码才可用） */
    public boolean isConfigured() {
        return props.isEnabled() && StringUtils.hasText(props.getPassword());
    }

    /**
     * 登录结果。
     *
     * <p>原来只返回 token / null，调用方分不清「密码错」和「被限流」——
     * 前者要 401，后者要 429 并告诉对方还要等多久。
     */
    public enum LoginOutcome { OK, BAD_PASSWORD, DISABLED, LOCKED }

    public record LoginAttempt(String token, LoginOutcome outcome, long retryAfterSeconds) {
    }

    /**
     * 一个来源的连续失败状态。
     *
     * <p>方法都加锁：登录接口是并发的，计数不能丢。
     */
    private static final class FailureState {
        private int count;
        private Instant firstAt;
        private Instant lockedUntil;

        synchronized long lockedForSeconds(Instant now) {
            if (lockedUntil == null || !now.isBefore(lockedUntil)) {
                return 0;
            }
            return Math.max(1, Duration.between(now, lockedUntil).toSeconds());
        }

        /**
         * 记一次失败。
         *
         * @return 已触发锁定时返回锁定时长（秒），没触发返回 0
         */
        synchronized long recordFailure(Instant now, int maxFailures, int windowSeconds, int lockSeconds) {
            if (firstAt == null || Duration.between(firstAt, now).toSeconds() > windowSeconds) {
                count = 0;
                firstAt = now;
            }
            count++;
            if (maxFailures > 0 && count >= maxFailures) {
                lockedUntil = now.plusSeconds(Math.max(1, lockSeconds));
                return Math.max(1, lockSeconds);
            }
            return 0;
        }

        synchronized void clear() {
            count = 0;
            firstAt = null;
            lockedUntil = null;
        }

        /** 早就没用的条目（可以清掉，避免 map 无限涨） */
        synchronized boolean isStale(Instant now, int windowSeconds) {
            Instant ref = lockedUntil != null ? lockedUntil : firstAt;
            return ref == null || ref.isBefore(now.minusSeconds(windowSeconds));
        }
    }

    /** 来源 → 失败状态。来源取的是**连接的对端地址**（见 AdminController.login） */
    private final Map<String, FailureState> failures = new ConcurrentHashMap<>();

    /**
     * 校验密码，成功返回新 token。
     *
     * @param sourceKey 来源标识（用来做失败锁定）。传 null/空也没关系，会归到 "unknown"
     */
    public LoginAttempt login(String password, String sourceKey) {
        String key = StringUtils.hasText(sourceKey) ? sourceKey : "unknown";
        Instant now = Instant.now();
        cleanupIfCrowded(now);

        FailureState state = failures.computeIfAbsent(key, k -> new FailureState());

        // ① 还在锁定中：直接拒，连密码都不比 —— 否则限流形同虚设
        long locked = state.lockedForSeconds(now);
        if (locked > 0) {
            log.warn("[ADMIN] 登录被拒：来源 {} 仍在锁定中（剩 {} 秒）", key, locked);
            return new LoginAttempt(null, LoginOutcome.LOCKED, locked);
        }

        if (!isConfigured()) {
            log.warn("[ADMIN] 登录被拒：没有配置管理员密码（app.admin.password / ADMIN_PASSWORD）");
            return new LoginAttempt(null, LoginOutcome.DISABLED, 0);
        }

        if (!constantTimeEquals(password, props.getPassword())) {
            long lockSeconds = state.recordFailure(now, props.getLoginMaxFailures(),
                    props.getLoginFailureWindowSeconds(), props.getLoginLockSeconds());
            if (lockSeconds > 0) {
                log.warn("[ADMIN] ⚠️ 来源 {} 连续失败 {} 次，已锁定 {} 秒",
                        key, props.getLoginMaxFailures(), lockSeconds);
                return new LoginAttempt(null, LoginOutcome.LOCKED, lockSeconds);
            }
            log.warn("[ADMIN] 登录失败：密码不对（来源 {}）", key);
            return new LoginAttempt(null, LoginOutcome.BAD_PASSWORD, 0);
        }

        state.clear();
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, now.plus(props.getSessionHours(), ChronoUnit.HOURS));
        log.info("[ADMIN] 登录成功，会话有效期 {} 小时", props.getSessionHours());
        return new LoginAttempt(token, LoginOutcome.OK, 0);
    }

    /**
     * 顺手清掉过期条目。
     *
     * <p>只在表变大时才扫（来源 IP 通常就一两个，扫的开销可以忽略；
     * 但如果哪天前面挂了代理、来源变多，也不能让这个 map 无限涨）。
     */
    private void cleanupIfCrowded(Instant now) {
        if (failures.size() < 256) {
            return;
        }
        int window = props.getLoginFailureWindowSeconds();
        failures.entrySet().removeIf(e -> e.getValue().isStale(now, window));
    }

    public boolean validate(String token) {
        if (!isConfigured() || !StringUtils.hasText(token)) {
            return false;
        }
        Instant expiresAt = sessions.get(token);
        if (expiresAt == null) {
            return false;
        }
        if (Instant.now().isAfter(expiresAt)) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 当前有效会话数（排查用） */
    public int sessionCount() {
        return sessions.size();
    }

    /**
     * 常量时间比较。
     *
     * <p>做法是**先各自 SHA-256 再比较**：这样两边都是固定 32 字节，
     * {@link MessageDigest#isEqual} 的比较次数与内容无关 ——
     * 既不会因为"前几位就不同"提前返回，也不会泄露密码长度。
     */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] ha = md.digest(a.getBytes(StandardCharsets.UTF_8));
            byte[] hb = md.digest(b.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(ha, hb);
        } catch (Exception e) {
            return false;
        }
    }
}
