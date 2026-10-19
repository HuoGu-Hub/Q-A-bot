package com.example.qqbot.command;

/**
 * 一条名单条目：哪个对象、在哪种名单里、指向谁。
 *
 * <p>指向的对象<b>可以是 QQ 号，也可以是群号</b>。群号的意思是「这个群里的所有人」。
 *
 * @param scope 名单种类：{@link #WHITE} 白名单 / {@link #BLACK} 黑名单
 * @param type  条目种类：{@link #UID} QQ 号 / {@link #GID} 群号
 * @param value 具体的号
 */
public record AclEntry(String scope, String type, long value) {

    public static final String WHITE = "white";
    public static final String BLACK = "black";
    public static final String UID = "uid";
    public static final String GID = "gid";

    /** 全局名单用 0 当命令 id，和真实命令 id 区分开 */
    public static final long GLOBAL = 0L;

    public boolean isWhite() {
        return WHITE.equals(scope);
    }

    public boolean isUser() {
        return UID.equals(type);
    }
}
