package com.example.qqbot.admin;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * 管理后台看到的真实客户端 IP。
 *
 * <p>Nginx 在本机，应用看到的来源恒为 127.0.0.1。只有直连对端是回环时，
 * 才信任 Nginx 覆盖写入的 {@code X-Real-IP}；其他来源一律用 socket 地址，
 * 防止客户端自己伪造请求头。
 */
public final class AdminClientIp {

    private static final Pattern IP = Pattern.compile("[0-9a-fA-F:.]+");

    private AdminClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isLoopback(remote)) {
            return remote;
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank() && IP.matcher(real.trim()).matches()) {
            return real.trim();
        }
        return remote;
    }

    /** 是否 HTTPS。Nginx 会覆盖写 X-Forwarded-Proto，直连 HTTP 则为空 */
    public static boolean isHttps(HttpServletRequest request) {
        return request.isSecure()
                || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }

    private static boolean isLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
    }
}
