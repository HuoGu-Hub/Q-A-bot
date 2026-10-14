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

    /**
     * 对上游的**串行闸门**。
     *
     * <p>实测：并发拉瓦片时 wiki 直接回 <b>429 Too Many Requests</b>，
     * 而"打开一次地图"正好就是几十个并发请求 —— 不节流的话首屏会缺一大片瓦片，
     * 用户看到的是"这地图是坏的"。
     *
     * <p>所以出站请求串行化，并保证两次之间有 {@link #MIN_GAP_MS} 的间隔。
     * 代价是首次拉满一张图的瓦片要二十来秒，但那是一次性的 ——
     * 之后所有访客都命中本地缓存，一个字节都不再打扰 wiki。
     */
    private final java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(1);

    /** 两次出站请求之间的**最小**间隔 */
    private static final long MIN_GAP_MS = 300;

    /** 间隔的上限。撞狠了也不能慢到"一张图等半分钟" */
    private static final long MAX_GAP_MS = 4000;

    /**
     * 当前实际间隔 —— **跟着 429 自适应**。
     *
     * <p>为什么固定值不行：实测 3 次/秒能撑过一小批请求，但**连续几分钟**地拉
     * （比如预热一整张图）依然会被限流，而那时的 429 会打在**同时在看图的访客**身上
     * —— 他们看到的是地图缺块。所以撞到 429 就把间隔翻倍，顺利几次再慢慢收回来。
     *
     * <p>这是"礼貌客户端"的常规做法：把对方的限流当成**信号**，而不是一次性故障。
     */
    private volatile long gapMs = MIN_GAP_MS;

    /** 只在闸门内读写 */
    private long lastRequestAt;

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

    /** 重试的等待时长。429 是"你太快了"，等得短了等于没等 */
    private static final long[] RETRY_BACKOFF_MS = {900, 2500};

    /** 上游说"你太快了"。单独一个类型，才能和网络故障区别对待 */
    static final class RateLimited extends IOException {
        RateLimited(String message) {
            super(message);
        }
    }

    /**
     * 取图，失败按 {@link #RETRY_BACKOFF_MS} 退避重试。
     *
     * <p>为什么要重试：即便节流了，实测仍会有个位数的请求撞上 429，
     * 而失败的表现是"地图上缺一块"。重试的代价只在失败路径上。
     */
    @Override
    public byte[] fetch(String fileName, int width) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= RETRY_BACKOFF_MS.length; attempt++) {
            try {
                return fetchOnce(fileName, width);
            } catch (IOException e) {
                last = e;
                // 429 是节流信号：把出站间隔翻倍，让整条链路慢下来；
                // 顺利的时候再一点点收回，别因为一次抖动就永久变慢
                if (e instanceof RateLimited) {
                    gapMs = Math.min(MAX_GAP_MS, gapMs * 2);
                    log.info("[MAP-IMG] 上游限流，出站间隔调整为 {} ms", gapMs);
                } else if (gapMs > MIN_GAP_MS) {
                    gapMs = Math.max(MIN_GAP_MS, gapMs - 100);
                }
                if (attempt < RETRY_BACKOFF_MS.length) {
                    sleep(RETRY_BACKOFF_MS[attempt]);
                }
            }
        }
        throw last == null ? new IOException("取图失败：" + fileName) : last;
    }

    private void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待重试被中断", e);
        }
    }

    /** 进闸门并补齐间隔。**必须在 finally 里 {@link #leave()}** */
    private void enter() throws IOException {
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待出站闸门被中断", e);
        }
        long wait = gapMs - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private void leave() {
        gate.release();
    }

    private byte[] fetchOnce(String fileName, int width) throws IOException {
        enter();
        try {
            return send(url(fileName, width));
        } finally {
            leave();
        }
    }

    private byte[] send(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, options.getTimeoutSeconds())))
                .header("User-Agent", options.getUserAgent())
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 429) {
                throw new RateLimited("上游限流（429）：" + url);
            }
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
