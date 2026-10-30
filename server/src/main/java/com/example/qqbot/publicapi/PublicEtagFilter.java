package com.example.qqbot.publicapi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * 公开 JSON 接口的 ETag。
 *
 * <p>只包小响应；地图资源点、瓦片与图片体积大，逐字节哈希不划算，
 * 它们靠文件名与长缓存保证新鲜度。
 */
@Component
public class PublicEtagFilter extends ShallowEtagHeaderFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/public/v1/")) {
            return true;
        }
        return path.contains("/carousel/image/")
                || path.contains("/map/tile")
                || path.contains("/map/resource-icon")
                || path.contains("/map/resources")
                || path.contains("/map/resource-areas");
    }
}
