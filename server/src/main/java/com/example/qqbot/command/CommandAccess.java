package com.example.qqbot.command;

/**
 * 一条命令的准入档位，五选一。
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
 */
public enum CommandAccess {

    ALL("all"),
    ADMIN("admin"),
    /** 管理员与白名单：管理员照常，另外白名单里的人也能用（不在名单里的普通成员不行） */
    ADMIN_WHITELIST("admin_whitelist"),
    OWNER("owner"),
    WHITELIST("whitelist"),
    BLACKLIST("blacklist");

    private final String code;

    CommandAccess(String code) {
        this.code = code;
    }

    public String code() {
        return code;
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
