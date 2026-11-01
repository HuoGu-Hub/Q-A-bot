package com.example.qqbot.admin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 后台的功能区域。
 *
 * <p>角色回答"能改多少"，区域回答"能看哪几块"，两者正交：
 * 区域只决定某一组接口**够不够得着**，写操作仍然由 {@link AdminRole} 的级别判定。
 * 所以把「系统」勾给只读账号，他看到的是日志，一条也改不动。
 *
 * <p><b>区域在每次请求时现算</b>，不写进会话快照。管理员改完范围，
 * 已经在线的访客下一次点击就生效 —— 不用等他自己退出再登录。
 *
 * <p><b>路径前缀是唯一的映射依据</b>：一个接口不属于任何区域时一律放行，
 * 这条规则是给 {@code /session}、{@code /logout}、改密、绑定 QQ 这类
 * "任何身份都必须够得着"的接口留的。新增控制器要记得在这里挂上区域，
 * 否则它会对所有角色可见。
 */
public enum AdminArea {

    /** 统计：总览、记录、检索几个 tab 共用同一批只读接口 */
    STATS("stats", "统计", EnumSet.of(AdminRole.VIEWER, AdminRole.EDITOR, AdminRole.ADMIN),
            "/admin/api/overview", "/admin/api/keywords", "/admin/api/misses",
            "/admin/api/cosine", "/admin/api/sources", "/admin/api/visits",
            "/admin/api/traffic", "/admin/api/llm", "/admin/api/records",
            "/admin/api/annotate"),

    /** 知识库：术语、分类、文档、蒸馏、wiki */
    KB("kb", "知识库", EnumSet.of(AdminRole.VIEWER, AdminRole.EDITOR, AdminRole.ADMIN),
            "/admin/api/kb", "/admin/api/distill"),

    /** 地图：点位、图层、备注 */
    MAP("map", "地图", EnumSet.of(AdminRole.VIEWER, AdminRole.EDITOR, AdminRole.ADMIN),
            "/admin/api/map"),

    /** Bot 配置：指令、看板、设置、提示词、语音 */
    BOT("bot", "Bot 配置", EnumSet.of(AdminRole.EDITOR, AdminRole.ADMIN),
            "/admin/api/commands", "/admin/api/boards", "/admin/api/board-records",
            "/admin/api/acl", "/admin/api/settings", "/admin/api/system-replies",
            "/admin/api/voice", "/admin/api/prompts", "/admin/api/system-aliases"),

    /** 页面信息：公开站的固定文案与轮播 */
    PAGES("pages", "页面信息", EnumSet.of(AdminRole.EDITOR, AdminRole.ADMIN),
            "/admin/api/pages", "/admin/api/carousel"),

    /** 系统：模型配置、日志、护栏指标 */
    SYSTEM("system", "系统", EnumSet.of(AdminRole.ADMIN),
            "/admin/api/models", "/admin/api/logs", "/admin/api/guard"),

    /** 账号：名册与可访问范围本身 */
    USERS("users", "账号", EnumSet.of(AdminRole.ADMIN),
            "/admin/api/users");

    private final String id;
    private final String label;
    private final Set<AdminRole> defaults;
    private final List<String> prefixes;

    AdminArea(String id, String label, Set<AdminRole> defaults, String... prefixes) {
        this.id = id;
        this.label = label;
        this.defaults = defaults;
        this.prefixes = List.of(prefixes);
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** 还没配过范围时，这一级默认拿到的区域 —— 与页面上摆出来的入口一致 */
    public static Set<AdminArea> defaultsOf(AdminRole role) {
        Set<AdminArea> out = EnumSet.noneOf(AdminArea.class);
        for (AdminArea a : values()) {
            if (a.defaults.contains(role)) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * 这一级是不是**必须**保留这个区域。
     *
     * <p>只对「管理 × 账号」成立：账号页是改回范围配置的唯一入口，
     * 允许把它关掉，等于给自己留一把没有备份的锁。
     */
    public boolean mustKeepFor(AdminRole role) {
        return this == USERS && role == AdminRole.ADMIN;
    }

    /** 路径是否落在本区域。按整段前缀比，{@code /admin/api/kb} 不会吃掉 {@code /admin/api/kbx} */
    boolean matches(String path) {
        for (String p : prefixes) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 路径属于哪个区域；不属于任何区域返回空，调用方按放行处理 */
    public static Optional<AdminArea> of(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (AdminArea a : values()) {
            if (a.matches(path)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** 宽松解析：认不出来返回空，由调用方决定是忽略还是报错 */
    public static AdminArea parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        for (AdminArea a : values()) {
            if (a.id.equals(s)) {
                return a;
            }
        }
        return null;
    }

    /**
     * 把一串 id 解析成区域集合。
     *
     * <p>未知 id 直接丢掉：这只可能来自旧版本或手改的库，
     * 而"多认一个不存在的区域"没有任何好处。
     */
    public static Set<AdminArea> parseAll(Iterable<String> ids) {
        Set<AdminArea> out = EnumSet.noneOf(AdminArea.class);
        if (ids == null) {
            return out;
        }
        for (String raw : ids) {
            AdminArea a = parse(raw);
            if (a != null) {
                out.add(a);
            }
        }
        return out;
    }

    /** 存库用的形状：逗号分隔的 id，按枚举顺序，稳定可比 */
    public static String join(Set<AdminArea> areas) {
        List<String> ids = new ArrayList<>();
        for (AdminArea a : values()) {
            if (areas.contains(a)) {
                ids.add(a.id);
            }
        }
        return String.join(",", ids);
    }

    public static Set<AdminArea> parseCsv(String raw) {
        Set<AdminArea> out = EnumSet.noneOf(AdminArea.class);
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String s : raw.split(",")) {
            AdminArea a = parse(s);
            if (a != null) {
                out.add(a);
            }
        }
        return out;
    }
}
