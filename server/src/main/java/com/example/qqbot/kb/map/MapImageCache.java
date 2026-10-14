package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 地图图片的**磁盘缓存** —— 同一张瓦片只从 wiki 取一次。
 *
 * <h2>缓存键为什么是 (map, page, 宽度, 文件名)</h2>
 * <ul>
 *   <li>同一张 {@code 0_0.jpg} 可能在多张画布上出现 —— 按 page 分目录便于整张画布清理；</li>
 *   <li>宽度必须入键：320 的缩略图和原图是**两个不同的文件**，
 *       混在一起会让先请求缩略图的人把原图的位置占了；</li>
 *   <li>文件名来自 wiki 的 JSON，本地不重命名 —— 出问题时能和 wiki 上的名字对上。</li>
 * </ul>
 *
 * <h2>写入用"临时文件 + 原子改名"</h2>
 * 直接 {@code Files.write} 到目标路径的话，两个并发请求会让读者看到**半个文件**，
 * 浏览器拿到截断的 JPEG 就是一块灰。原子改名保证"要么没有，要么是完整的"。
 */
@Service
public class MapImageCache {

    private static final Logger log = LoggerFactory.getLogger(MapImageCache.class);

    /** 一次取图的原始结果 */
    public record Image(byte[] bytes, String mime, boolean fromCache) {
    }

    private final MapOptions options;
    private final MapImageSource source;

    /** 临时文件名后缀的序号 —— 避免同一毫秒内两次写同一个临时名 */
    private final AtomicLong seq = new AtomicLong();

    /**
     * 上游明确回答"没有这个文件"的缓存键，以及判定时刻。
     *
     * <p>为什么要记：wiki 上确实有页面声明了并不存在的底图
     * （{@code Map:FrozenFrontier/Main} 写的是 {@code Map-FrozenFrontier.jpg}，而这张图没上传过）。
     * 不记的话，每次打开地图、每换一档缩放都会重新去问一遍 ——
     * 一档四次重试、四档十六次，全砸在被限流的出站额度上，日志也刷满。
     *
     * <p>为什么要过期：万一 wiki 后来补传了这张图，不能永远认定它不存在。
     */
    private final Map<String, Long> missing = new ConcurrentHashMap<>();

    /** "文件不存在"的记住时长 */
    private static final long MISSING_TTL_MS = Duration.ofHours(12).toMillis();

    public MapImageCache(MapOptions options, MapImageSource source) {
        this.options = options;
        this.source = source;
    }

    public boolean isEnabled() {
        return options.isEnabled();
    }

    public Path root() {
        return Path.of(options.getTileDir()).toAbsolutePath().normalize();
    }

    /**
     * 取一张图：命中缓存零网络，未命中则下载并落盘。
     *
     * <p>任何失败都返回 {@link Optional#empty()} 而不是抛异常 ——
     * 调用方是图片接口，最合适的响应是 404，而不是把整个页面拖垮。
     */
    public Optional<Image> get(String map, String page, String name, int width) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        String file;
        try {
            file = safeName(name);
        } catch (IllegalArgumentException e) {
            log.warn("[MAP-IMG] 拒绝可疑文件名：{}", name);
            return Optional.empty();
        }
        int w = normalizeWidth(width);
        Path target = pathOf(map, page, file, w);

