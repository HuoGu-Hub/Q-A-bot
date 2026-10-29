package com.example.qqbot.incoming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件入口鉴权。重点不是“能通过”，而是“没有 Token 一定不能通过”。
 */
class OneBotAuthFilterTest {

    private static OneBotAuthFilter filter(String token) {
        return new OneBotAuthFilter(() -> token);
    }

    @Test
    @DisplayName("其他路径不受影响")
    void otherPathsPass() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/public/stats");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter("").doFilter(req, res, (rq, rs) -> called.set(true));

        assertThat(called).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("携带正确 Bearer Token 才放行")
    void validTokenPasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/onebot/event");
        req.addHeader("Authorization", "Bearer secret-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter("secret-token").doFilter(req, res, (rq, rs) -> called.set(true));

        assertThat(called).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("缺少 Token 返回 401")
    void missingTokenRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/onebot/event");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter("secret-token").doFilter(req, res, (rq, rs) -> called.set(true));

        assertThat(called).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("错误 Token 返回 401")
    void wrongTokenRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/onebot/event");
        req.addHeader("Authorization", "Bearer nope");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter("secret-token").doFilter(req, res, (rq, rs) -> called.set(true));

        assertThat(called).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("未配置 Token 时拒绝全部上报，而不是放行")
    void blankTokenFailsClosed() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/onebot/event");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter("").doFilter(req, res, (rq, rs) -> called.set(true));

        assertThat(called).isFalse();
        assertThat(res.getStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("带分号路径参数仍然要鉴权，不能借路径差异绕过")
    void semicolonPathStillNeedsToken() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/onebot/event;x");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter("secret-token").doFilter(req, res, (rq, rs) -> called.set(true));

        assertThat(called).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("超过 1 MB 的事件体先拒绝")
    void oversizedBodyRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/onebot/event");
        req.addHeader("Authorization", "Bearer secret-token");
        req.setContent(new byte[2 * 1024 * 1024]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter("secret-token").doFilter(req, res, (rq, rs) -> called.set(true));

        assertThat(called).isFalse();
        assertThat(res.getStatus()).isEqualTo(413);
    }
}
