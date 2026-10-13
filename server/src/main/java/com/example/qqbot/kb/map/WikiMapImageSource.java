package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 从 wiki 取图。全项目只有这里会去 {@code Special:FilePath} 拉图片。
 *
 * <h2>为什么不直接把浏览器的 img 指向 wiki</h2>
 * 三条实测理由：
 * <ol>
 *   <li><b>慢</b>：单张 1280 瓦片约 400 KB，一次视野就是好几张；国内直连 wiki 首屏要等十几秒；</li>
 *   <li><b>不可控</b>：对方限流或改文件名，我们的页面就直接破图，本地连缓存的机会都没有；</li>
 *   <li><b>不许刷屏</b>：每个访客各拉一遍是对 wiki 的重复负担；经我们缓存后同样的字节只取一次。</li>
 * </ol>
 *
 * <p>{@code ?width=} 交给 MediaWiki 自己缩略：它比我们在 Java 里解码再缩要快得多，
 * 也不占我们的 CPU（实测 1280 原图 363 KB → width=320 只要 31 KB）。
 */
@Component
public class WikiMapImageSource implements MapImageSource {

    private static final Logger log = LoggerFactory.getLogger(WikiMapImageSource.class);

    private final MapOptions options;
    private final HttpClient http;

    public WikiMapImageSource(MapOptions options) {
        this.options = options;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // 必须跟随：Special:FilePath 是 302，真正的字节在 /images/ 下
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public byte[] fetch(String fileName, int width) throws IOException {
        String url = url(fileName, width);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, options.getTimeoutSeconds())))
                .header("User-Agent", options.getUserAgent())
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("上游返回 " + response.statusCode() + "：" + url);
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new IOException("上游返回空内容：" + url);
            }
            if (body.length > options.getMaxBytes()) {
                throw new IOException("图片超过上限 " + options.getMaxBytes() + " 字节：" + url);
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("取图被中断：" + url, e);
        } catch (IOException e) {
            log.warn("[MAP-IMG] 取图失败：{} —— {}", url, e.getMessage());
            throw e;
        }
    }

    @Override
    public String describe() {
        return "wiki（" + options.getImageBase() + "）";
    }

    /** 拼上游 URL。文件名先做百分号编码 —— 组图标里有 {@code '} 和 {@code ()} 这类字符 */
    String url(String fileName, int width) {
        String base = options.getImageBase();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        String encoded = URLEncoder.encode(fileName.replace(' ', '_'), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return width > 0 ? base + encoded + "?width=" + width : base + encoded;
    }
}
