package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.media.UpstreamGate;
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
     * 出站闸门：串行 + **固定间隔** + 撞限流就整条暂停。
     *
     * <p>实测：并发拉瓦片时对方直接回 <b>429 Too Many Requests</b>，
     * 而"打开一次地图"正好就是几十个并发请求 —— 不节流的话首屏会缺一大片瓦片。
     *
     * <p>速率的取法与依据见 {@link UpstreamGate}：那里也说明了为什么
     * "自适应漂移"被换成了固定值 + 暂停。
     */
    private final UpstreamGate gate;

    private final MapOptions options;
    private final HttpClient http;

    /**
     * @param gate 全局唯一的上游闸门 —— 地图与 wiki 配图共用，见 {@link UpstreamGate} 类注释。
     *             以 Bean 注入而不是自己 new：两个抓图路径必须排同一条队。
     */
    public WikiMapImageSource(MapOptions options, UpstreamGate gate) {
        this.options = options;
        this.gate = gate;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // 必须跟随：Special:FilePath 是 302，真正的字节在 /images/ 下
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 重试的等待时长。429 是"你太快了"，等得短了等于没等 */
    private static final long[] RETRY_BACKOFF_MS = {900, 2500, 6000};

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
        if (gate.paused()) {
            // 暂停期里**一次请求都不发**：对方已经在说"别来了"，继续敲只会把限流窗口拉长。
            // 这里抛的是 RateLimited，调用方（MapImageCache）把它当"暂时取不到"处理，
            // 只在 debug 里留一行 —— 不然一张图一条 WARN，日志就没法看了。
            throw new RateLimited("上游限流暂停中（还剩 " + gate.pauseRemainingMs() / 1000 + " 秒）");
        }
        IOException last = null;
        for (int attempt = 0; attempt <= RETRY_BACKOFF_MS.length; attempt++) {
            try {
                byte[] bytes = fetchOnce(fileName, width);
                gate.onSuccess();
                logSummary();
                return bytes;
            } catch (MapImageSource.NotFound e) {
                // 文件不存在，重试一百次也还是不存在 —— 直接往上抛，一次都不多问
                throw e;
            } catch (IOException e) {
                last = e;
                if (e instanceof RateLimited) {
                    // 429 是节流信号：先顶高间隔，顶到连击上限就整条暂停。
                    // 日志只在"进入暂停"时说一句，其余降到 debug —— 实测每张图都会撞一次，
                    // 按原来的写法一次预热就是上千行 WARN。
                    if (gate.onRateLimited()) {
                        log.warn("[MAP-IMG] 上游持续限流，暂停取图 {} 秒 —— 期间一张都不取，等预热下次补",
                                options.getRateLimitPauseMs() / 1000);
                    } else {
                        log.debug("[MAP-IMG] 上游限流，出站间隔顶到 {} ms（连击 {}）", gate.gapMs(), gate.streak());
                    }
                    logSummary();
                    if (gate.paused()) {
                        break;      // 已经决定暂停，再重试没有意义
                    }
                }
                if (attempt < RETRY_BACKOFF_MS.length) {
                    sleep(RETRY_BACKOFF_MS[attempt]);
                }
            }
        }
        throw last == null ? new IOException("取图失败：" + fileName) : last;
    }

    /** 限流统计：**每五分钟最多一句**，把"成功多少 / 限流多少 / 当前间隔"一次说完 */
    private void logSummary() {
        UpstreamGate.Stats stats = gate.summaryIfDue();
        if (stats != null) {
            log.info("[MAP-IMG] 近 5 分钟：成功 {} 张 / 撞限流 {} 次，当前出站间隔 {} ms",
                    stats.ok(), stats.limited(), stats.gapMs());
        }
    }

    private void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待重试被中断", e);
        }
    }

    private byte[] fetchOnce(String fileName, int width) throws IOException {
        gate.enter();
        try {
            return send(url(fileName, width));
        } finally {
            gate.leave();
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
            if (response.statusCode() == 404) {
                throw new MapImageSource.NotFound("上游没有这个文件（404）：" + url);
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
            // 429 不在这里报：它是节流信号，由 fetch 汇总成"每五分钟一句"。
            // 这里只留真正的故障（超时、5xx、内容异常）。
            if (!(e instanceof RateLimited)) {
                log.warn("[MAP-IMG] 取图失败：{} —— {}", url, e.getMessage());
            }
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