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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号服务的验收。跑在临时库上，覆盖三种"能把后台锁死"的操作。
 */
class AdminUserServiceTest {

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        db = new SqliteDatabase(dbProps(tmp.resolve("admin-user.sqlite").toString()));
        db.init();
        Jdbc jdbc = new Jdbc(db);
        AdminProperties props = new AdminProperties();
        props.setPassword("bootstrap-pw");
        AdminUserRepository repo = new AdminUserRepository(jdbc);
        repo.init();
        AdminAuditRepository audit = new AdminAuditRepository(jdbc);
        audit.init();
        service = new AdminUserService(repo, audit, props);
        service.init();
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

    @Test
    @DisplayName("★ 空库 + ADMIN_PASSWORD → 自动建出 admin 账号")
    void bootstrapsFirstAdmin() {
        assertThat(service.hasUsers()).isTrue();
        assertThat(service.list()).hasSize(1);
        assertThat(service.list().get(0).username()).isEqualTo("admin");
        assertThat(service.list().get(0).role()).isEqualTo(AdminRole.ADMIN);
        assertThat(service.authenticate("admin", "bootstrap-pw")).isPresent();
    }

    @Test
    @DisplayName("QQ 号可作为登录名，且不能重复绑定")
    void qqIsLoginAliasAndUnique() {
        AdminUser u = service.create("quser", null, "password1234", AdminRole.VIEWER, "10001");
        assertThat(u.qq()).isEqualTo("10001");
        assertThat(u.qqVerified()).isFalse();
        assertThat(service.authenticate("10001", "password1234")).isPresent();

        assertThatThrownBy(() -> service.create("quser2", null, "password1234",
                AdminRole.VIEWER, "10001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QQ");

        service.markQqVerified(u.id());
        assertThat(service.find(u.id()).orElseThrow().qqVerified()).isTrue();
    }

    @Test
    @DisplayName("账号名不区分大小写，重复建会被拒")
    void usernameIsCaseInsensitive() {
        service.create("Editor01", null, "password1234", AdminRole.EDITOR);

        assertThatThrownBy(() -> service.create("editor01", null, "password1234", AdminRole.VIEWER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
        assertThat(service.authenticate("EDITOR01", "password1234")).isPresent();
    }

    @Test
    @DisplayName("账号名与密码的格式校验")
    void validatesInput() {
        assertThatThrownBy(() -> service.create("a b", null, "password1234", AdminRole.VIEWER))
                .hasMessageContaining("账号名");
        assertThatThrownBy(() -> service.create("ab", null, "password1234", AdminRole.VIEWER))
                .hasMessageContaining("账号名");
        assertThatThrownBy(() -> service.create("okname", null, "short", AdminRole.VIEWER))
                .hasMessageContaining("至少");
    }

    @Test
    @DisplayName("★ 不能删掉最后一个可用的管理员")
    void keepsAtLeastOneAdmin() {
        long adminId = service.list().get(0).id();

        assertThatThrownBy(() -> service.delete(adminId, 999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最后一个");
    }

    @Test
    @DisplayName("★ 不能把最后一个管理员降级或停用")
    void cannotDemoteLastAdmin() {
        long adminId = service.list().get(0).id();

        assertThatThrownBy(() -> service.update(adminId, null, AdminRole.EDITOR, true))
                .hasMessageContaining("最后一个");
        assertThatThrownBy(() -> service.update(adminId, null, AdminRole.ADMIN, false))
                .hasMessageContaining("最后一个");
    }

    @Test
    @DisplayName("有第二个管理员之后，第一个就可以降级了")
    void allowsDemoteWhenAnotherAdminExists() {
        service.create("second", null, "password1234", AdminRole.ADMIN);
        long adminId = service.list().get(0).id();

        AdminUser u = service.update(adminId, null, AdminRole.EDITOR, true);

        assertThat(u.role()).isEqualTo(AdminRole.EDITOR);
    }

    @Test
    @DisplayName("不能删除自己")
    void cannotDeleteSelf() {
        long id = service.list().get(0).id();

        assertThatThrownBy(() -> service.delete(id, id))
                .hasMessageContaining("不能删除自己");
    }

    @Test
    @DisplayName("停用的账号登不进来，也验不过原密码")
    void disabledUserCannotLogin() {
        AdminUser u = service.create("temp", null, "password1234", AdminRole.VIEWER);
        service.update(u.id(), null, AdminRole.VIEWER, false);

        assertThat(service.authenticate("temp", "password1234")).isEmpty();
    }

    @Test
    @DisplayName("改密码之后旧密码失效")
    void changePassword() {
        AdminUser u = service.create("temp", null, "password1234", AdminRole.VIEWER);
        service.changePassword(u.id(), "newpassword12");

        assertThat(service.authenticate("temp", "password1234")).isEmpty();
        assertThat(service.authenticate("temp", "newpassword12")).isPresent();
    }
}
