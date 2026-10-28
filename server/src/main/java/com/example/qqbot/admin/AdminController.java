package com.example.qqbot.admin;

import com.example.qqbot.persistence.AdminAuditRepository;
import com.example.qqbot.qa.QaAnalytics;
import com.example.qqbot.qa.QaStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台的只读 API（S3）。
 *
 * <p><b>全部统计口径来自 {@link QaAnalytics}</b> —— 和 CLI 报表共用同一套 SQL，
 * 保证"命令行看到的数"和"网页上看到的数"永远一致。
 *
 * <p>认证由 {@link AdminAuthFilter} 统一拦在前面，本类只关心数据。
 */
@RestController
@RequestMapping("/admin/api")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final QaAnalytics analytics;
    private final AdminAuth auth;
    private final AdminPolicy props;
    private final QaStore store;
    private final AdminUserService users;
    private final AdminAuditRepository audit;

    public AdminController(QaAnalytics analytics, AdminAuth auth, AdminPolicy props,
                           QaStore store, AdminUserService users, AdminAuditRepository audit) {
        this.analytics = analytics;
        this.auth = auth;
        this.props = props;
        this.store = store;
        this.users = users;
        this.audit = audit;
    }

    // ==================== 登录 ====================

    /**
     * 登录。
     *
     * <p>失败保护用的是**连接的对端地址 + 账号名**，不是 {@code X-Forwarded-For}：
     * 那个头客户端可以自己伪造，拿它做限流等于没限流。
     *
     * <p>为什么键里要带账号：经 Nginx 之后所有人来源都是 127.0.0.1，
     * 只按来源计数等于全站共用一个计数器 —— 任何人对任意账号连错几次，
     * 全体管理员一起被锁在门外。
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        String source = request == null ? null : request.getRemoteAddr();
        String ipHash = AdminAuthFilter.hashIp(source);
        AdminAuth.LoginAttempt attempt = auth.login(username, password, source);

        if (attempt.outcome() == AdminAuth.LoginOutcome.LOCKED) {
            long wait = Math.max(1, attempt.retryAfterSeconds());
            audit.record(null, username, "login-locked", "POST", "/admin/api/login",
                    "retryAfterSeconds=" + wait, ipHash);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(wait))
                    .body(Map.of(
                            "error", "尝试次数过多，请 " + Math.max(1, wait / 60) + " 分钟后再试（剩 " + wait + " 秒）",
                            "retryAfterSeconds", wait));
        }
        if (attempt.outcome() == AdminAuth.LoginOutcome.DISABLED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "后台未启用或还没有账号"));
        }
        if (attempt.outcome() != AdminAuth.LoginOutcome.OK || attempt.token() == null) {
            audit.record(null, username, "login-failed", "POST", "/admin/api/login", null, ipHash);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "账号或密码不对"));
        }

        Cookie cookie = new Cookie(AdminAuthFilter.COOKIE_NAME, attempt.token());
        cookie.setHttpOnly(true);
        cookie.setPath("/admin");
        cookie.setMaxAge(props.getSessionHours() * 3600);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);

        AdminPrincipal p = attempt.principal();
        if (p != null) {
            audit.record(p.id(), p.username(), "login-ok", "POST", "/admin/api/login", null, ipHash);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("user", userDto(p));
        return ResponseEntity.ok(out);
    }

    /** 账号信息的对外形状：不含任何哈希或盐 */
    private static Map<String, Object> userDto(AdminPrincipal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (p == null) {
            return m;
        }
        m.put("id", p.id());
        m.put("username", p.username());
        m.put("displayName", p.display());
        m.put("role", p.role().id());
        m.put("roleLabel", p.role().label());
        return m;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (AdminAuthFilter.COOKIE_NAME.equals(c.getName())) {
                    auth.logout(c.getValue());
                }
            }
        }
        Cookie clear = new Cookie(AdminAuthFilter.COOKIE_NAME, "");
        clear.setPath("/admin");
        clear.setMaxAge(0);
        response.addCookie(clear);
        return Map.of("ok", true);
    }

    /** 前端用来确认自己还在登录状态，顺便把自己是谁告诉它 */
    @GetMapping("/session")
    public ResponseEntity<Map<String, Object>> session(HttpServletRequest request) {
        AdminPrincipal p = AdminAuthFilter.current(request);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录或会话已过期"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("user", userDto(p));
        out.put("sessions", auth.sessionCount());
        return ResponseEntity.ok(out);
    }

    /**
     * 改自己的密码。
     *
     * <p>对任何已登录账号开放，但必须验证原密码 —— 否则会话被劫持就等于改密。
     */
    @PostMapping("/password")
    public ResponseEntity<Map<String, Object>> changeMyPassword(@RequestBody Map<String, String> body,
                                                               HttpServletRequest request) {
        AdminPrincipal p = AdminAuthFilter.current(request);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        String oldPassword = body == null ? null : body.get("oldPassword");
        String newPassword = body == null ? null : body.get("newPassword");
        if (users.authenticate(p.username(), oldPassword).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "原密码不对"));
        }
        try {
            users.changePassword(p.id(), newPassword);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        audit.record(p.id(), p.username(), "password-self", "POST", "/admin/api/password", null, "");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ==================== 统计 ====================

    @GetMapping("/overview")
    public QaAnalytics.Overview overview(@RequestParam(defaultValue = "30") int days) {
        return analytics.overview(days);
    }

    @GetMapping("/keywords")
    public List<QaAnalytics.KeywordStat> keywords(@RequestParam(defaultValue = "30") int days,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return analytics.keywords(days, limit);
    }

    @GetMapping("/misses")
    public Map<String, Object> misses(@RequestParam(defaultValue = "30") int days,
                                      @RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("withKeyword", analytics.misses(days, limit, 3));
        out.put("unmatched", analytics.unmatchedMisses(days, limit));
        return out;
    }

    @GetMapping("/cosine")
    public List<QaAnalytics.Bucket> cosine(@RequestParam(defaultValue = "30") int days) {
        return analytics.cosineDistribution(days);
    }

    @GetMapping("/sources")
    public Map<String, Object> sources(@RequestParam(defaultValue = "30") int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sources", analytics.sources(days));
        out.put("verdicts", analytics.verdicts(days));
        return out;
    }

    /**
     * 记录列表。
     *
     * @param scope {@code questions}（默认）= 只看真正的提问（{@code guard_action='pass'}）；
     *              {@code all} = 看全部消息（含没 @ 的闲聊），用于排查"为什么没回我"。
     *              ⚠️ 原来这个端点没有任何 guard 过滤，于是「问答记录」页里 93%
     *              是没 @ 机器人的闲聊，却挂着"去群里 @ 机器人问几个问题"的提示语。
     */
    @GetMapping("/records")
    public Map<String, Object> records(@RequestParam(defaultValue = "30") int days,
                                       @RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(defaultValue = "0") int offset,
                                       @RequestParam(defaultValue = "questions") String scope) {
        boolean onlyQuestions = !"all".equalsIgnoreCase(scope);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", analytics.recentRecords(days, Math.min(limit, 500), Math.max(0, offset), onlyQuestions));
        out.put("total", analytics.countRecords(days, onlyQuestions));
        out.put("scope", onlyQuestions ? "questions" : "all");
        return out;
    }

    @GetMapping("/visits")
    public QaAnalytics.VisitSummary visits(@RequestParam(defaultValue = "30") int days) {
        return analytics.visits(days);
    }

    // ==================== 闭环：标注（S4）====================

    /**
     * 给一条记录打标。
     *
     * <p>这是"发现问题 → 定位原因 → 修正 → 验证"闭环的起点。
     * verdict 取值：§good§ / §bad§ / §no_source§ / §hallucination§ / §follow_up§ / §unknown§
     */
    @PostMapping("/annotate")
    public ResponseEntity<Map<String, Object>> annotate(@RequestBody Map<String, Object> body) {
        long id = asLong(body.get("id"));
        String verdict = asString(body.get("verdict"));
        if (id <= 0 || verdict == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 id 和 verdict"));
        }
        if (!VALID_VERDICTS.contains(verdict)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "verdict 只能是 " + VALID_VERDICTS));
        }
        boolean ok = store.annotate(id, verdict, asString(body.get("note")), "human");
        return ok ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "没有这条记录"));
    }

    private static final java.util.Set<String> VALID_VERDICTS =
            java.util.Set.of("good", "bad", "no_source", "hallucination", "follow_up", "unknown");

    private static long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String asString(Object o) {
        String s = o == null ? null : String.valueOf(o);
        return s == null || s.isBlank() ? null : s.trim();
    }

}
