package com.example.qqbot.admin;

/**
 * 后台账号的**对外视图**。
 *
 * <p>刻意不含 {@code passwordHash}：这个 record 会被直接序列化成 JSON 发给前端。
 * 哈希只在 {@code AdminUserService} 内部与仓库之间流转，不进类型层能看见的地方。
 */
public record AdminUser(
        long id,
        String username,
        String displayName,
        AdminRole role,
        boolean enabled,
        String createdAt,
        String lastLoginAt) {

    /** 界面上怎么称呼这个人：优先昵称，没有就用账号名 */
    public String display() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }
}
