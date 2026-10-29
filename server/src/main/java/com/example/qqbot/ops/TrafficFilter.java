package com.example.qqbot.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把每个请求的耗时与状态码交给 {@link TrafficMetrics}。
 *
 * <p>用 Filter 而不是 Interceptor：Interceptor 只覆盖进了 Controller 的请求，
 * 404、静态资源、鉴权被拒的那些都看不到 —— 而"错误率"恰恰要从这些里来。
 */
@Component
public class TrafficFilter extends OncePerRequestFilter {

    private final TrafficMetrics metrics;

    public TrafficFilter(TrafficMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000L;
            metrics.record(millis, response.getStatus(), isApi(request.getRequestURI()));
        }
    }

    /**
     * 别把自己算进去。
     * 看板每刷新一次就多一条请求，不排除的话"打开监控"会推高监控自己的读数。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/admin/api/traffic");
    }

    private static boolean isApi(String uri) {
        return uri.startsWith("/api/") || uri.startsWith("/admin/api/");
    }
}
