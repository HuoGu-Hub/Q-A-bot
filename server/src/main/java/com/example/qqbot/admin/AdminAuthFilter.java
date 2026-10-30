package com.example.qqbot.admin;

import com.example.qqbot.persistence.AdminAuditRepository;
import com.example.qqbot.qa.QaStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 管理后台的认证与授权闸门。
 *
 * <p>四件事，按顺序：
 * <ol>
 *   <li>没有账号 → 整个 {@code /admin} 返回 503，**绝不裸奔**</li>
 *   <li>白名单（登录页、登录接口、前端静态资源）直接放行</li>
 *   <li>校验会话；API 返回 401 JSON，页面重定向到登录页</li>
 *   <li>按角色判权：GET 要只读、写操作要编辑、用户管理要管理员</li>
 * </ol>
 *
 * <p>顺便写两类记录：{@code admin_visit} 供"访问次数"统计；
 * {@code admin_audit} 记**写操作与登录**，用于回答"这条知识是谁删的"。
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    public static final String COOKIE_NAME = "admin_token";
    public static final String ATTR_PATH = "admin.path";
    /** 通过校验后的 {@link AdminPrincipal} 挂在这里，供控制器读取 */
    public static final String ATTR_PRINCIPAL = "admin.principal";

    /**
     * 不需要登录就能访问的路径。
     *
     * <p>放行的都是**不含任何数据的静态壳子**：前端构建产物本身不含数据，
     * 登录接口本来就要匿名可达。真正的数据全在 {@code /admin/api/**} 后面。
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/admin.html",
            "/admin/api/login",
            "/admin/api/login/qq-code");

    /** 未绑定 QQ 时唯一允许的后台 API */
    private static final Set<String> QQ_BIND_PATHS = Set.of(
            "/admin/api/session",
            "/admin/api/logout",
            "/admin/api/logout-all",
            "/admin/api/me/qq/send-code",
            "/admin/api/me/qq/verify");

    private static final String[] PUBLIC_PREFIXES = {"/admin/assets/", "/admin/login"};

    private final AdminPolicy props;
    private final AdminAuth auth;
    private final QaStore store;
    private final AdminAuditRepository audit;

    public AdminAuthFilter(AdminPolicy props, AdminAuth auth, QaStore store,
                           AdminAuditRepository audit) {
        this.props = props;
        this.auth = auth;
        this.store = store;
        this.audit = audit;
    }

    /** 当前请求的登录身份；未登录返回 {@code null} */
    public static AdminPrincipal current(HttpServletRequest request) {
        Object v = request == null ? null : request.getAttribute(ATTR_PRINCIPAL);
        return v instanceof AdminPrincipal p ? p : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String raw = request.getRequestURI();
        if (raw == null || !raw.startsWith("/admin")) {
            chain.doFilter(request, response);
            return;
        }
        String path = canonicalPath(request);
        if (path == null || !path.startsWith("/admin")) {
            badRequest(response, raw.startsWith("/admin/api"));
            return;
        }

        if (!auth.isConfigured()) {
            disabled(response, path);
            return;
        }

        if (PUBLIC_PATHS.contains(path) || hasPublicPrefix(path)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<AdminPrincipal> principal = auth.principal(readCookie(request));
        if (principal.isEmpty()) {
            unauthorized(response, path, "未登录或会话已过期");
            return;
        }

        if (path.startsWith("/admin/api/")) {
            AdminRole required = requiredRole(path, request.getMethod());
            if (!principal.get().role().atLeast(required)) {
                forbidden(response, required);
                return;
            }
            if (isWrite(request.getMethod()) && !originAllowed(request)) {
                forbiddenOrigin(response);
                return;
            }
            if (!principal.get().qqVerified() && !QQ_BIND_PATHS.contains(path)) {
                qqBindRequired(response);
                return;
            }
        }

        request.setAttribute(ATTR_PRINCIPAL, principal.get());
        recordVisit(request, path);

        try {
            chain.doFilter(request, response);
        } finally {
            auditWrite(request, response, path, principal.get());
        }
    }

    /**
     * 把请求路径归一化，并拒绝一切可能造成路径歧义的写法。
     *
     * <p>过滤器如果用 {@code getRequestURI()} 的原始字符串判权，而 Spring MVC 用
     * 去掉矩阵参数后的路径匹配，{@code /admin/api;x/users} 就会命中账号管理控制器，
     * 却只被要求 EDITOR —— 编辑可以创建管理员。这里先拒绝分号、点段与编码分隔符，
     * 再交给 Spring 的 {@link UrlPathHelper} 做同一套归一化。
     *
     * @return 归一化路径；疑似路径混淆时返回 {@code null}
     */
    static String canonicalPath(HttpServletRequest request) {
        String raw = request.getRequestURI();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (raw.indexOf(';') >= 0 || raw.indexOf('\\') >= 0 || raw.contains("..")
                || lower.contains("%2f") || lower.contains("%5c")
                || lower.contains("%2e") || lower.contains("%3b")) {
            return null;
        }
        try {
            return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void badRequest(HttpServletResponse response, boolean api) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(api
                ? "application/json;charset=UTF-8" : "text/plain;charset=UTF-8");
        response.getWriter().write(api ? "{\"error\":\"请求路径不合法\"}" : "请求路径不合法");
    }

    /** 用户管理与审计 → ADMIN；只读 → VIEWER；其余写操作 → EDITOR。 */
    static AdminRole requiredRole(String path, String method) {
        if (path.equals("/admin/api/logout") || path.equals("/admin/api/logout-all")
                || path.equals("/admin/api/password") || path.equals("/admin/api/session")
                || path.equals("/admin/api/me/qq/send-code")
                || path.equals("/admin/api/me/qq/verify")) {
            return AdminRole.VIEWER;
        }
        if (path.startsWith("/admin/api/users")) {
            return AdminRole.ADMIN;
        }
        boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        return read ? AdminRole.VIEWER : AdminRole.EDITOR;
    }

    private static boolean isWrite(String method) {
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)
                && !"OPTIONS".equalsIgnoreCase(method);
    }

    /**
     * 写操作的 Origin 校验。浏览器跨站写会带 Origin，命令行 curl 通常不带。
     * 只比 host，比整个 Origin 更宽容端口变化。
     */
    static boolean originAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            String originHost = URI.create(origin).getHost();
            if (originHost == null) {
                return false;
            }
            int colon = host.lastIndexOf(':');
            String hostName = colon > 0 ? host.substring(0, colon) : host;
            return originHost.equalsIgnoreCase(hostName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean hasPublicPrefix(String path) {
        for (String p : PUBLIC_PREFIXES) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 后台未启用：API 回 JSON，页面直接渲染一句话。**不重定向**，避免死循环。 */
    private void disabled(HttpServletResponse response, String path) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        // 诊断只进日志：503 页面会暴露绝对路径与部署目录，不值得给匿名请求看
        log.warn("[ADMIN] 后台未启用：{}", com.example.qqbot.files.ProjectFiles.describe());
        if (path.startsWith("/admin/api/")) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("""
                    {"error":"管理后台未启用：还没有任何后台账号。在 .env 里设置 ADMIN_PASSWORD 后重启即可自动创建 admin 账号"}
                    """);
        } else {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("""
                    <meta charset="utf-8">
                    <div style="font:15px/1.7 sans-serif;max-width:460px;margin:80px auto">
                    <h3>管理后台未启用</h3>
                    <p>系统里还没有任何后台账号。在 <code>.env</code> 里设置
                    <code>ADMIN_PASSWORD</code> 后重启机器人，会自动创建 <code>admin</code> 账号。</p>
                    <p style="color:#888;font-size:13px">详细原因已写入服务器日志。</p>
                    </div>
                    """);
        }
    }

    private void unauthorized(HttpServletResponse response, String path, String message)
            throws IOException {
        if (path.startsWith("/admin/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"" + message + "\"}");
        } else {
            response.sendRedirect("/admin/login");
        }
    }

    private void forbidden(HttpServletResponse response, AdminRole required) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"权限不足：这个操作需要「"
                + required.label() + "」及以上权限\"}");
    }

    private void qqBindRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"请先绑定 QQ\",\"code\":\"QQ_BIND_REQUIRED\"}");
    }

    private void forbiddenOrigin(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"请求来源不合法\"}");
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private void recordVisit(HttpServletRequest request, String path) {
        if (!props.isRecordVisits()) {
            return;
        }
        try {
            store.recordVisit(path, hashIp(AdminClientIp.of(request)),
                    request.getHeader("User-Agent"),
                    request.getMethod().equals("GET") ? "view" : "action");
        } catch (Exception e) {
            log.debug("[ADMIN] 记录访问失败（不影响请求）：{}", e.getMessage());
        }
    }

    /** 只记写操作：GET 量大又没有信息量，记进去只会把有用的行埋掉 */
    private void auditWrite(HttpServletRequest request, HttpServletResponse response,
                            String path, AdminPrincipal principal) {
        String method = request.getMethod();
        if (!path.startsWith("/admin/api/") || "GET".equalsIgnoreCase(method)
                || path.equals("/admin/api/logout")) {
            return;
        }
        String query = request.getQueryString();
        String detail = "status=" + response.getStatus()
                + (query == null ? "" : " ?" + query);
        audit.record(principal.id(), principal.username(), "write", method, path,
                detail, hashIp(AdminClientIp.of(request)));
    }

    /** IP 只存哈希 —— 统计与审计都不需要原始 IP */
    static String hashIp(String ip) {
        if (ip == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (Exception e) {
            return "";
        }
    }
}
