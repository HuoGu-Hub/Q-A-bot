package com.example.qqbot.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实客户端 IP 与 Origin 校验。多用户后台公开在公网时，这两条是限流与 CSRF 的基础。
 */
class AdminClientIpTest {

    @Test
    @DisplayName("直连来源是回环时，信任 Nginx 覆盖写的 X-Real-IP")
    void trustsRealIpBehindLoopbackProxy() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Real-IP", "203.0.113.9");
        assertThat(AdminClientIp.of(req)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("非回环来源伪造 X-Real-IP 无效")
    void ignoresSpoofedRealIpFromPublicPeer() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("198.51.100.7");
        req.addHeader("X-Real-IP", "203.0.113.9");
        assertThat(AdminClientIp.of(req)).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("HTTPS 由 X-Forwarded-Proto 判断")
    void detectsHttps() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-Proto", "https");
        assertThat(AdminClientIp.isHttps(req)).isTrue();

        MockHttpServletRequest plain = new MockHttpServletRequest();
        assertThat(AdminClientIp.isHttps(plain)).isFalse();
    }

    @Test
    @DisplayName("写操作的 Origin 必须和 Host 同源")
    void checksOrigin() {
        MockHttpServletRequest ok = new MockHttpServletRequest();
        ok.addHeader("Host", "admin.example.com");
        ok.addHeader("Origin", "https://admin.example.com");
        assertThat(AdminAuthFilter.originAllowed(ok)).isTrue();

        MockHttpServletRequest bad = new MockHttpServletRequest();
        bad.addHeader("Host", "admin.example.com");
        bad.addHeader("Origin", "https://evil.example.net");
        assertThat(AdminAuthFilter.originAllowed(bad)).isFalse();

        MockHttpServletRequest absent = new MockHttpServletRequest();
        absent.addHeader("Host", "admin.example.com");
        assertThat(AdminAuthFilter.originAllowed(absent)).isTrue();
    }
}
