package com.example.qqbot.admin;

import com.example.qqbot.config.AdminProperties;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.AdminAuditRepository;
import com.example.qqbot.persistence.AdminUserRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证层验收。重点在三条安全默认：
 * 没账号时彻底不可用、错误凭据不给会话、失败锁定按「来源 + 账号」隔离。
 */
class AdminAuthTest {

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private AdminProperties props;
    private AdminUserService users;

    @BeforeEach
    void setUp() {
        db = new SqliteDatabase(dbProps(tmp.resolve("auth.sqlite").toString()));
        db.init();
        Jdbc jdbc = new Jdbc(db);

        props = new AdminProperties();
        props.setPassword("bootstrap-pw");
        props.setLoginMaxFailures(3);
        props.setLoginFailureWindowSeconds(600);
        props.setLoginLockSeconds(600);

        AdminUserRepository repo = new AdminUserRepository(jdbc);
        repo.init();
        AdminAuditRepository audit = new AdminAuditRepository(jdbc);
        audit.init();
        users = new AdminUserService(repo, audit, props);
        users.init();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private static PersistenceProperties dbProps(String path) {
        PersistenceProperties p = new PersistenceProperties();
        p.setDb(path);
        return p;
    }

    private AdminAuth auth() {
        return new AdminAuth(props, users);
    }

    @Test
    @DisplayName("★ 没有任何账号时，后台彻底不可用")
    void notConfiguredMeansNoLogin() {
        // 独立的空库：不能用 setUp 那个，它已经引导出 admin 了
        SqliteDatabase emptyDb = new SqliteDatabase(
                dbProps(tmp.resolve("empty.sqlite").toString()));
        emptyDb.init();
        try {
            Jdbc jdbc = new Jdbc(emptyDb);
            AdminUserRepository repo = new AdminUserRepository(jdbc);
            repo.init();
            AdminAuditRepository audit = new AdminAuditRepository(jdbc);
            audit.init();

            AdminProperties empty = new AdminProperties();
            empty.setPassword("");
            AdminUserService noUsers = new AdminUserService(repo, audit, empty);
            noUsers.init();
            AdminAuth a = new AdminAuth(empty, noUsers);

            assertThat(a.isConfigured()).isFalse();
            assertThat(a.login("admin", "anything", "test").outcome())
                    .isEqualTo(AdminAuth.LoginOutcome.DISABLED);
            assertThat(a.validate("whatever")).isFalse();
        } finally {
            emptyDb.close();
        }
    }

    @Test
    @DisplayName("登录成功给 token，会话里带得出身份")
    void loginFlow() {
        AdminAuth a = auth();

        assertThat(a.login("admin", "wrong", "t").outcome())
                .isEqualTo(AdminAuth.LoginOutcome.BAD_CREDENTIALS);

        AdminAuth.LoginAttempt ok = a.login("admin", "bootstrap-pw", "t");
        assertThat(ok.outcome()).isEqualTo(AdminAuth.LoginOutcome.OK);
        assertThat(ok.token()).isNotBlank();
        assertThat(a.validate(ok.token())).isTrue();
        assertThat(a.principal(ok.token())).get()
                .extracting(AdminPrincipal::username, AdminPrincipal::role)
                .containsExactly("admin", AdminRole.ADMIN);
    }

    @Test
    @DisplayName("退出后 token 立刻失效")
    void logoutInvalidatesToken() {
        AdminAuth a = auth();
        String token = a.login("admin", "bootstrap-pw", "t").token();

        a.logout(token);

        assertThat(a.validate(token)).isFalse();
        assertThat(a.sessionCount()).isZero();
    }

    @Test
    @DisplayName("伪造的 token 一律不通过")
    void forgedTokensRejected() {
        AdminAuth a = auth();

        assertThat(a.validate(null)).isFalse();
        assertThat(a.validate("")).isFalse();
        assertThat(a.validate("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).isFalse();
    }

    @Test
    @DisplayName("会话过期后失效")
    void expiredSessionRejected() {
        props.setSessionHours(-1);
        AdminAuth a = auth();
        String token = a.login("admin", "bootstrap-pw", "t").token();

        assertThat(a.validate(token)).isFalse();
    }

    @Test
    @DisplayName("每次登录给不同的 token（互不影响）")
    void tokensAreIndependent() {
        AdminAuth a = auth();
        String first = a.login("admin", "bootstrap-pw", "t").token();
        String second = a.login("admin", "bootstrap-pw", "t").token();

        assertThat(first).isNotEqualTo(second);
        a.logout(first);
        assertThat(a.validate(first)).isFalse();
        assertThat(a.validate(second)).as("退出一个会话不该影响另一个").isTrue();
    }

    @Test
    @DisplayName("★ 连续失败达到阈值后锁定")
    void locksAfterRepeatedFailures() {
        AdminAuth a = auth();

        for (int i = 0; i < 3; i++) {
            a.login("admin", "nope", "1.2.3.4");
        }

        AdminAuth.LoginAttempt locked = a.login("admin", "bootstrap-pw", "1.2.3.4");
        assertThat(locked.outcome()).isEqualTo(AdminAuth.LoginOutcome.LOCKED);
        assertThat(locked.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("★ 锁定按「来源 + 账号」隔离：别人被锁不影响我")
    void lockoutIsPerUser() {
        users.create("other", null, "password123", AdminRole.EDITOR);
        AdminAuth a = auth();

        for (int i = 0; i < 3; i++) {
            a.login("other", "nope", "10.0.0.1");
        }

        assertThat(a.login("other", "password123", "10.0.0.1").outcome())
                .isEqualTo(AdminAuth.LoginOutcome.LOCKED);
        assertThat(a.login("admin", "bootstrap-pw", "10.0.0.1").outcome())
                .as("同一个来源、不同账号，不该被连坐")
                .isEqualTo(AdminAuth.LoginOutcome.OK);
    }

    @Test
    @DisplayName("★ 角色矩阵：只读看、编辑写、管理员管人")
    void roleMatrix() {
        assertThat(AdminAuthFilter.requiredRole("/admin/api/stats", "GET"))
                .isEqualTo(AdminRole.VIEWER);
        assertThat(AdminAuthFilter.requiredRole("/admin/api/kb/blocks", "POST"))
                .isEqualTo(AdminRole.EDITOR);
        assertThat(AdminAuthFilter.requiredRole("/admin/api/users", "GET"))
                .isEqualTo(AdminRole.ADMIN);
        assertThat(AdminAuthFilter.requiredRole("/admin/api/users/3/password", "POST"))
                .isEqualTo(AdminRole.ADMIN);
        // 退出与改自己密码对任何已登录账号都开放，否则只读账号连退出都点不了
        assertThat(AdminAuthFilter.requiredRole("/admin/api/logout", "POST"))
                .isEqualTo(AdminRole.VIEWER);
        assertThat(AdminAuthFilter.requiredRole("/admin/api/password", "POST"))
                .isEqualTo(AdminRole.VIEWER);
        assertThat(AdminAuthFilter.requiredRole("/admin/api/session", "GET"))
                .isEqualTo(AdminRole.VIEWER);
    }

    @Test
    @DisplayName("IP 只存哈希，不存原始值")
    void ipIsHashed() {
        String hashed = AdminAuthFilter.hashIp("192.168.1.100");

        assertThat(hashed).isNotBlank().doesNotContain("192.168").hasSize(16);
        assertThat(AdminAuthFilter.hashIp("192.168.1.100")).isEqualTo(hashed);
        assertThat(AdminAuthFilter.hashIp(null)).isEmpty();
    }
}
