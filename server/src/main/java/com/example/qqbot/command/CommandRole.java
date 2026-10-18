package com.example.qqbot.command;

import com.example.qqbot.onebot.model.OneBotEvent;

import java.util.Locale;

/**
 * 命令权限等级 —— 把 OneBot 的 {@code sender.role} 与后台填的 {@code min_role}
 * 归一到同一条刻度上，比较才有意义。
 *
 * <h2>认不出来时给 MEMBER，而不是"没有角色"</h2>
 * 最底层就是 {@link #MEMBER}，因为库里 {@code min_role} 的默认值也是它。
 * 若把「sender 缺失」判成无角色，那么任何不带 {@code sender} 的协议端实现
 * 都会让<b>所有</b>命令失效 —— 为了防一个只有管理员才做的事，把大家全挡在门外。
 *
 * <p>认不出来就给 MEMBER 的代价是：能证明自己是管理员的人才用得了管理员命令，
 * 证明不了的用普通命令。这正是我们要的。
 *
 * <p>私聊没有群角色，同样按 {@link #MEMBER}；于是「仅管理员」的命令在私聊用不了，
 * 而普通命令照常 —— 私聊里没有第二双眼睛，管理员命令不该在那里开放。
 */
public enum CommandRole {

    /** 普通成员，也是所有"无法判定"情况下的取值 */
    MEMBER(1),
    /** 群管理员 */
    ADMIN(2),
    /** 群主 */
    OWNER(3);

    private final int level;

    CommandRole(int level) {
        this.level = level;
    }

    /** 当前角色是否够得上要求 */
    public boolean atLeast(CommandRole required) {
        return level >= (required == null ? MEMBER : required).level;
    }

    /** 发送者的实际角色；私聊、缺 sender、认不出来的 role 都是 {@link #MEMBER} */
    public static CommandRole ofSender(OneBotEvent event) {
        if (event == null || !event.isGroupMessage() || event.getSender() == null) {
            return MEMBER;
        }
        String raw = event.getSender().path("role").asText("");
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "owner" -> OWNER;
            case "admin" -> ADMIN;
            default -> MEMBER;
        };
    }

    /** 后台填的 min_role；空值与认不出来的值都当 {@link #MEMBER} */
    public static CommandRole ofMinRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEMBER;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "owner", "群主" -> OWNER;
            case "admin", "管理员", "管理" -> ADMIN;
            default -> MEMBER;
        };
    }

    /** 给群友看的拒绝理由；{@link #MEMBER} 用不到（它是地板） */
    public String describe() {
        return switch (this) {
            case OWNER -> "这条命令只有群主能用";
            case ADMIN -> "这条命令只有群主或管理员能用";
            default -> "这条命令你暂时用不了";
        };
    }
}
