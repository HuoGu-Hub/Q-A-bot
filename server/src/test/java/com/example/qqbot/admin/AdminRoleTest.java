package com.example.qqbot.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 角色层级：只读 < 编辑 < 管理 */
class AdminRoleTest {

    @Test
    @DisplayName("层级比较")
    void atLeast() {
        assertThat(AdminRole.ADMIN.atLeast(AdminRole.EDITOR)).isTrue();
        assertThat(AdminRole.EDITOR.atLeast(AdminRole.VIEWER)).isTrue();
        assertThat(AdminRole.VIEWER.atLeast(AdminRole.EDITOR)).isFalse();
        assertThat(AdminRole.EDITOR.atLeast(AdminRole.ADMIN)).isFalse();
    }

    @Test
    @DisplayName("★ 脏值一律降级为只读，不能凭空升级")
    void unknownBecomesViewer() {
        assertThat(AdminRole.parse("root")).isEqualTo(AdminRole.VIEWER);
        assertThat(AdminRole.parse(null)).isEqualTo(AdminRole.VIEWER);
        assertThat(AdminRole.parseStrict("root")).isNull();
    }

    @Test
    @DisplayName("解析不区分大小写与首尾空格")
    void parseIsForgiving() {
        assertThat(AdminRole.parse(" ADMIN ")).isEqualTo(AdminRole.ADMIN);
        assertThat(AdminRole.parseStrict("Editor")).isEqualTo(AdminRole.EDITOR);
    }
}
