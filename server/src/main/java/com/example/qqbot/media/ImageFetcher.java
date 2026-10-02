package com.example.qqbot.media;

import com.example.qqbot.onebot.model.ImageRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 下载图片并转成 base64，供视觉模型使用。
 *
 * <p><b>这里有 SSRF 防护</b>：图片 URL 是外部给的，不校验的话，
 * 攻击者可以发一个 @B@http://192.168.1.1/admin@B@ 的"图片"，
 * 让机器人去访问内网地址（探测、甚至触发副作用）。
 *
 * <p>三重限制：
 * <ol>
 *   <li>只允许 http/https，且必须是公网地址（拒绝回环、内网、链路本地、CGNAT）</li>
 *   <li>大小上限（默认 5MB），边下边数，超了立刻断开</li>
 *   <li>超时（连接 5 秒、读取 10 秒）</li>
 * </ol>
 *
 * <p>残余风险：DNS 重绑定（校验时解析成公网，下载时又解析成内网）。
 * 对本项目的威胁模型来说可接受。
 */
@Component
public class ImageFetcher {

    private static final Logger log = LoggerFactory.getLogger(ImageFetcher.class);

    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ImageCache imageCache;

    public ImageFetcher(ImageCache imageCache) {
        this.imageCache = imageCache;
    }

    /** 下载好的图片 */
    public record FetchedImage(String base64, String mimeType, int byteSize) {
    }

    /** 一次网络下载的原始结果 */
    private record Downloaded(byte[] body, String mimeType) {
    }

    /**
     * 取一张图：**先查缓存，命中就完全不产生网络流量**。
     *
     * <p>这是省流量的关键路径：QQ 的图片 URL 是 rkey 签名的、会过期，
     * 而图片段的 {@code file} 字段就是内容 MD5，所以「是不是同一张图」
     * 在发出任何请求之前就能判断出来。
     */
    public Optional<FetchedImage> fetch(ImageRef ref) {
        if (ref == null || !StringUtils.hasText(ref.url())) {
            return Optional.empty();
        }

        // ① 缓存命中 —— 零下载
        if (ref.hasKey()) {
            Optional<ImageCache.Cached> hit = imageCache.lookup(ref.key());
            if (hit.isPresent()) {
                Optional<FetchedImage> loaded = loadFromCache(hit.get());
                if (loaded.isPresent()) {
                    log.info("[MEDIA] 图片缓存命中，跳过下载：key={} 大小={} 类型={}",
                            ref.key(), ImageCache.humanSize(hit.get().size()), hit.get().mimeType());
                    return loaded;
                }
            }
        }

        // ② 未命中 —— 下载（含 SSRF 防护与大小上限）
        Optional<Downloaded> downloaded = download(ref.url());
        if (downloaded.isEmpty()) {
            return Optional.empty();
        }
        Downloaded d = downloaded.get();

        // ③ 落盘，下次同一个人/别人再引用这张图就能零下载命中
        if (ref.hasKey()) {
            imageCache.store(ref.key(), d.mimeType(), d.body());
        }
        return Optional.of(new FetchedImage(
                Base64.getEncoder().encodeToString(d.body()), d.mimeType(), d.body().length));
    }

    /** 没有 MD5 可用时的退化路径（等价于「每次都要下载」） */
    public Optional<FetchedImage> fetch(String url) {
        return fetch(ImageRef.ofUrl(url));
    }

    private Optional<FetchedImage> loadFromCache(ImageCache.Cached cached) {
        try {
            byte[] body = Files.readAllBytes(cached.path());
            if (body.length == 0) {
                log.warn("[MEDIA] 缓存文件是空的，改为重新下载：{}", cached.path());
                return Optional.empty();
            }
            return Optional.of(new FetchedImage(
                    Base64.getEncoder().encodeToString(body), cached.mimeType(), body.length));
        } catch (IOException e) {
            log.warn("[MEDIA] 读缓存文件失败，改为重新下载：{} —— {}", cached.path(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Downloaded> download(String url) {
        if (!StringUtils.hasText(url)) {
            return Optional.empty();
        }
        if (!isPublicHttpUrl(url)) {
            log.warn("[MEDIA] 图片 URL 被 SSRF 防护拒绝（非公网地址或协议不允许）：{}", mask(url));
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "qqbot-personal/0.1")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("[MEDIA] 图片下载失败 HTTP {}：{}", response.statusCode(), mask(url));
                return Optional.empty();
            }

            byte[] body = response.body();
            if (body.length == 0) {
                return Optional.empty();
            }
            if (body.length > MAX_BYTES) {
                log.warn("[MEDIA] 图片过大（{} 字节，上限 {}），已忽略", body.length, MAX_BYTES);
                return Optional.empty();
            }

            String mime = response.headers().firstValue("content-type").orElse("image/jpeg");
            int semi = mime.indexOf(';');
            if (semi > 0) {
                mime = mime.substring(0, semi).trim();
            }
            if (!mime.startsWith("image/")) {
                log.warn("[MEDIA] 返回的不是图片（content-type={}），已忽略", mime);
                return Optional.empty();
            }

            log.info("[MEDIA] 图片下载成功：{} KB，类型 {}", body.length / 1024, mime);
            return Optional.of(new Downloaded(body, mime));
        } catch (Exception e) {
            log.warn("[MEDIA] 图片下载异常：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 只允许访问公网 http/https 地址 */
    private boolean isPublicHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return false;
            }
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                        || address.isMulticastAddress()) {
                    return false;
                }
                byte[] b = address.getAddress();
                if (b.length == 4) {
                    int f1 = b[0] & 0xFF;
                    int f2 = b[1] & 0xFF;
                    if (f1 == 10 || f1 == 127) return false;
                    if (f1 == 172 && f2 >= 16 && f2 <= 31) return false;
                    if (f1 == 192 && f2 == 168) return false;
                    if (f1 == 169 && f2 == 254) return false;
                    if (f1 == 100 && f2 >= 64 && f2 <= 127) return false; // CGNAT 100.64/10
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 日志里只留域名，避免把带签名的完整 URL（含 token）写进日志 */
    private String mask(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + "/...";
        } catch (Exception e) {
            return "<无法解析的URL>";
        }
    }
}
