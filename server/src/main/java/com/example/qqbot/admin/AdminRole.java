package com.example.qqbot.admin;

/**
 * 后台角色。
 *
 * <p>三级就够了，不做细粒度权限表：群管理要的是"谁能改内容"，不是"谁能改某一列"。
 *
 * <ul>
 *   <li>{@code viewer} 只读 —— 能看统计、知识库、日志，不能改任何东西</li>
 *   <li>{@code editor} 编辑 —— 能改知识库、命令、页面文案、地图、公告</li>
 *   <li>{@code admin}  管理 —— 全部权限，含用户管理与系统设置</li>
 * </ul>
 *
 * <p>级别用整数比较，{@link #atLeast(AdminRole)} 是唯一的判定入口 ——
 * 让"谁能做什么"只在一处定义，避免每个接口各写一遍字符串比较。
 */
public enum AdminRole {

    VIEWER("viewer", "只读", 1),
    EDITOR("editor", "编辑", 2),
    ADMIN("admin", "管理", 3);

    private final String id;
    private final String label;
    private final int level;

    AdminRole(String id, String label, int level) {
        this.id = id;
        this.label = label;
        this.level = level;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public int level() {
        return level;
    }

    /** 本级是否满足要求的那一级 */
    public boolean atLeast(AdminRole required) {
        return this.level >= required.level;
    }

    /**
     * 宽松解析：认不出来一律当只读。
     *
     * <p>用在**读库**的时候：老库里的脏值不该让某个账号凭空变成管理员。
     * 写入路径应当用 {@link #parseStrict(String)}，非法值会被拒掉。
     */
    public static AdminRole parse(String raw) {
        AdminRole r = parseStrict(raw);
        return r == null ? VIEWER : r;
    }

    /** 严格解析：认不出来返回 {@code null}，由调用方决定怎么报错 */
    public static AdminRole parseStrict(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        for (AdminRole r : values()) {
            if (r.id.equals(s)) {
                return r;
            }
        }
        return null;
    }
}
