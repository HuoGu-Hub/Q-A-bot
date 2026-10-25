package com.example.qqbot.distill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 聊天图片的落盘。
 *
 * <p>QQ 图片链接会过期，所以必须在采集时就把字节存下来。文件名用内容 MD5，
 * 同一张图被转发多次只占一份磁盘；路径永远由服务端拼，不接受外部传路径。
 */
@Component
public class ChatImageStore {

    private static final Logger log = LoggerFactory.getLogger(ChatImageStore.class);

    /** 只认这几种扩展名；其它 MIME 一律按 .bin 存，避免拼出奇怪后缀 */
    private static final String[] MIME_EXT = {
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp",
            "image/gif", "gif", "image/bmp", "bmp"
    };

    private final DistillPolicy props;

    public ChatImageStore(DistillPolicy props) {
        this.props = props;
    }

    /** 一次落盘的结果 */
    public record Stored(String md5, String filename, String mime, int bytes) {
    }

    /**
     * 保存一张图。
     *
     * @throws IllegalArgumentException 空图或超过上限
     */
    public Stored save(byte[] bytes, String mime) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        int max = Math.max(1, props.getMaxImageSizeKb()) * 1024;
        if (bytes.length > max) {
            throw new IllegalArgumentException("图片超过上限 " + (max / 1024) + " KB");
        }
        String md5 = md5(bytes);
        String safeMime = mime == null || mime.isBlank() ? "application/octet-stream" : mime;
        String ext = extensionOf(safeMime);
        String filename = md5 + "." + ext;
        Path dir = dir();
        Files.createDirectories(dir);
        Path target = resolveInside(dir, filename);
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ignored) {
            // 同一张图已经存过，直接复用
        }
        return new Stored(md5, filename, safeMime, bytes.length);
    }

    /** 读一张图；文件名不合法或不存在返回 null */
    public byte[] read(String filename) {
        if (filename == null || !filename.matches("[a-f0-9]{32}\\.[a-z0-9]{1,8}")) {
            return null;
        }
        Path file = resolveInside(dir(), filename);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("[DISTILL] 读聊天图片失败：{} —— {}", file, e.getMessage());
            return null;
        }
    }

    public String mimeOf(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }

    /** 目录不存在时创建；返回绝对路径 */
    public Path dir() {
        Path dir = Path.of(props.getImagesDir() == null || props.getImagesDir().isBlank()
                ? "./data/chat-images" : props.getImagesDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("[DISTILL] 创建聊天图片目录失败：{} —— {}", dir, e.getMessage());
        }
        return dir;
    }

    private static Path resolveInside(Path dir, String filename) {
        Path target = dir.resolve(filename).normalize();
        return target.startsWith(dir) ? target : null;
    }

    private static String extensionOf(String mime) {
        String lower = mime.toLowerCase(Locale.ROOT);
        for (int i = 0; i < MIME_EXT.length; i += 2) {
            if (MIME_EXT[i].equals(lower)) {
                return MIME_EXT[i + 1];
            }
        }
        return "bin";
    }

    private static String md5(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("计算图片 MD5 失败", e);
        }
    }
}
