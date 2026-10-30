package com.example.qqbot.admin;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.web.ApiException;

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
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
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
    private final AdminQqCodeService qqCodes;
    private final AdminTrustedDeviceService devices;

    static final String DEVICE_COOKIE = "qqbot_admin_device";

    public AdminController(QaAnalytics analytics, AdminAuth auth, AdminPolicy props,
                           QaStore store, AdminUserService users, AdminAuditRepository audit,
                           AdminQqCodeService qqCodes, AdminTrustedDeviceService devices) {
        this.analytics = analytics;
        this.auth = auth;
        this.props = props;
        this.store = store;
        this.users = users;
        this.audit = audit;
        this.qqCodes = qqCodes;
        this.devices = devices;
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
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        String username = body == null ? null : body.username();
        if (username == null && body != null) {
            username = body.identifier();
        }
        String password = body == null ? null : body.password();
        String source = request == null ? null : AdminClientIp.of(request);
        String ipHash = AdminAuthFilter.hashIp(source);
        AdminAuth.PasswordResult result = auth.authenticate(username, password, source);

        if (result.outcome() == AdminAuth.LoginOutcome.LOCKED) {
            long wait = Math.max(1, result.retryAfterSeconds());
            audit.record(null, username, "login-locked", "POST", "/admin/api/login",
                    "retryAfterSeconds=" + wait, ipHash);
            throw ApiException.tooManyRequests(
                    "尝试次数过多，请 " + Math.max(1, wait / 60) + " 分钟后再试（剩 " + wait + " 秒）", wait);
        }
        if (result.outcome() == AdminAuth.LoginOutcome.DISABLED) {
            throw ApiException.serviceUnavailable("SERVICE_UNAVAILABLE", "后台未启用或还没有账号");
        }
        if (result.outcome() != AdminAuth.LoginOutcome.OK || result.user() == null) {
            audit.record(null, username, "login-failed", "POST", "/admin/api/login", null, ipHash);
            throw ApiException.unauthorized("账号或密码不对");
        }

        AdminUser user = result.user();

        // 信任设备：密码对了就免验证码
        String deviceToken = readCookie(request, DEVICE_COOKIE);
        if (user.qqVerified() && devices.isValid(deviceToken, user.id())) {
            return sessionResponse(user, request, response, ipHash, false);
        }

        // 已经绑定 QQ：密码之后必须再验一次 QQ 码
        if (user.qq() != null && !user.qq().isBlank()) {
            try {
                AdminQqCodeService.Issued issued = qqCodes.issue(
                        user.id(), user.qq(), AdminQqCodeService.PURPOSE_LOGIN, null);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("needCode", true);
                out.put("challengeId", issued.challengeId());
                out.put("qq", issued.qqMasked());
                return ResponseEntity.ok(out);
            } catch (AdminQqCodeService.CodeException e) {
                audit.record(user.id(), user.username(), "qq-code-send-failed",
                        "POST", "/admin/api/login", e.getMessage(), ipHash);
                throw ApiException.serviceUnavailable("SERVICE_UNAVAILABLE", e.getMessage());
            }
        }

        // 还没绑 QQ：先发会话，但 AdminAuthFilter 会把它锁在“绑定 QQ”页
        return sessionResponse(user, request, response, ipHash, false);
    }

    /** 登录第二步：QQ 验证码。成功后才发正式会话 */
    @PostMapping("/login/qq-code")
    public ResponseEntity<Map<String, Object>> loginWithQqCode(@RequestBody QqCodeRequest body,
                                                               HttpServletRequest request,
                                                               HttpServletResponse response) {
        String challengeId = body == null ? null : body.challengeId();
        String code = body == null ? null : body.code();
        boolean trust = body != null && Boolean.TRUE.equals(body.trustDevice());
        String source = request == null ? null : AdminClientIp.of(request);
        String ipHash = AdminAuthFilter.hashIp(source);
        try {
            AdminQqCodeService.Consumed consumed = qqCodes.consume(
                    challengeId, code, AdminQqCodeService.PURPOSE_LOGIN);
            AdminUser user = users.find(consumed.userId()).orElseThrow(
                    () -> new AdminQqCodeService.CodeException("账号不存在"));
            if (!user.qqVerified()) {
                users.markQqVerified(user.id());
                user = users.find(user.id()).orElse(user);
            }
            audit.record(user.id(), user.username(), "login-qq-ok", "POST",
                    "/admin/api/login/qq-code", null, ipHash);
            return sessionResponse(user, request, response, ipHash, trust);
        } catch (AdminQqCodeService.CodeException e) {
            audit.record(null, null, "login-qq-failed", "POST",
                    "/admin/api/login/qq-code", e.getMessage(), ipHash);
            throw ApiException.unauthorized(e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> sessionResponse(AdminUser user,
                                                                HttpServletRequest request,
                                                                HttpServletResponse response,
                                                                String ipHash,
                                                                boolean trustDevice) {
        String token = auth.issueSession(user);
        Cookie cookie = new Cookie(AdminAuthFilter.COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/admin");
        cookie.setMaxAge(props.getSessionHours() * 3600);
        cookie.setSecure(request != null && AdminClientIp.isHttps(request));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);

        if (trustDevice) {
            String raw = devices.issue(user.id(), request == null ? null : request.getHeader("User-Agent"));
            Cookie device = new Cookie(DEVICE_COOKIE, raw);
            device.setHttpOnly(true);
            device.setPath("/admin");
            device.setMaxAge(30 * 24 * 3600);
            device.setSecure(request != null && AdminClientIp.isHttps(request));
            device.setAttribute("SameSite", "Lax");
            response.addCookie(device);
        }

        audit.record(user.id(), user.username(), "login-ok", "POST", "/admin/api/login", null, ipHash);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("user", userDto(AdminPrincipal.of(user)));
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
        m.put("qq", p.qq());
        m.put("qqVerified", p.qqVerified());
        return m;
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request == null ? null : request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
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
            throw ApiException.unauthorized("未登录或会话已过期");
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
    public ResponseEntity<Map<String, Object>> changeMyPassword(@RequestBody PasswordRequest body,
                                                               HttpServletRequest request) {
        AdminPrincipal p = AdminAuthFilter.current(request);
        if (p == null) {
            throw ApiException.unauthorized("未登录");
        }
        String oldPassword = body == null ? null : body.oldPassword();
        String newPassword = body == null ? null : body.newPassword();
        if (users.authenticate(p.username(), oldPassword).isEmpty()) {
            throw ApiException.badRequest("原密码不对");
        }
        try {
            users.changePassword(p.id(), newPassword);
            auth.invalidateUser(p.id());
            devices.revokeAll(p.id());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        audit.record(p.id(), p.username(), "password-self", "POST", "/admin/api/password", null, "");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 绑定 QQ：先发码到目标 QQ，验证通过后才标记 verified */
    @PostMapping("/me/qq/send-code")
    public ResponseEntity<Map<String, Object>> sendQqBindCode(@RequestBody(required = false) BindCodeRequest body,
                                                              HttpServletRequest request) {
        AdminPrincipal p = AdminAuthFilter.current(request);
        if (p == null) {
            throw ApiException.unauthorized("未登录");
        }
        if (p.qqVerified()) {
            throw ApiException.badRequest("这个账号已经绑定 QQ");
        }
        String qq = body == null ? null : body.qq();
        if (qq == null || qq.isBlank()) {
            qq = p.qq();
        }
        if (p.qq() != null && !p.qq().isBlank() && !p.qq().equals(qq)) {
            throw ApiException.badRequest("只能绑定管理员预置的 QQ");
        }
        try {
            users.bindQq(p.id(), qq);
            AdminQqCodeService.Issued issued = qqCodes.issue(
                    p.id(), qq, AdminQqCodeService.PURPOSE_BIND, null);
            audit.record(p.id(), p.username(), "qq-bind-code", "POST",
                    "/admin/api/me/qq/send-code", "qq=" + issued.qqMasked(), "");
            return ResponseEntity.ok(Map.of("ok", true,
                    "challengeId", issued.challengeId(), "qq", issued.qqMasked()));
        } catch (IllegalArgumentException | AdminQqCodeService.CodeException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @PostMapping("/me/qq/verify")
    public ResponseEntity<Map<String, Object>> verifyQqBind(@RequestBody QqCodeRequest body,
                                                            HttpServletRequest request) {
        AdminPrincipal p = AdminAuthFilter.current(request);
        if (p == null) {
            throw ApiException.unauthorized("未登录");
        }
        String challengeId = body == null ? null : body.challengeId();
        String code = body == null ? null : body.code();
        try {
            AdminQqCodeService.Consumed consumed = qqCodes.consume(
                    challengeId, code, AdminQqCodeService.PURPOSE_BIND);
            if (consumed.userId() != p.id()) {
                throw ApiException.badRequest("验证码不属于当前账号");
            }
            users.markQqVerified(p.id());
            auth.refreshUser(p.id());
            devices.revokeAll(p.id());
            audit.record(p.id(), p.username(), "qq-bind-ok", "POST",
                    "/admin/api/me/qq/verify", "qq=" + AdminQqCodeService.mask(consumed.qq()), "");
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (AdminQqCodeService.CodeException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 退出所有设备：撤销所有会话和信任设备 */
    @PostMapping("/logout-all")
    public Map<String, Object> logoutAll(HttpServletRequest request, HttpServletResponse response) {
        AdminPrincipal p = AdminAuthFilter.current(request);
        if (p != null) {
            auth.invalidateUser(p.id());
            devices.revokeAll(p.id());
            audit.record(p.id(), p.username(), "logout-all", "POST",
                    "/admin/api/logout-all", null, "");
        }
        Cookie clear = new Cookie(AdminAuthFilter.COOKIE_NAME, "");
        clear.setPath("/admin");
        clear.setMaxAge(0);
        response.addCookie(clear);
        Cookie device = new Cookie(DEVICE_COOKIE, "");
        device.setPath("/admin");
        device.setMaxAge(0);
        response.addCookie(device);
        return Map.of("ok", true);
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
    public ResponseEntity<Map<String, Object>> annotate(@RequestBody AnnotateRequest body) {
        long id = body.id() == null ? 0L : body.id();
        String verdict = body.verdict() == null ? null : body.verdict().trim();
        if (id <= 0 || verdict == null) {
            throw ApiException.badRequest("需要 id 和 verdict");
        }
        if (!VALID_VERDICTS.contains(verdict)) {
            throw ApiException.badRequest("verdict 只能是 " + VALID_VERDICTS);
        }
        boolean ok = store.annotate(id, verdict, body.note(), "human");
        if (!ok) {
            throw ApiException.notFound("没有这条记录");
        }
        return ResponseEntity.ok(Map.of("ok", true));
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


    @JsonIgnoreProperties(ignoreUnknown = false)


    public record LoginRequest(String username, String identifier, String password) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record QqCodeRequest(String challengeId, String code, Boolean trustDevice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record PasswordRequest(String oldPassword, String newPassword) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record BindCodeRequest(String qq) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record AnnotateRequest(Long id, String verdict, String note) {
    }
}
