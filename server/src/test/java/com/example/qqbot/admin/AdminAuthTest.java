package com.example.qqbot.admin;

import com.example.qqbot.config.AdminProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证层验收测试。重点在**安全默认**：
 * 没配密码时必须彻底不可用，而不是"无密码就能进"。
 */
class AdminAuthTest {

    private AdminAuth authWith(String password) {
        AdminProperties props = new AdminProperties();
        props.setPassword(password);
        return new AdminAuth(props);
    }

    /**
     * 登录并取出 token。
     *
     * <p>{@code login} 现在返回 {@link AdminAuth.LoginAttempt}（调用方要能区分
     * 「密码错」和「被限流」），所以测试里统一走这个助手，免得八处各写一遍。
     */
    private static String tokenOf(AdminAuth auth, String password) {
        return auth.login(password, "test").token();
    }

    @Test
    @DisplayName("★ 没配密码时，后台彻底不可用（不返回 token）")
    void notConfiguredMeansNoLogin() {
        AdminAuth auth = authWith("");

        assertThat(auth.isConfigured()).isFalse();
        assertThat(tokenOf(auth, "")).as("空密码也不能登录").isNull();
        assertThat(tokenOf(auth, "anything")).isNull();
        assertThat(auth.validate("whatever")).isFalse();
    }

    @Test
    @DisplayName("密码不对不给 token；对了给 token 且能校验通过")
    void loginFlow() {
        AdminAuth auth = authWith("s3cret");

        assertThat(tokenOf(auth, "wrong")).isNull();

        String token = tokenOf(auth, "s3cret");
        assertThat(token).isNotBlank();
        assertThat(auth.validate(token)).isTrue();
        assertThat(auth.sessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("退出后 token 立刻失效")
    void logoutInvalidatesToken() {
        AdminAuth auth = authWith("s3cret");
        String token = tokenOf(auth, "s3cret");

        auth.logout(token);

        assertThat(auth.validate(token)).isFalse();
        assertThat(auth.sessionCount()).isZero();
    }

    @Test
    @DisplayName("伪造的 token 一律不通过")
    void forgedTokensRejected() {
        AdminAuth auth = authWith("s3cret");

        assertThat(auth.validate(null)).isFalse();
        assertThat(auth.validate("")).isFalse();
        assertThat(auth.validate("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).isFalse();
    }

    @Test
    @DisplayName("会话过期后失效")
    void expiredSessionRejected() {
        AdminProperties props = new AdminProperties();
        props.setPassword("s3cret");
        // 有效期设成**负数**小时 —— 保证过期时刻稳稳落在过去。
        //
        // 原来设的是 0：过期时刻恰好等于登录那一刻，而校验用的是
        // Instant.now().isAfter(expiresAt)。两次 Instant.now() 落在同一微秒时
        // 判定成「还没过期」，测试就红一次 —— 单跑几乎撞不上，跑全量时偶发
        // （2026-10-01 撞到一次）。负数把这段竞态彻底去掉，语义也更直白。
        props.setSessionHours(-1);
        AdminAuth auth = new AdminAuth(props);

        String token = tokenOf(auth, "s3cret");

        assertThat(auth.validate(token)).isFalse();
    }

    @Test
    @DisplayName("每次登录给不同的 token（互不影响）")
    void tokensAreIndependent() {
        AdminAuth auth = authWith("s3cret");

        String a = tokenOf(auth, "s3cret");
        String b = tokenOf(auth, "s3cret");

        assertThat(a).isNotEqualTo(b);
        auth.logout(a);
        assertThat(auth.validate(a)).isFalse();
        assertThat(auth.validate(b)).as("退出一个会话不该影响另一个").isTrue();
    }

    @Test
    @DisplayName("常量时间比较：相等/不等/长度不同/空值")
    void constantTimeCompare() {
        assertThat(AdminAuth.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(AdminAuth.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(AdminAuth.constantTimeEquals("abc", "abcd")).as("长度不同也不相等").isFalse();
        assertThat(AdminAuth.constantTimeEquals("", "")).isTrue();
        assertThat(AdminAuth.constantTimeEquals(null, "x")).isFalse();
        assertThat(AdminAuth.constantTimeEquals("x", null)).isFalse();
    }

    @Test
    @DisplayName("总开关关掉时后台也不可用")
    void disabledSwitch() {
        AdminProperties props = new AdminProperties();
        props.setPassword("s3cret");
        props.setEnabled(false);

        assertThat(new AdminAuth(props).isConfigured()).isFalse();
    }

    @Test
    @DisplayName("IP 只存哈希，不存原始值")
    void ipIsHashed() {
        String hashed = AdminAuthFilter.hashIp("192.168.1.100");

        assertThat(hashed).isNotBlank().doesNotContain("192.168").hasSize(16);
        assertThat(AdminAuthFilter.hashIp("192.168.1.100")).as("同样输入同样输出").isEqualTo(hashed);
        assertThat(AdminAuthFilter.hashIp(null)).isEmpty();
    }
}
