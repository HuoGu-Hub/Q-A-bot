package com.example.qqbot.command;

/**
 * 一条命令的准入档位，六选一。
 *
 * <p>前三个按角色判，后两个按名单判：
 * <ul>
 *   <li>{@link #ALL} 全体成员</li>
 *   <li>{@link #ADMIN} 群主与管理员</li>
 *   <li>{@link #ADMIN_WHITELIST} 群主、管理员，外加白名单里的人</li>
 *   <li>{@link #OWNER} 只有群主</li>
 *   <li>{@link #WHITELIST} <b>只有</b>这条命令名单里的人能用</li>
 *   <li>{@link #BLACKLIST} 名单里的人不能用，其余照旧</li>
 * </ul>
 *
 * <p>名单的优先级高于档位，见 {@link AccessRules}。
 *
 * <h2>这里是档位清单的唯一出处</h2>
 * 中文名与"要不要露名单"都长在枚举上，由 {@code GET /admin/api/commands/access-modes}
 * 下发给后台 —— 前端三处编辑器（命令、板块、/prompt）都从那份数据渲染。
 * <b>加一档只改这里</b>：界面自动多一个选项，判法由 {@link AccessRules} 的 switch 补齐，
 * 漏写那个 switch 会编译不过。
 */
public enum CommandAccess {

    ALL("all", "全体", false),
    ADMIN("admin", "管理员", false),
    /** 管理员与白名单：管理员照常，另外白名单里的人也能用（不在名单里的普通成员不行） */
    ADMIN_WHITELIST("admin_whitelist", "管理员与白名单", true),
    OWNER("owner", "群主", false),
    WHITELIST("whitelist", "白名单", true),
    BLACKLIST("blacklist", "黑名单", true);

    private final String code;
    /** 后台下拉里显示的中文名 */
    private final String label;
    /** 名单是不是这一档的判定依据 */
    private final boolean usesAcl;

    CommandAccess(String code, String label, boolean usesAcl) {
        this.code = code;
        this.label = label;
        this.usesAcl = usesAcl;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /**
     * 这一档下，名单是判定的主依据 —— 后台要露出名单编辑区。
     *
     * <p>名单对<b>所有</b>档位都生效（黑名单永远拦、白名单永远放，见 {@link AccessRules}），
     * 但只有这三档"谁能用"由名单里的人决定：不给它们录名单，等于把命令锁死或形同虚设。
     * 其余档位按角色判，名单区收起来不占地方。
     */
    public boolean usesAcl() {
        return usesAcl;
    }

    /** 给群友看的说法，拒绝时回这句 */
    public String describe(boolean inGroup) {
        return switch (this) {
            case OWNER -> inGroup ? "这条命令只有群主能用" : "这条命令要在群里用";
            case ADMIN -> inGroup ? "这条命令只有群主或管理员能用" : "这条命令要在群里用";
            case ADMIN_WHITELIST -> inGroup ? "这条命令只有管理员或白名单里的人能用" : "这条命令要在群里用";
            case WHITELIST -> "这条命令只对指定的人开放";
            case BLACKLIST -> "这条命令你暂时用不了";
            case ALL -> "";
        };
    }

    /**
     * 从库里那一列解析。
     *
     * <p>{@code member} 是旧值，等价于「全体」—— 老库升级上来时不能因此把人全挡在门外。
     * 认不出来的值也按「全体」处理：这是一个"配错就把所有人锁死"的字段，
     * 宁可放宽也不要因为一个笔误让整条命令失效（后台会把它纠正成合法值）。
     */
    public static CommandAccess of(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        String s = raw.trim().toLowerCase();
        if ("member".equals(s)) {
            return ALL;
        }
        for (CommandAccess access : values()) {
            if (access.code.equals(s)) {
                return access;
            }
        }
        return ALL;
    }
}
