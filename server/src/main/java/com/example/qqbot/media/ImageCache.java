package com.example.qqbot.media;

import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.guard.PathGuard;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 图片文件缓存 —— 同一张图只从 QQ 的 CDN 下载一次。
 *
 * <p><b>为什么要做：</b>部署到服务器后每月流量有限，而现在的行为是
 * 「每有人 @ 一张图就重新下载一遍」。QQ 的图片 URL 是 rkey 签名的、
 * <b>会过期</b>（实测过期后返回 {@code download url has expired}），
 * 所以不能靠 URL 复用，必须自己存一份。
 *
 * <p><b>怎么认「同一张图」：</b>用 OneBot 图片段里的 {@code file} 字段 ——
 * QQ 用图片内容的 MD5 给文件命名（真实样本 {@code 65A82BE1...C823.jpg}），
 * 这个值<b>在下载之前就能拿到</b>。所以缓存命中时**一个字节的网络流量都不花**。
 *
 * <p><b>「最后使用时间」怎么算：</b>不用系统 atime（实测工作区是 {@code noatime} 挂载，
 * atime 根本不更新），而是**每次命中都把文件的 mtime 更新为当前时间**。
 * 于是「mtime = 最后一次被使用的时间」，{@link TempImageCleaner} 直接按它清理即可 ——
 * 不需要额外维护一份索引，也就没有索引和文件不一致的问题。
 *
 * <p><b>写盘是原子的</b>：先写 {@code .part} 再 rename，避免半截文件被当成有效缓存。
 */
@Component
public class ImageCache {

    private static final Logger log = LoggerFactory.getLogger(ImageCache.class);

    /** 只接受小写 32 位十六进制 —— 缓存键来自外部输入，绝不能直接当路径片段用 */
    private static final Pattern SAFE_KEY = Pattern.compile("^[0-9a-f]{32}$");

    private static final String PART_SUFFIX = ".part";

    private final MediaStorageGuard storage;
    private final MediaProperties props;
    private final PathGuard pathGuard;

    /** 缓存目录当前总字节数的近似值：启动时扫描一次，之后增量维护 */
    private final AtomicLong approxBytes = new AtomicLong(0);

    /** 一次命中的结果 */
    public record Cached(Path path, String mimeType, long size) {
    }

    public ImageCache(MediaStorageGuard storage, MediaProperties props, PathGuard pathGuard) {
        this.storage = storage;
        this.props = props;
        this.pathGuard = pathGuard;
    }

    @PostConstruct
    void init() {
        long bytes = listFiles(storage.tmpDir()).stream().mapToLong(ImageCache::sizeOf).sum();
        approxBytes.set(bytes);
        log.info("[MEDIA] 图片缓存目录：{}（当前占用 {}）", storage.inboundDir(), humanSize(bytes));
    }

    /**
     * 按 MD5 查缓存。
     *
     * <p>命中时会**顺手把文件 mtime 更新为当前时间** —— 这就是「最后使用时间」的记账方式。
     *
     * @return 命中则返回本地文件信息；未命中/未启用/键不可用都返回 empty（调用方去下载）
     */
    public Optional<Cached> lookup(String key) {
        if (!isCacheable(key)) {
            return Optional.empty();
        }
        Path dir = storage.inboundDir();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, key + ".*")) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path) || isPartFile(path)) {
                    continue;
                }
                long size = Files.size(path);
                touch(path);
                return Optional.of(new Cached(path, mimeFromExtension(path), size));
            }
        } catch (IOException e) {
            // 目录不存在 / 读不了都当作未命中，不影响正常下载
            log.debug("[MEDIA] 读取图片缓存失败，按未命中处理：{} —— {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    /** 下载完成后落盘。失败只记日志，绝不影响本次回复。 */
    public void store(String key, String mimeType, byte[] body) {
        if (!isCacheable(key) || body == null || body.length == 0) {
            return;
        }
        Path dir = storage.inboundDir();
        String ext = extensionFor(mimeType);
        Path target = dir.resolve(key + "." + ext);
        Path part = dir.resolve(key + "." + ext + PART_SUFFIX);
        try {
            Files.createDirectories(dir);
            Files.write(part, body,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            moveInto(part, target);
            approxBytes.addAndGet(body.length);
            if (exceedsCapacity()) {
                enforceCapacity();
            }
        } catch (IOException e) {
            log.warn("[MEDIA] 写入图片缓存失败（不影响本次回复）：{} —— {}", key, e.getMessage());
            deleteQuietly(part);
        }
    }

    /**
     * 把缓存总量压回上限以内：按**最后使用时间**从旧到新淘汰（LRU）。
     *
     * <p>光靠 TTL 不够 —— 保留时长只管「多久没用就删」，
     * 但一个热门群完全可能在一小时内塞进几百兆图，TTL 还没到磁盘就先满了。
     *
     * @return 实际淘汰的文件数
     */
    public int enforceCapacity() {
        long capMb = props.getTempImages().getMaxTotalSizeMb();
        Path root = storage.tmpDir();
        if (capMb <= 0 || !Files.isDirectory(root)) {
            return 0;
        }
        List<Path> files = listFiles(root);
        long total = files.stream().mapToLong(ImageCache::sizeOf).sum();
        approxBytes.set(total);

        long cap = capMb * 1024 * 1024;
        if (total <= cap) {
            return 0;
        }

        files.sort(Comparator.comparingLong(ImageCache::lastModified));
        long target = (long) (cap * 0.9);   // 一次多腾一点，避免每次写入都触发淘汰
        int removed = 0;
        long freed = 0;
        for (Path file : files) {
            if (total <= target) {
                break;
            }
            if (!pathGuard.check(file.toString()).allowed()) {
                continue;
            }
            long size = sizeOf(file);
            try {
                Files.delete(file);
                total -= size;
                freed += size;
                removed++;
            } catch (IOException e) {
                log.warn("[MEDIA] 淘汰缓存文件失败：{} —— {}", file, e.getMessage());
            }
        }
        approxBytes.set(total);
        log.info("[MEDIA] 图片缓存超过上限 {} MB，按最后使用时间淘汰 {} 个文件，释放 {}，当前 {}",
                capMb, removed, humanSize(freed), humanSize(total));
        return removed;
    }

    /** 缓存当前占用的近似字节数 */
    public long approxBytes() {
        return approxBytes.get();
    }

    // ==================== 内部工具 ====================

    private boolean isCacheable(String key) {
        return props.getTempImages().isEnabled()
                && key != null
                && SAFE_KEY.matcher(key).matches();
    }

    private boolean exceedsCapacity() {
        long capMb = props.getTempImages().getMaxTotalSizeMb();
        return capMb > 0 && approxBytes.get() > capMb * 1024 * 1024;
    }

    /** 命中即记账：把 mtime 刷成现在，它就代表「最后一次被使用的时间」 */
    private void touch(Path path) {
        try {
            Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            log.debug("[MEDIA] 更新缓存使用时间失败（不影响使用）：{}", e.getMessage());
        }
    }

    private void moveInto(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Path> listFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            log.warn("[MEDIA] 遍历缓存目录失败：{} —— {}", root, e.getMessage());
        }
        return files;
    }

    private static boolean isPartFile(Path path) {
        return path.getFileName().toString().endsWith(PART_SUFFIX);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清不掉就留给清理任务
        }
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) {
            return "jpg";
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            default -> "jpg";
        };
    }

    private static String mimeFromExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "image/jpeg";
        };
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
