package com.example.qqbot.command;

import java.util.List;

/**
 * 准入判定的**唯一实现**。
 *
 * <h2>为什么单独抽出来</h2>
 * 这段逻辑有五个层次、两个维度（QQ 号比群号具体、单命令比全局优先），
 * 散在调用点里迟早会写歪。抽成纯函数之后可以拿用例把每一层都钉住。
 *
 * <h2>判定顺序</h2>
 * <ol>
 *   <li>这条命令的名单里，有他的 <b>QQ 号</b>：白名单放行、黑名单拒绝</li>
 *   <li>这条命令的名单里，有他所在的 <b>群号</b>：同上</li>
 *   <li>全局名单里，有他的 <b>QQ 号</b>：同上</li>
 *   <li>全局名单里，有他所在的 <b>群号</b>：同上</li>
 *   <li>以上都没命中，按档位判</li>
 * </ol>
 *
 * <p>两条排序原则：<b>具体的先于笼统的</b>（QQ 号在群号前面），
 * <b>单命令的先于全局的</b>。所以"全局把人封了，但他挂在某条命令的白名单里"
 * 这条命令他照样能用 —— 第 1 步就放行了，走不到全局那两步。
 */
public final class AccessRules {

    private AccessRules() {
    }

    /** 单看一层名单能得出的结论 */
    enum Decision {
        ALLOW, DENY, NONE
    }

    /**
     * 这个人能不能用这条命令。
     *
     * @param access     命令的档位
     * @param role       这个人在本群的角色
     * @param userId     他的 QQ 号
     * @param groupId    所在群；私聊传 0
     * @param commandAcl 这条命令自己的名单
     * @param globalAcl  全局名单
     */
    public static boolean allows(CommandAccess access, CommandRole role,
                                 long userId, long groupId,
                                 List<AclEntry> commandAcl, List<AclEntry> globalAcl) {
        Decision decision = decide(commandAcl, userId, groupId);
        if (decision == Decision.NONE) {
            decision = decide(globalAcl, userId, groupId);
        }
        if (decision != Decision.NONE) {
            return decision == Decision.ALLOW;
        }
        return switch (access) {
            case ALL -> true;
            case ADMIN -> role.atLeast(CommandRole.ADMIN);
            // 白名单条目在 decide 阶段已经放行；走到这里说明名单没命中，
            // 所以「管理员与白名单」剩下的判断就是角色够不够
            case ADMIN_WHITELIST -> role.atLeast(CommandRole.ADMIN);
            case OWNER -> role.atLeast(CommandRole.OWNER);
            // 白名单档却没在名单里 → 不进；黑名单档且不在名单里 → 放行
            case WHITELIST -> false;
            case BLACKLIST -> true;
        };
    }

    /**
     * 这次准入是不是<b>名单里的白名单</b>放行的。
     *
     * <p>用途只有一个：本群提示这类"会改变全群"的写操作要比"能用这条命令"更严。
     * 档位只说谁能触发命令（「全体」就是谁都能触发），而白名单是管理员<b>点名</b>
     * 放进来的人 —— 和管理员一样，视为可以管理这条命令的共享内容。
     * 所以「全体」下的普通成员只能看提示、设自己的个人提示；被白名单点名的成员
     * 才能管理本群提示。
     *
     * <p>层次与 {@link #allows} 完全一致：单命令名单先于全局名单；
     * 命令名单给出了结论（放行或拒绝）就不再往下看。黑名单条目永远返回 false。
     */
    public static boolean grantedByWhitelist(long userId, long groupId,
                                             List<AclEntry> commandAcl, List<AclEntry> globalAcl) {
        Decision decision = decide(commandAcl, userId, groupId);
        if (decision == Decision.NONE) {
            decision = decide(globalAcl, userId, groupId);
        }
        return decision == Decision.ALLOW;
    }

    /** 只看一层名单：先 QQ 号后群号，先白后黑 */
    static Decision decide(List<AclEntry> acl, long userId, long groupId) {
        if (acl == null || acl.isEmpty()) {
            return Decision.NONE;
        }
        if (contains(acl, AclEntry.WHITE, AclEntry.UID, userId)) {
            return Decision.ALLOW;
        }
        if (contains(acl, AclEntry.BLACK, AclEntry.UID, userId)) {
            return Decision.DENY;
        }
        if (groupId > 0 && contains(acl, AclEntry.WHITE, AclEntry.GID, groupId)) {
            return Decision.ALLOW;
        }
        if (groupId > 0 && contains(acl, AclEntry.BLACK, AclEntry.GID, groupId)) {
            return Decision.DENY;
        }
        return Decision.NONE;
    }

    private static boolean contains(List<AclEntry> acl, String scope, String type, long value) {
        if (value <= 0) {
            return false;
        }
        for (AclEntry entry : acl) {
            if (scope.equals(entry.scope()) && type.equals(entry.type()) && entry.value() == value) {
                return true;
            }
        }
        return false;
    }
}
