package com.example.qqbot.publicapi;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 旧公开路径兼容层。
 *
 * <p>{@code /api/public/**} 继续可用，但内部转发到 {@code /api/public/v1/**}，
 * 并带上 {@code Deprecation} / {@code Sunset} / {@code Link}，给调用方一个明确的迁移窗口。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LegacyPublicPathFilter extends OncePerRequestFilter {

    private static final String OLD_PREFIX = "/api/public/";
    private static final String V1_PREFIX = "/api/public/v1/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith(OLD_PREFIX) && !path.startsWith(V1_PREFIX)) {
            String target = V1_PREFIX + path.substring(OLD_PREFIX.length());
            String query = request.getQueryString();
            if (query != null && !query.isBlank()) {
                target += "?" + query;
            }
            response.setHeader("Deprecation", "true");
            response.setHeader("Sunset", "Thu, 31 Dec 2026 23:59:59 GMT");
            response.setHeader("Link", "<" + target + ">; rel=\"successor-version\"");
            request.getRequestDispatcher(target).forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
