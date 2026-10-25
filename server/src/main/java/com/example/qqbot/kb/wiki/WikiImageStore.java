package com.example.qqbot.kb.wiki;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.WikiImport;
import com.example.qqbot.media.MediaPolicy;
import com.example.qqbot.media.MediaStorageGuard;
import com.example.qqbot.media.UpstreamGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * wiki 主图的**下载与落盘** —— 导入器只问它要一个 marker key。
 *
 * <h2>目录即索引</h2>
 * 落盘路径是 {@code wiki-images/<页面 slug>/<文件名 slug>.<扩展名>}。
 * 不维护"文件名 → 哈希"的索引表：那种表一旦和目录不一致，就会出现
 * "后台明明抓了图，回答里却没有"的悬案。存在即已抓过，重跑直接用。
 *
 * <h2>为什么不是内容寻址</h2>
 * 内容寻址能天然去重，但要求先知道 sha1 才能判断"有没有" —— 而 sha1 要么下载后才知道，
 * 要么额外查一次 imageinfo。这里已经按引用频率排除了共享图标，剩下的重复很少，
 * 不值得为此多一张索引表。代价是**同名文件被重新上传时不会自动覆盖**：
 * 只要有文件存在就直接复用。图片改名会生成新 key、自然更新；
 * 同名替换则需要清空该页目录后重抓。这个代价在 P0 可以接受 —— 图片版本比正文稳定得多。
 *
 * <h2>限流与配额</h2>
 * 出站走全局唯一的 {@link UpstreamGate}，与地图瓦片排同一条队；
 * 目录总量超过 {@code app.media.wiki-images.max-total-size-mb} 就**停止写入新图**，
 * 而不是淘汰旧图 —— 旧图已经被历史回答的 marker 指着。
 */
@Service
public class WikiImageStore implements WikiImageFetcher {

    private static final Logger log = LoggerFactory.getLogger(WikiImageStore.class);

    /** "上游确实没有这张图"的记住时长 —— 与地图那边同一个理由，避免每次重问 */
    private static final long MISSING_TTL_MS = Duration.ofHours(12).toMillis();

    /** 429 / 5xx 的重试等待。间隔本身由闸门控制，这里只处理"被拒之后缓一下" */
    private static final long[] RETRY_BACKOFF_MS = {900, 2500};

    private final KbPolicy props;
    private final MediaPolicy media;
    private final MediaStorageGuard storage;
    private final UpstreamGate gate;
    private final HttpClient http;

    /** 已确认不存在的图：URL → 判定时刻 */
    private final Map<String, Long> missing = new ConcurrentHashMap<>();

    /** 目录总字节数。-1 = 还没统计过，第一次写入时扫一遍 */
    private final AtomicLong totalBytes = new AtomicLong(-1);

    /** 配额告警只说一次，否则每页一条会把日志刷满 */
    private final AtomicBoolean capWarned = new AtomicBoolean();

