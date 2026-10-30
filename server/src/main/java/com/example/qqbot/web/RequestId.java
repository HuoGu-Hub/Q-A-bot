package com.example.qqbot.web;

import org.slf4j.MDC;

/**
 * 当前请求的关联标识。
 *
 * <p>由 {@link RequestIdFilter} 写入 MDC，错误响应与日志都带上它。
 * 控制器与过滤器不需要自己生成，直接读这里。
 */
public final class RequestId {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private RequestId() {
    }

    static void set(String id) {
        MDC.put(MDC_KEY, id);
    }

    static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id == null || id.isBlank() ? "unknown" : id;
    }
}
