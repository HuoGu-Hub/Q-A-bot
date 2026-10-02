package com.example.qqbot.publicapi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端路由的 fallback。
 *
 * <p>Vue Router 用 history 模式，刷新 {code /library} 这类前端路由时，
 * 请求会直接打到后端 —— 后端得把它们交还给 index.html，否则 404。
 *
 * <p><b>两个端的 fallback 必须分开</b>：
 * <ul>
 *   <li>{@code /} 及其子路径 → {@code public.html}</li>
 *   <li>{@code /admin/**} → {@code admin.html}</li>
 * </ul>
 * 混在一起会导致"公开站的某个路径把后台页面吐出来"这种荒唐结果。
 *
 * <p>注意：这里**只做转发**，鉴权仍然由 {@link com.example.qqbot.admin.AdminAuthFilter}
 * 在更前面完成。
 */
@Controller
public class SpaForwardController {

    /** 后端自己的接口前缀，不能被前端路由抢走 */
    private static final String[] API_PREFIXES = {"/api/", "/onebot/", "/actuator/"};

    /**
     * 管理后台的前端路由 fallback。
     *
     * <p>{@code /admin/login}、{@code /admin/screen} 这些是前端路由，
     * 但 {@code /admin/api/**} 是后端接口（更具体的映射会优先匹配，不会走到这里）。
     */
    @GetMapping({"/admin/login", "/admin/screen", "/admin/dashboard",
            "/admin/records", "/admin/glossary", "/admin/logs",
            "/admin/commands", "/admin/settings", "/admin/models", "/admin/plaza"})
    public String adminRoutes() {
        return "forward:/admin.html";
    }

    /**
     * 兜底：{@code /admin/} 下任何**不是接口**的路径都交给前端路由。
     *
     * <p>为什么需要它：前端加了新页面（比如以后加 /admin/users），
     * 如果忘了同步这里的列表，刷新就会 404。
     * 用通配兜底 + 明确排除 {@code /admin/api/} 更稳。
     *
     * <p>注意 Spring 的路由优先级：更具体的映射（{@code /admin/api/**}）
     * 会先匹配，所以这里的通配不会抢走后端接口。
     */
    @GetMapping("/admin/{path:[^.]*}")
    public String adminFallback() {
        return "forward:/admin.html";
    }

    /** 公开站的通配兜底（同样排除接口前缀） */
    @GetMapping("/{path:[^.]*}")
    public String publicFallback() {
        return "forward:/public.html";
    }

    /** 公开站的前端路由 fallback */
    @GetMapping({"/library", "/plaza", "/about", "/entry/**"})
    public String publicRoutes() {
        return "forward:/public.html";
    }

    /**
     * 多段前端路由（如 {@code /library/manage}）。
     *
     * <p>为什么需要单独一条：{@code /{path:[^.]*}} 只匹配**单段**路径 ——
     * {@code /library/manage} 有两段，落不进那个正则，于是 404。
     * 实测踩过：资料库页的「新增 / 变更知识」按钮点下去是 Whitelabel Error。
     *
     * <p>限制在两段是刻意的：更深的前端路由目前没有，而通配越宽
     * 越容易把后端接口或静态资源误吞。
     */
    @GetMapping("/{p1:[^.]*}/{p2:[^.]*}")
    public String publicNestedRoutes() {
        return "forward:/public.html";
    }

    /** 明确把后端接口路径排除在外（防御性：万一有人加了通配映射） */
    public static boolean isApiPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String p : API_PREFIXES) {
            if (uri.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根路径 → 公开站首页。
     *
     * <p><b>用 forward 而不是 redirect</b>：redirect 会把地址栏变成
     * {@code /public.html}（暴露文件名），forward 则保持 {@code /} 不变。
     * 用户看到的应该是干净的路由地址，{@code .html} 只是内部实现细节。
     *
     * <p>{@code /admin/} 同理，见 {@link #adminRoot()}。
     */
    @GetMapping("/")
    public String root() {
        return "forward:/public.html";
    }

    /**
     * 管理后台根路径 → 后台首页。
     *
     * <p>刻意**不设** {@code /admin/} 到 {@code /admin.html} 的 rewrite：
     * 保留带斜杠的地址，前端 router 的 base 就是 {@code /admin/}。
     */
    @GetMapping({"/admin", "/admin/"})
    public String adminRoot() {
        return "forward:/admin.html";
    }
}
