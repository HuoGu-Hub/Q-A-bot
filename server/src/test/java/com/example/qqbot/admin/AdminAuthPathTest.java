package com.example.qqbot.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台路径归一化。分号路径曾经能让编辑越过 ADMIN 校验。
 */
class AdminAuthPathTest {

    @Test
    @DisplayName("普通后台路径原样归一化")
    void normalPath() {
        assertThat(AdminAuthFilter.canonicalPath(
                new MockHttpServletRequest("POST", "/admin/api/users")))
                .isEqualTo("/admin/api/users");
    }

    @Test
    @DisplayName("分号矩阵参数一律拒绝")
    void semicolonRejected() {
        assertThat(AdminAuthFilter.canonicalPath(
                new MockHttpServletRequest("POST", "/admin/api;x/users")))
                .isNull();
        assertThat(AdminAuthFilter.canonicalPath(
                new MockHttpServletRequest("POST", "/admin/api/users;x")))
                .isNull();
    }

    @Test
    @DisplayName("点段与编码分隔符一律拒绝")
    void traversalRejected() {
        assertThat(AdminAuthFilter.canonicalPath(
                new MockHttpServletRequest("GET", "/admin/assets/../api/users")))
                .isNull();
        assertThat(AdminAuthFilter.canonicalPath(
                new MockHttpServletRequest("GET", "/admin/assets/..%2fapi/users")))
                .isNull();
        assertThat(AdminAuthFilter.canonicalPath(
                new MockHttpServletRequest("GET", "/admin/api/users%3Bx")))
                .isNull();
    }
}
