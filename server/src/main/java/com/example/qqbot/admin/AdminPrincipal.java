package com.example.qqbot.admin;

/**
 * 当前请求的登录身份。
 *
 * <p>由 {@link AdminAuthFilter} 在通过校验后放进请求属性，
 * 控制器用它做"只能改自己密码""不能删自己"这类判断。
 */
public record AdminPrincipal(long id, String username, String displayName, AdminRole role,
                             String qq, boolean qqVerified) {

    public static AdminPrincipal of(AdminUser u) {
        return new AdminPrincipal(u.id(), u.username(), u.displayName(), u.role(),
                u.qq(), u.qqVerified());
    }

    public String display() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }
}
