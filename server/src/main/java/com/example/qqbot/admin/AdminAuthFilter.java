package com.example.qqbot.admin;

import com.example.qqbot.config.AdminProperties;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/**
 * 管理后台的认证闸门。
 *
 * <p>规则很简单：
 * <ul>
 *   <li>没配密码 → 整个 {@code /admin} 返回 503（**绝不裸奔**）</li>
 *   <li>白名单（登录页、登录接口）→ 直接放行</li>
 *   <li>其余 → 校验 cookie 里的 token；API 返回 401 JSON，页面重定向到登录页</li>
 * </ul>
 *
 * <p>顺便记一次访问（{@code admin_visit} 表），这就是"统计访问次数"的数据来源。
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    public static final String COOKIE_NAME = "admin_token";
    public static final String ATTR_PATH = "admin.path";

    /**
     * 不需要登录就能访问的路径。
     *
     * <p>放行的都是**不含任何数据的静态壳子**：
     * <ul>
     *   <li>{@code /admin.html} 与 {@code /admin/assets/**} —— 前端构建产物本身不含数据</li>
     *   <li>{@code /admin/api/login} —— 登录接口</li>
     * </ul>
     * 真正的数据全在 {@code /admin/api/**} 后面，那里一律要会话。
     *
     * <p>这样做的意义：前端路由（history 模式）能正常加载，
     * 用户看到登录页而不是一个 401 白屏。
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/admin.html",
            "/admin/api/login");

    /**
     * 放行的前缀。
     *
     * <p>{@code /admin/assets/} 是构建产物；{@code /admin/login} 是**前端登录页路由** ——
     * 它必须能加载（否则"未登录 → 跳登录页 → 又 302 → 死循环"）。
     */
    private static final String[] PUBLIC_PREFIXES = {"/admin/assets/", "/admin/login"};

    private final AdminProperties props;
    private final AdminAuth auth;
    private final QaStore store;

    public AdminAuthFilter(AdminProperties props, AdminAuth auth, QaStore store) {
        this.props = props;
        this.auth = auth;
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/admin")) {
            chain.doFilter(request, response);
            return;
        }

        // 没配密码：整个后台关闭。
        // ⚠️ 这里绝不能用"重定向到登录页" —— 登录页本身也在 /admin 下，
        // 会变成 302 → 302 的死循环。必须直接返回 503。
        if (!auth.isConfigured()) {
            disabled(response, path);
            return;
        }

        if (PUBLIC_PATHS.contains(path) || hasPublicPrefix(path)) {
            chain.doFilter(request, response);
            return;
        }

        String token = readCookie(request);
        if (!auth.validate(token)) {
            unauthorized(response, path, "未登录或会话已过期");
            return;
        }

        recordVisit(request, path);
        chain.doFilter(request, response);
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
        if (path.startsWith("/admin/api/")) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"管理后台未启用：没读到 ADMIN_PASSWORD（"
                    + com.example.qqbot.files.ProjectFiles.describe() + "）\"}");
        } else {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<meta charset=\"utf-8\">"
                    + "<div style=\"font:15px/1.7 sans-serif;max-width:420px;margin:80px auto\">"
                    + "<h3>管理后台未启用</h3>"
                    + "<p>请在 <code>.env</code> 里设置 <code>ADMIN_PASSWORD</code> 后重启机器人。"
                    + "（.env 放在工程根目录即可，从子目录启动也能找到）</p>"
                    + "<p style=\"color:#888;font-size:13px\">诊断："
                    + com.example.qqbot.files.ProjectFiles.describe() + "</p>"
                    + "<p style=\"color:#888\">这是安全默认：没配密码就没有后台，而不是无密码可访问。</p>"
                    + "</div>");
        }
    }

    /** 未登录/会话过期：API 回 401，页面重定向到登录页 */
    private void unauthorized(HttpServletResponse response, String path, String message)
            throws IOException {
        if (path.startsWith("/admin/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"" + message + "\"}");
        } else {
            // 指向**前端路由**（不是静态文件）—— 静态页已迁到 Vue，路径也变了
            response.sendRedirect("/admin/login");
        }
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
            store.recordVisit(path, hashIp(request.getRemoteAddr()),
                    request.getHeader("User-Agent"),
                    request.getMethod().equals("GET") ? "view" : "action");
        } catch (Exception e) {
            log.debug("[ADMIN] 记录访问失败（不影响请求）：{}", e.getMessage());
        }
    }

    /** IP 只存哈希 —— 后台访问统计不需要原始 IP */
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
