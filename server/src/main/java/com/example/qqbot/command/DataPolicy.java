package com.example.qqbot.command;

import java.util.List;

/**
 * 一条命令的**数据策略**：能看哪些群的数据、跨群结果显示多少。
 *
 * <h2>为什么和准入分开</h2>
 * 「谁能用」和「能用哪些数据」是两把独立的锁。合在一起就会出现
 * 「把公告改成跨群之后，普通成员也能看别的群了」这种连带效果。
 *
 * <h2>写永远只写本群</h2>
 * 这里管的全是<b>读</b>。任何命令写入的数据归属群永远是发命令的那个群 ——
 * 否则一个人在 1 群发一条，可以伪装成 2 群的内容，跨群可见之后根本查不出来。
 *
 * @param scope    读的范围
 * @param groups   {@link DataScope#SOME} 时互相可见的群
 * @param showGroup 跨群结果里是否标出"来自哪个群"
 * @param showUser  跨群结果里是否标出对方 QQ 号
 * @param allowPrivate 能不能在私聊里用。默认<b>不能</b>：私聊里没有群管的第二双眼睛
 */
public record DataPolicy(DataScope scope, List<Long> groups, boolean showGroup, boolean showUser,
                         boolean allowPrivate) {

    /** 默认：只看本群、什么都不标、私聊不开放 —— 新建命令不加设置就是最保守的 */
    public static final DataPolicy DEFAULT =
            new DataPolicy(DataScope.GROUP, List.of(), false, false, false);

    public DataPolicy {
        scope = scope == null ? DataScope.GROUP : scope;
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /** 读的范围 */
    public enum DataScope {
        /** 只读本群 */
        GROUP,
        /** 只在给定的几个群之间互相可见 */
        SOME,
        /** 所有群 */
        ALL
    }

    /** 这条命令能不能读到某个群的数据 */
    public boolean canRead(long groupId) {
        return switch (scope) {
            case ALL -> true;
            case SOME -> groups.contains(groupId);
            case GROUP -> false;   // 具体判断由调用方补上"是不是本群"
        };
    }

    /** 结果里要不要标来源群号 */
    public boolean marksGroup() {
        return scope != DataScope.GROUP && showGroup;
    }

    /** 结果里要不要标对方 QQ */
    public boolean marksUser() {
        return scope != DataScope.GROUP && showUser;
    }
}