    public WikiImageStore(KbPolicy props, MediaPolicy media, MediaStorageGuard storage, UpstreamGate gate) {
        this.props = props;
        this.media = media;
        this.storage = storage;
        this.gate = gate;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Special:FilePath 是 302，真正的字节在 /images/ 下
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Optional<Result> fetch(String pageTitle, WikiImagePicker.Pick pick) {
        WikiImport.Images opts = props.getWikiImport().getImages();
        // 两个开关各管一段：import 侧管"要不要抓"，media 侧是目录层的维护总闸
        if (!opts.isEnabled() || !media.getWikiImages().isEnabled() || pick == null) {
            return Optional.empty();
        }
        String fileName = WikiImagePicker.normalize(pick.fileName());
        if (!WikiImagePicker.isSafeFileName(fileName)) {
            log.debug("[WIKI-IMG] 文件名不合法，跳过：{}", fileName);
            return Optional.empty();
        }
        String pageSlug = pageSlug(pageTitle);
        String stem = stemOf(fileName);

        // 已经抓过：目录就是索引，一次磁盘扫描比一次上游请求便宜得多
        Optional<String> existing = find(pageSlug, stem);
        if (existing.isPresent()) {
            return Optional.of(new Result(existing.get(), false));
        }
        if (gate.paused()) {
            log.debug("[WIKI-IMG] 上游限流暂停中，本次不取图：{}", fileName);
            return Optional.empty();
        }
        if (atCapacity()) {
            // 先判再下：配额满了还去敲上游，等于白花一次限流额度
            return Optional.empty();
        }

        String url = urlOf(fileName, opts);
        Long gone = missing.get(url);
        if (gone != null) {
            if (System.currentTimeMillis() - gone < MISSING_TTL_MS) {
                return Optional.empty();
            }
            missing.remove(url);
        }

        Downloaded downloaded = download(url, opts);
        if (downloaded == null) {
            return Optional.empty();
        }
        return store(pageSlug, fileName, stem, downloaded.bytes(), downloaded.mime())
                .map(key -> new Result(key, true));
    }

    /**
     * 把字节落到 {@code wiki-images/<page>/<stem>.<ext>}，返回 marker key。
     *
     * <p>包内可见是为了让单测能**完全不联网**地验证路径、配额与幂等。
     */
    Optional<String> store(String pageSlug, String fileName, String stem, byte[] bytes, String mime) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        String ext = extensionOf(mime, fileName);
        if (ext == null) {
            log.warn("[WIKI-IMG] 不是可用的图片类型（{}），跳过：{}", mime, fileName);
            return Optional.empty();
        }
        String key = pageSlug + "/" + stem + "." + ext;
        // 目录里已经有同 stem 的文件就不再写 —— 重跑幂等。
        // 必须返回**找到的那个 key**：旧文件的扩展名可能与这次推断的不同。
        Optional<String> existing = find(pageSlug, stem);
        if (existing.isPresent()) {
            return existing;
        }
        if (!reserve(bytes.length)) {
            return Optional.empty();
        }
        try {
            Path target = resolveInside(pageSlug, stem + "." + ext);
            write(target, bytes);
            return Optional.of(key);
        } catch (IOException e) {
            totalBytes.addAndGet(-bytes.length);
            log.warn("[WIKI-IMG] 写入失败，本次不出图：{} —— {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 路径与命名 ====================

    /**
     * 页面标题 → 目录名。与块 id 用**同一条**规则，避免"块叫一个名、图在另一个目录"。
     */
    static String pageSlug(String title) {
        String base = WikiArticleImporter.blockIdOf(title == null || title.isBlank() ? "page" : title);
        String s = base.startsWith("wiki-") ? base.substring(5) : base;
        if (s.isBlank()) {
            // 非 ASCII 标题会被 blockIdOf 清空 —— 用短哈希兜底，避免所有中文页挤进同一个目录
            s = "p" + sha1(title == null ? "" : title).substring(0, 10);
        }
        // 56 而不是 64：marker 的总长上限是 120，要留出 "wiki:"、"/"、扩展名与文件名的位置
        return truncate(s, 56);
    }

    /** 文件名 → 不带扩展名的 slug。中文名同样走哈希兜底 */
    static String stemOf(String fileName) {
        String name = WikiImagePicker.normalize(fileName);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (s.isBlank()) {
            s = "img-" + sha1(name).substring(0, 8);
        }
        return truncate(s, 40);
    }

    /** MIME → 扩展名。SVG 与其他类型一律不认：协议端显示不稳定 */
    static String extensionOf(String mime, String fileName) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("png")) {
            return "png";
        }
        if (m.contains("jpeg") || m.contains("jpg")) {
            return "jpg";
        }
        if (m.contains("webp")) {
            return "webp";
        }
        if (m.contains("gif")) {
            return "gif";
        }
        // 上游没给 content-type 时退回文件名，但必须仍在白名单里
        return WikiImagePicker.hasImageExtension(fileName) ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : null;
    }

    private Path resolveInside(String pageSlug, String fileName) throws IOException {
        Path root = storage.wikiDir();
        Path dir = root.resolve(pageSlug).normalize();
        Path target = dir.resolve(fileName).normalize();
        if (!dir.startsWith(root) || !target.startsWith(dir)) {
            throw new IOException("可疑路径：" + pageSlug + "/" + fileName);
        }
        return target;
    }

    /** 目录里有没有同 stem 的图；有就返回 marker key */
    Optional<String> find(String pageSlug, String stem) {
        Path dir;
        try {
            Path root = storage.wikiDir();
            dir = root.resolve(pageSlug).normalize();
            if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
                return Optional.empty();
            }
        } catch (Exception e) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> WikiImagePicker.hasImageExtension(p.getFileName().toString()))
                    .filter(p -> stemOf(p.getFileName().toString()).equals(stem))
                    .findFirst()
                    .map(p -> pageSlug + "/" + p.getFileName());
        } catch (IOException e) {
            log.debug("[WIKI-IMG] 扫描目录失败：{} —— {}", dir, e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 下载 ====================

    private record Downloaded(byte[] bytes, String mime) {
    }

    private Downloaded download(String url, WikiImport.Images opts) {
        Exception last = null;
        for (int attempt = 0; attempt <= RETRY_BACKOFF_MS.length; attempt++) {
            if (gate.paused()) {
                return null;
            }
            try {
                HttpResponse<byte[]> response = send(url, opts);
                int status = response.statusCode();
                if (status == 404) {
                    // 只记一次：之后每次导入都会在这里被挡掉，不再敲上游
                    missing.put(url, System.currentTimeMillis());
                    log.info("[WIKI-IMG] 上游没有这张图，{} 小时内不再询问：{}", MISSING_TTL_MS / 3_600_000, url);
                    return null;
                }
                if (status == 429) {
                    boolean paused = gate.onRateLimited();
                    if (paused) {
                        log.warn("[WIKI-IMG] 上游持续限流，暂停取图 {} 秒 —— 期间一张都不取",
                                gate.pauseRemainingMs() / 1000);
                    } else {
                        log.debug("[WIKI-IMG] 上游限流，出站间隔顶到 {} ms", gate.gapMs());
                    }
                    sleep(attempt);
                    continue;
                }
                if (status != 200) {
                    last = new IOException("HTTP " + status);
                    // 5xx 值得再试一次，4xx 是"请求写错了"，重试没有意义
                    if (status >= 500 && attempt < RETRY_BACKOFF_MS.length) {
                        sleep(attempt);
                        continue;
                    }
                    log.warn("[WIKI-IMG] 取图失败 HTTP {}：{}", status, url);
                    return null;
                }
                gate.onSuccess();
                String mime = contentType(response);
                if (!mime.startsWith("image/")) {
                    log.warn("[WIKI-IMG] 返回的不是图片（{}），跳过：{}", mime, url);
                    return null;
                }
                byte[] body = response.body();
                if (body == null || body.length == 0) {
                    log.warn("[WIKI-IMG] 上游返回空内容：{}", url);
                    return null;
                }
                if (opts.getMaxBytes() > 0 && body.length > opts.getMaxBytes()) {
                    log.warn("[WIKI-IMG] 图片超过上限 {} KB，跳过：{}（{} KB）",
                            opts.getMaxBytes() / 1024, url, body.length / 1024);
                    return null;
                }
                return new Downloaded(body, mime);
            } catch (InterruptedException e) {
                // 关机/取消任务时不要继续重试，也不要把中断状态吞掉
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                last = e;
                sleep(attempt);
            }
        }
        log.warn("[WIKI-IMG] 取图失败：{} —— {}", url, last == null ? "未知原因" : last.getMessage());
        return null;
    }

    private HttpResponse<byte[]> send(String url, WikiImport.Images opts) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, opts.getTimeoutSeconds())))
                .header("User-Agent", opts.getUserAgent())
                .GET()
                .build();
        gate.enter();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } finally {
            gate.leave();
        }
    }

    private static String contentType(HttpResponse<byte[]> response) {
        String mime = response.headers().firstValue("content-type").orElse("");
        int semi = mime.indexOf(';');
        return (semi > 0 ? mime.substring(0, semi) : mime).strip().toLowerCase(Locale.ROOT);
    }

    /** 拼下载地址。文件名先做百分号编码 —— 组图标里有 {@code '} 与 {@code ()} 这类字符 */
    static String urlOf(String fileName, WikiImport.Images opts) {
        String base = opts.getImageBase();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        String encoded = URLEncoder.encode(fileName.replace(' ', '_'), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return opts.getWidth() > 0 ? base + encoded + "?width=" + opts.getWidth() : base + encoded;
    }

    private static void sleep(int attempt) {
        if (attempt >= RETRY_BACKOFF_MS.length) {
            return;
        }
        try {
            Thread.sleep(RETRY_BACKOFF_MS[attempt]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 配额与写入 ====================

    /**
     * 预留字节额度。
     *
     * <p>超过上限时**停写**而不是淘汰：这些图被块正文里的 marker 指着，
     * 淘汰一张就会让一条历史回答变成"只有标记没有图"。要腾空间请显式清空目录再重抓。
     */
    /** 目录是否已经到顶。只看当前总量，不预留 —— 给"下载前先判一次"用 */
    private synchronized boolean atCapacity() {
        int capMb = media.getWikiImages().getMaxTotalSizeMb();
        if (capMb <= 0) {
            return false;
        }
        long total = totalBytes.get();
        if (total < 0) {
            total = computeTotal();
            totalBytes.set(total);
        }
        if (total >= capMb * 1024L * 1024L) {
            if (capWarned.compareAndSet(false, true)) {
                log.warn("[WIKI-IMG] wiki 配图目录已到上限 {} MB，本次起不再抓新图。"
                        + "清空 {} 后重跑导入即可重新抓取", capMb, storage.wikiDir());
            }
            return true;
        }
        return false;
    }

    private synchronized boolean reserve(long bytes) {
        int capMb = media.getWikiImages().getMaxTotalSizeMb();
        if (capMb <= 0) {
            totalBytes.addAndGet(bytes);
            return true;
        }
        long total = totalBytes.get();
        if (total < 0) {
            total = computeTotal();
            totalBytes.set(total);
        }
        long cap = capMb * 1024L * 1024L;
        if (total + bytes > cap) {
            if (capWarned.compareAndSet(false, true)) {
                log.warn("[WIKI-IMG] wiki 配图目录已到上限 {} MB，本次起不再写入新图。"
                        + "清空 {} 后重跑导入即可重新抓取", capMb, storage.wikiDir());
            }
            return false;
        }
        totalBytes.addAndGet(bytes);
        return true;
    }

    private long computeTotal() {
        long sum = 0;
        try (Stream<Path> walk = Files.walk(storage.wikiDir(), 3)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                try {
                    sum += Files.size(p);
                } catch (IOException ignored) {
                    // 单个文件读不到就跳过，不该让配额统计失败
                }
            }
        } catch (IOException e) {
            log.warn("[WIKI-IMG] 统计配图目录失败，本次按 0 计：{}", e.getMessage());
        }
        return sum;
    }

    /** 临时文件 + 原子改名：并发读者要么看不到，要么看到完整文件 */
    private static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp" + System.nanoTime());
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha1(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "0000000000";
        }
    }
}
