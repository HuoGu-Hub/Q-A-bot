package com.example.qqbot.publicapi;

import org.springframework.core.MethodParameter;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.time.Duration;

/**
 * 公开只读接口的缓存策略。
 *
 * <p>稳定内容给 60 秒或 300 秒，检索类只给 30 秒；图片接口自己设置长缓存，
 * 这里不覆盖。错误响应不加缓存头，避免把 404 / 429 缓存住。
 */
@ControllerAdvice(assignableTypes = {
        PublicController.class,
        PublicCarouselController.class,
        PublicMapController.class
})
public class PublicCacheAdvice implements ResponseBodyAdvice<Object> {

    private static final CacheControl STABLE =
            CacheControl.maxAge(Duration.ofSeconds(60))
                    .staleWhileRevalidate(Duration.ofSeconds(30))
                    .cachePublic();

    private static final CacheControl STABLE_LONG =
            CacheControl.maxAge(Duration.ofSeconds(300))
                    .staleWhileRevalidate(Duration.ofSeconds(60))
                    .cachePublic();

    private static final CacheControl SEARCH =
            CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String path = request.getURI().getPath();
        if (!path.startsWith("/api/public/v1/") || isBinary(path)) {
            return body;
        }
        if (response instanceof org.springframework.http.server.ServletServerHttpResponse servlet
                && servlet.getServletResponse().getStatus() >= 400) {
            return body;
        }
        response.getHeaders().setCacheControl(cacheControl(path));
        return body;
    }

    private static CacheControl cacheControl(String path) {
        if (path.equals("/api/public/v1/kb/search")
                || path.equals("/api/public/v1/map/search")
                || path.equals("/api/public/v1/map/locate")) {
            return SEARCH;
        }
        if (path.equals("/api/public/v1/kb/groups")
                || path.equals("/api/public/v1/kb/page")
                || path.equals("/api/public/v1/map/catalog")
                || path.equals("/api/public/v1/map/markers")
                || path.equals("/api/public/v1/map/resource-groups")
                || path.equals("/api/public/v1/map/resources")
                || path.equals("/api/public/v1/map/resource-areas")) {
            return STABLE_LONG;
        }
        return STABLE;
    }

    private static boolean isBinary(String path) {
        return path.contains("/carousel/image/")
                || path.contains("/map/tile")
                || path.contains("/map/resource-icon");
    }
}
