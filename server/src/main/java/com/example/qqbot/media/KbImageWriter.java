package com.example.qqbot.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * 把聊天图片复制进知识库图库。
 *
 * <p>知识库的图片协议是 {@code [[img:kb:文件名]]}，{@link KbImageSource} 只认
 * {@code data/kb-images} 下的文件。蒸馏笔记发布时必须先把图落到这个目录，
 * 否则正文里的标记在机器人或公开站上都会变成坏图。
 *
 * <p>文件名由调用方生成，且只允许字母数字与短横线；这里再做一次收口，
 * 保证不会把用户可控内容拼成路径。
 */
@Component
public class KbImageWriter {

    private static final Logger log = LoggerFactory.getLogger(KbImageWriter.class);

    private static final int MAX_BASE_LENGTH = 64;

    private final MediaStorageGuard storage;

    public KbImageWriter(MediaStorageGuard storage) {
        this.storage = storage;
    }

    /**
     * 写入一张图；返回带扩展名的文件名。MIME 不认识时返回 null。
     * 同名文件已存在时直接复用，重复发布不会产生副本。
     */
    public String save(byte[] bytes, String mime, String baseName) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String ext = extensionOf(mime);
        if (ext == null) {
            log.warn("[DISTILL] 不支持的图片类型，跳过入库：{}", mime);
            return null;
        }
        String base = safeBase(baseName);
        String filename = base + ext;
        Path dir = storage.kbDir();
        Path target = resolveInside(dir, filename);
        if (target == null) {
            return null;
        }
        try {
            Files.createDirectories(dir);
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ignored) {
            // 同一张图已经复制过，复用
        } catch (IOException e) {
            log.warn("[DISTILL] 写入知识库图片失败：{} —— {}", target, e.getMessage());
            return null;
        }
        return filename;
    }

    public boolean exists(String filename) {
        Path file = resolveInside(storage.kbDir(), filename);
        return file != null && Files.isRegularFile(file);
    }

    public boolean delete(String filename) {
        Path file = resolveInside(storage.kbDir(), filename);
        if (file == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[DISTILL] 删除知识库图片失败：{} —— {}", file, e.getMessage());
            return false;
        }
    }

    private static String safeBase(String baseName) {
        String s = baseName == null || baseName.isBlank() ? "distill" : baseName.trim();
        s = s.replaceAll("[^A-Za-z0-9_-]", "-");
        if (s.length() > MAX_BASE_LENGTH) {
            s = s.substring(0, MAX_BASE_LENGTH);
        }
        return s.isBlank() ? "distill" : s;
    }

    private static String extensionOf(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT).trim();
        if (m.equals("image/png")) {
            return ".png";
        }
        if (m.equals("image/webp")) {
            return ".webp";
        }
        if (m.equals("image/gif")) {
            return ".gif";
        }
        if (m.equals("image/bmp")) {
            return ".bmp";
        }
        if (m.equals("image/jpeg") || m.equals("image/jpg")) {
            return ".jpg";
        }
        return null;
    }

    private static Path resolveInside(Path dir, String filename) {
        if (filename == null || filename.isBlank() || filename.contains("/")
                || filename.contains("\\") || filename.startsWith(".")) {
            return null;
        }
        Path target = dir.resolve(filename).normalize();
        return target.startsWith(dir) ? target : null;
    }
}
