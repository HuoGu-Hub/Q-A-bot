package com.example.qqbot.incoming;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 事件入口鉴权：只允许携带 NapCat 共享密钥的请求进入 {@code /onebot/**}。
 *
 * <p>为什么必须有：{@code /onebot/event} 在公网可达，事件体里的
 * {@code sender.role}、{@code group_id}、{@code user_id} 都会被下游当成事实。
 * 不校验来源，任何人都能伪造群主消息、烧模型额度、写采集库。
 *
 * <p>Token 为空时**拒绝全部事件**，不是放行。首次部署必须先在 .env 配好
 * {@code ONEBOT_TOKEN}，并在 NapCat 的 HTTP 客户端里填同一个值。
 */
@Component
public class OneBotAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OneBotAuthFilter.class);

    /** 与 Nginx 的 1 MB 上限保持一致，防未鉴权的大 body 先吃内存 */
    private static final long MAX_BODY_BYTES = 1024 * 1024L;

    private final InboundTokenPolicy policy;

    public OneBotAuthFilter(InboundTokenPolicy policy) {
        this.policy = policy;
    }

    @PostConstruct
    void logState() {
        if (StringUtils.hasText(policy.getInboundToken())) {
            log.info("[ONEBOT] 事件入口鉴权已启用");
        } else {
            log.error("[ONEBOT] 事件入口 Token 未配置，所有上报都会被拒绝。"
                    + "请在 .env 设置 ONEBOT_TOKEN，并同步到 NapCat 的 HTTP 客户端");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (!path.startsWith("/onebot/")) {
            chain.doFilter(request, response);
            return;
        }

        String expected = policy.getInboundToken();
        if (!StringUtils.hasText(expected)) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "事件入口未配置 Token");
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "事件体过大");
            return;
        }

        String provided = request.getHeader(HttpHeaders.AUTHORIZATION);
        String want = "Bearer " + expected;
        if (provided == null || !MessageDigest.isEqual(
                want.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            log.warn("[ONEBOT] 事件入口鉴权失败，来源 {}", request.getRemoteAddr());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未授权");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
