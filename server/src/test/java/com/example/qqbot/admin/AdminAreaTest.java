package com.example.qqbot.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 区域与路径的对应关系。这张表错了，后台要么把接口全锁死，要么把该藏的漏出去。
 */
class AdminAreaTest {

    @Test
    @DisplayName("接口按整段前缀归到区域")
    void mapsPathsToAreas() {
        assertThat(AdminArea.of("/admin/api/kb")).contains(AdminArea.KB);
        assertThat(AdminArea.of("/admin/api/kb/blocks")).contains(AdminArea.KB);
        assertThat(AdminArea.of("/admin/api/users/scopes")).contains(AdminArea.USERS);
        assertThat(AdminArea.of("/admin/api/logs/stream")).contains(AdminArea.SYSTEM);
        assertThat(AdminArea.of("/admin/api/commands/12/acl")).contains(AdminArea.BOT);
    }

    @Test
    @DisplayName("前缀按段比，/admin/api/kbx 不算知识库")
    void prefixIsSegmentAware() {
        assertThat(AdminArea.of("/admin/api/kbx")).isEmpty();
        assertThat(AdminArea.of("/admin/api/user")).isEmpty();
    }

    @Test
    @DisplayName("任何身份都要够得着的接口不属于任何区域")
    void commonPathsHaveNoArea() {
        assertThat(AdminArea.of("/admin/api/session")).isEmpty();
        assertThat(AdminArea.of("/admin/api/logout")).isEmpty();
        assertThat(AdminArea.of("/admin/api/password")).isEmpty();
        assertThat(AdminArea.of("/admin/api/me/qq/verify")).isEmpty();
    }

    @Test
    @DisplayName("默认范围与页面上摆出来的入口一致")
    void defaultsMatchNavigation() {
        assertThat(AdminArea.defaultsOf(AdminRole.VIEWER))
                .containsExactlyInAnyOrder(AdminArea.STATS, AdminArea.EVAL, AdminArea.KB, AdminArea.MAP);
        assertThat(AdminArea.defaultsOf(AdminRole.EDITOR))
                .containsExactlyInAnyOrder(AdminArea.STATS, AdminArea.EVAL, AdminArea.KB, AdminArea.MAP,
                        AdminArea.BOT, AdminArea.PAGES);
        assertThat(AdminArea.defaultsOf(AdminRole.ADMIN))
                .containsExactlyInAnyOrder(AdminArea.values());
    }

    @Test
    @DisplayName("只有「管理 × 账号」必须保留")
    void usersAreaIsMandatoryForAdmin() {
        assertThat(AdminArea.USERS.mustKeepFor(AdminRole.ADMIN)).isTrue();
        assertThat(AdminArea.USERS.mustKeepFor(AdminRole.EDITOR)).isFalse();
        assertThat(AdminArea.STATS.mustKeepFor(AdminRole.ADMIN)).isFalse();
    }

    @Test
    @DisplayName("存库形状可来回转换，未知 id 被丢掉")
    void csvRoundTrip() {
        String csv = AdminArea.join(Set.of(AdminArea.KB, AdminArea.STATS));
        assertThat(csv).isEqualTo("stats,kb");
        assertThat(AdminArea.parseCsv(csv))
                .containsExactlyInAnyOrder(AdminArea.STATS, AdminArea.KB);
        assertThat(AdminArea.parseCsv("stats,nope")).containsExactly(AdminArea.STATS);
        assertThat(AdminArea.parseCsv("")).isEmpty();
        assertThat(AdminArea.parseCsv(null)).isEmpty();
    }
}