        if (Files.isRegularFile(target)) {
            try {
                return Optional.of(new Image(Files.readAllBytes(target), mimeOf(file), true));
            } catch (IOException e) {
                // 读失败就当没缓存，重新下一份覆盖它
                log.warn("[MAP-IMG] 缓存读取失败，改为重新下载：{} —— {}", target, e.getMessage());
            }
        }
        // 键里**不带档位**：一个文件存不存在跟要多大宽度无关，
        // 带上档位的话四档各问一次，正好把想省的那几次全问回来
        String key = map + "|" + nz(page) + "|" + file;
        Long gone = missing.get(key);
        if (gone != null) {
            if (System.currentTimeMillis() - gone < MISSING_TTL_MS) {
                // 已知不存在：直接回 404，连一次出站都不发
                return Optional.empty();
            }
            missing.remove(key);
        }
        try {
            byte[] bytes = source.fetch(file, w);
            store(target, bytes);
            return Optional.of(new Image(bytes, mimeOf(file), false));
        } catch (MapImageSource.NotFound e) {
            // 只在这一刻记一次日志：之后每次请求都会在上面的分支被挡掉
            log.warn("[MAP-IMG] 上游没有这张图，{} 小时内不再询问：{}", MISSING_TTL_MS / 3600000, file);
            missing.put(key, System.currentTimeMillis());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[MAP-IMG] 取图失败：{} —— {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 缓存目录的规模 —— 管理端展示用，顺带证明缓存真的在长 */
    public record Stats(boolean enabled, String root, long files, long bytes) {
    }

    public Stats stats() {
        Path root = root();
        long files = 0;
        long bytes = 0;
        if (isEnabled() && Files.isDirectory(root)) {
            try (var walk = Files.walk(root, 4)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    files++;
                    bytes += Files.size(p);
                }
            } catch (IOException e) {
                log.warn("[MAP-IMG] 统计缓存目录失败：{}", e.getMessage());
            }
        }
        return new Stats(isEnabled(), root.toString(), files, bytes);
    }

    /* ==================== 清空 ==================== */

    /** 一次清空删掉的东西。字节数要留着 —— 调用方得能告诉管理员"接下来第一次加载会慢" */
    public record PurgeAll(long files, long bytes) {
    }

    /**
     * 清空整个瓦片缓存。
     *
     * <p>缓存是**派生物**，随时可以重新拉回来，所以这里删得干脆。真正要如实说出口的是代价：
     * 删完到重新预热完之间，访客的每一次取图都要等上游（出站还是串行节流的）。
     *
     * <p>先统计再删 —— 删完就数不出来了。
     */
    public PurgeAll purgeAll() {
        if (!isEnabled()) {
            return new PurgeAll(0, 0);
        }
        Path root = root();
        if (!Files.isDirectory(root)) {
            return new PurgeAll(0, 0);
        }
        long files = 0;
        long bytes = 0;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files++;
                bytes += Files.size(p);
            }
        } catch (IOException e) {
            log.warn("[MAP-IMG] 统计瓦片缓存失败：{}", e.getMessage());
        }
        try (var walk = Files.walk(root)) {
            // 反序：先删文件再删空目录，否则目录非空删不掉
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(root)) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            log.warn("[MAP-IMG] 清空瓦片缓存失败：{}", e.getMessage());
        }
        log.info("[MAP-IMG] 瓦片缓存已清空：{} 个文件 / {} 字节", files, bytes);
        return new PurgeAll(files, bytes);
    }

    /* ==================== 路径与校验 ==================== */

    /**
     * 把宽度收进白名单。**不在白名单里的一律退回原图（0）**，不报错 ——
     * 这个参数只影响清晰度，为它把请求变成 400 不划算。
     */
    int normalizeWidth(int width) {
        if (width <= 0) {
            return 0;
        }
        return options.getWidths().contains(width) ? width : 0;
    }

    Path pathOf(String map, String page, String file, int width) {
        return root()
                .resolve(slug(map, 64))
                .resolve(slug(page, 96))
                .resolve(String.valueOf(width))
                .resolve(file);
    }

    /**
     * 文件名校验。**防的是路径穿越**，不是防内容 —— 内容由上游 wiki 负责。
     *
     * <p>允许 {@code '} 与 {@code ()} 是实测需要：组图标里有
     * {@code Ripped Alchemist's Wall Hanging (Left).png} 这种名字，
     * 收得太紧会把合法的图挡掉。
     */
    static String safeName(String raw) {
        String n = raw == null ? "" : raw.strip().replace(' ', '_');
        if (n.isEmpty()) {
            throw new IllegalArgumentException("图片名不能为空");
        }
        if (n.contains("/") || n.contains("\\") || n.contains("..") || n.startsWith(".")) {
            throw new IllegalArgumentException("图片名含非法路径片段：" + raw);
        }
        return n;
    }

    /** 目录名归一化：把 {@code Map:Embervale/Background} 变成能当目录名的东西 */
    static String slug(String raw, int max) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) {
            return "_";
        }
        String out = s.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (out.length() > max) {
            out = out.substring(0, max);
        }
        return out.isEmpty() ? "_" : out;
    }

    /** 由扩展名推 MIME；认不出就用 {@code application/octet-stream}（浏览器会当下载） */
    static String mimeOf(String file) {
        String f = file.toLowerCase(Locale.ROOT);
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (f.endsWith(".png")) {
            return "image/png";
        }
        if (f.endsWith(".webp")) {
            return "image/webp";
        }
        if (f.endsWith(".gif")) {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    private void store(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp" + seq.incrementAndGet());
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                // 少数文件系统不支持原子改名 —— 退回普通移动，至少保证内容是完整的
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("[MAP-IMG] 写入缓存失败（不影响本次响应）：{} —— {}", target, e.getMessage());
        }
    }
}
