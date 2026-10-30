package com.example.qqbot.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 给每个请求分配一个关联标识。
 *
 * <p>放在所有业务过滤器之前，保证 401、403、413 这类在控制器之前发生的错误
 * 也能拿到同一个 requestId。响应头回传，前端报错时可以直接贴给运维。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(RequestId.HEADER);
        String id = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        RequestId.set(id);
        response.setHeader(RequestId.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            RequestId.clear();
        }
    }
}
