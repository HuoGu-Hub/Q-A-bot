package com.example.qqbot.admin;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.AdminRoleAreaRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 角色范围：没配过用默认值，配过就按配的走，配成空也算数。
 */
class AdminRoleScopeServiceTest {

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private AdminRoleAreaRepository repo;
    private AdminRoleScopeService svc;

    @BeforeEach
    void setUp() {
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(tmp.resolve("scope.sqlite").toString());
        db = new SqliteDatabase(props);
        db.init();
        repo = new AdminRoleAreaRepository(new Jdbc(db));
        repo.init();
        svc = new AdminRoleScopeService(repo);
        svc.reload();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("没配过时按默认范围走")
    void defaultsWhenNothingConfigured() {
        assertThat(svc.areasOf(AdminRole.VIEWER)).containsExactlyInAnyOrder(
                AdminArea.STATS, AdminArea.EVAL, AdminArea.KB, AdminArea.MAP);
        assertThat(svc.allows(AdminRole.VIEWER, "/admin/api/logs")).isFalse();
        assertThat(svc.allows(AdminRole.EDITOR, "/admin/api/logs")).isFalse();
        assertThat(svc.allows(AdminRole.ADMIN, "/admin/api/logs")).isTrue();
    }

    @Test
    @DisplayName("不属于任何区域的接口对谁都放行")
    void commonPathsAlwaysAllowed() {
        assertThat(svc.allows(AdminRole.VIEWER, "/admin/api/session")).isTrue();
        assertThat(svc.allows(AdminRole.VIEWER, "/admin/api/logout")).isTrue();
    }

    @Test
    @DisplayName("收窄后立刻生效，并且落库")
    void applyTakesEffectAndPersists() {
        svc.apply(Map.of(
                "viewer", List.of("stats"),
                "editor", List.of("stats", "kb"),
                "admin", List.of("stats", "kb", "map", "bot", "pages", "system", "users")));

        assertThat(svc.allows(AdminRole.VIEWER, "/admin/api/kb")).isFalse();
        assertThat(svc.allows(AdminRole.VIEWER, "/admin/api/overview")).isTrue();

        AdminRoleScopeService reloaded = new AdminRoleScopeService(repo);
        reloaded.reload();
        assertThat(reloaded.areasOf(AdminRole.VIEWER)).containsExactly(AdminArea.STATS);
        assertThat(reloaded.areasOf(AdminRole.EDITOR))
                .containsExactlyInAnyOrder(AdminArea.STATS, AdminArea.KB);
    }

    @Test
    @DisplayName("管理员的「账号」关不掉，否则改不回配置")
    void adminKeepsUsersArea() {
        svc.apply(Map.of(
                "viewer", List.of(),
                "editor", List.of(),
                "admin", List.of("stats")));

        assertThat(svc.areasOf(AdminRole.ADMIN))
                .containsExactlyInAnyOrder(AdminArea.STATS, AdminArea.USERS);
    }

    @Test
    @DisplayName("明确配成空 ≠ 没配过")
    void emptyIsNotDefault() {
        svc.apply(Map.of("viewer", List.of(), "editor", List.of(), "admin", List.of()));

        assertThat(svc.areasOf(AdminRole.VIEWER)).isEmpty();
        assertThat(svc.allows(AdminRole.VIEWER, "/admin/api/overview")).isFalse();

        AdminRoleScopeService reloaded = new AdminRoleScopeService(repo);
        reloaded.reload();
        assertThat(reloaded.areasOf(AdminRole.VIEWER)).isEmpty();
    }

    @Test
    @DisplayName("缺角色、未知区域、未知角色都要报错，不静默补默认值")
    void rejectsIncompleteOrUnknown() {
        assertThatThrownBy(() -> svc.apply(Map.of("viewer", List.of("stats"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少角色");

        assertThatThrownBy(() -> svc.apply(Map.of(
                "viewer", List.of("nope"), "editor", List.of(), "admin", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的区域");

        assertThatThrownBy(() -> svc.apply(Map.of(
                "viewer", List.of(), "editor", List.of(), "admin", List.of(), "owner", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的角色");
    }
}
