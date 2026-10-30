package com.example.qqbot.admin;

import com.example.qqbot.files.ProjectFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理后台的会话与登录。
 *
 * <p>会话仍然是**内存里的 token 表**：重启要重新登录，但换来的是"退出即失效"
 * 和不用在库里存会话。账号与密码在 {@link AdminUserService}，这里只管"登录之后"。
 *
 * <p>失败锁定的键是 <b>来源 + 账号名</b>，不再是单纯的来源：
 * 原来经 Nginx 之后所有人都是 127.0.0.1，等于全站共用一个计数器 ——
 * 任何人连错 3 次就能把全部管理员锁在门外。加上账号名之后，
 * 别人只能锁住他自己试的那个账号。
 */
@Component
public class AdminAuth {

    private static final Logger log = LoggerFactory.getLogger(AdminAuth.class);

    private final AdminPolicy props;
    private final AdminUserService users;

    /** token → 会话 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(AdminPrincipal principal, Instant expiresAt) {
    }

    public AdminAuth(AdminPolicy props, AdminUserService users) {
        this.props = props;
        this.users = users;
        if (!isConfigured()) {
            log.warn("""
                    [ADMIN] 管理后台当前不可用：没有任何后台账号。
                      {}
                      → 首次部署：在 .env 里设置 ADMIN_PASSWORD 后重启，会自动建出 admin 账号
                      → 已有账号：检查数据库是否可读
                    """, ProjectFiles.describe());
        }
    }

    /** 后台是否可用：开关打开 + 至少有一个账号 */
    public boolean isConfigured() {
        return props.isEnabled() && users.hasUsers();
    }

    public enum LoginOutcome { OK, BAD_CREDENTIALS, DISABLED, LOCKED }

    public record LoginAttempt(String token, LoginOutcome outcome, long retryAfterSeconds,
                               AdminPrincipal principal) {
        public static LoginAttempt ok(String token, AdminPrincipal p) {
            return new LoginAttempt(token, LoginOutcome.OK, 0, p);
        }

        public static LoginAttempt fail(LoginOutcome outcome, long retryAfterSeconds) {
            return new LoginAttempt(null, outcome, retryAfterSeconds, null);
        }
    }

    /** 一个「来源 + 账号」的连续失败状态 */
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

        synchronized boolean isStale(Instant now, int windowSeconds) {
            Instant ref = lockedUntil != null ? lockedUntil : firstAt;
            return ref == null || ref.isBefore(now.minusSeconds(windowSeconds));
        }
    }

    private final Map<String, FailureState> failures = new ConcurrentHashMap<>();

    private static String key(String source, String username) {
        String s = StringUtils.hasText(source) ? source : "unknown";
        String u = StringUtils.hasText(username) ? username.trim().toLowerCase(Locale.ROOT) : "?";
        return s + "|" + u;
    }

    public LoginAttempt login(String username, String password, String sourceKey) {
        String key = key(sourceKey, username);
        Instant now = Instant.now();
        cleanupIfCrowded(now);

        FailureState state = failures.computeIfAbsent(key, k -> new FailureState());

        long locked = state.lockedForSeconds(now);
        if (locked > 0) {
            log.warn("[ADMIN] 登录被拒：{} 仍在锁定中（剩 {} 秒）", key, locked);
            return LoginAttempt.fail(LoginOutcome.LOCKED, locked);
        }

        if (!isConfigured()) {
            log.warn("[ADMIN] 登录被拒：后台未启用或还没有任何账号");
            return LoginAttempt.fail(LoginOutcome.DISABLED, 0);
        }

        Optional<AdminUser> user = users.authenticate(username, password);
        if (user.isEmpty()) {
            long lockSeconds = state.recordFailure(now, props.getLoginMaxFailures(),
                    props.getLoginFailureWindowSeconds(), props.getLoginLockSeconds());
            if (lockSeconds > 0) {
                log.warn("[ADMIN] ⚠️ {} 连续失败 {} 次，已锁定 {} 秒",
                        key, props.getLoginMaxFailures(), lockSeconds);
                return LoginAttempt.fail(LoginOutcome.LOCKED, lockSeconds);
            }
            log.warn("[ADMIN] 登录失败：账号或密码不对（{}）", key);
            return LoginAttempt.fail(LoginOutcome.BAD_CREDENTIALS, 0);
        }

        state.clear();
        AdminPrincipal principal = AdminPrincipal.of(user.get());
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(principal, now.plus(props.getSessionHours(), ChronoUnit.HOURS)));
        log.info("[ADMIN] 登录成功：{}（{}），会话 {} 小时",
                principal.username(), principal.role().id(), props.getSessionHours());
        return LoginAttempt.ok(token, principal);
    }

    private void cleanupIfCrowded(Instant now) {
        if (failures.size() < 256) {
            return;
        }
        int window = props.getLoginFailureWindowSeconds();
        failures.entrySet().removeIf(e -> e.getValue().isStale(now, window));
    }

    public boolean validate(String token) {
        return principal(token).isPresent();
    }

    /** 取会话里的身份；过期或伪造一律返回空 */
    public Optional<AdminPrincipal> principal(String token) {
        if (!isConfigured() || !StringUtils.hasText(token)) {
            return Optional.empty();
        }
        Session s = sessions.get(token);
        if (s == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(s.expiresAt())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(s.principal());
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 改密、改角色、停用或删除账号后，清掉这个账号的全部旧会话 */
    public int invalidateUser(long userId) {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().principal().id() == userId);
        return before - sessions.size();
    }

    public int sessionCount() {
        return sessions.size();
    }
}
