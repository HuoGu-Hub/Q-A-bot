package com.example.qqbot.incoming;

import com.example.qqbot.web.ProblemWriter;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 事件入口鉴权：只允许携带 NapCat 共享密钥的请求进入 {@code /onebot/**}。
 *
 * <p>NapCat 的 HTTP 客户端不发 {@code Authorization: Bearer}，它把
 * {@code x-signature: sha1=<HMAC-SHA1(token, body)>} 放在头上。这里两种都认：
 * Bearer 用于手工测试和兼容其他协议端，x-signature 用于 NapCat。
 *
 * <p>Token 为空时**拒绝全部事件**，不是放行。
 */
@Component
public class OneBotAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OneBotAuthFilter.class);

    /** 与 Nginx 的 1 MB 上限保持一致 */
    private static final long MAX_BODY_BYTES = 1024 * 1024L;

    private static final String SIGNATURE_HEADER = "x-signature";

    private final InboundTokenPolicy policy;
    private final ProblemWriter problems;

    public OneBotAuthFilter(InboundTokenPolicy policy, ProblemWriter problems) {
        this.policy = policy;
        this.problems = problems;
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

        String token = policy.getInboundToken();
        if (!StringUtils.hasText(token)) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "ONEBOT_DISABLED", "事件入口未配置 Token");
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "事件体过大");
            return;
        }

        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && constantEquals("Bearer " + token, auth)) {
            chain.doFilter(request, response);
            return;
        }

        String signature = request.getHeader(SIGNATURE_HEADER);
        if (signature != null) {
            byte[] body;
            try {
                body = readBody(request);
            } catch (BodyTooLarge e) {
                reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "事件体过大");
                return;
            }
            String expected = "sha1=" + hmacSha1(token, body);
            if (constantEquals(expected, signature.trim())) {
                chain.doFilter(new CachedBodyRequest(request, body), response);
                return;
            }
        }

        log.warn("[ONEBOT] 事件入口鉴权失败，来源 {}", request.getRemoteAddr());
        reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未授权");
    }

    private static boolean constantEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha1(String token, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 不可用", e);
        }
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try (ServletInputStream in = request.getInputStream()) {
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > MAX_BODY_BYTES) {
                    throw new BodyTooLarge();
                }
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private void reject(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        problems.write(response, status, code, message);
    }

    private static final class BodyTooLarge extends IOException {
    }

    /** 过滤器和控制器都要读 body：缓存字节后重放一次 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步读取，不需要异步回调
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
