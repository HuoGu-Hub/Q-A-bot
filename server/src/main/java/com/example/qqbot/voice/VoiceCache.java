package com.example.qqbot.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * TTS 的磁盘缓存 —— 同一句话、同一套参数只合成一次。
 *
 * <h2>为什么值得缓存</h2>
 * edge-tts 一句话要 1.7~2 秒，而且每次都走公网。群里来来回回就那几句
 * （「来啦」「稍等」「晚安」），缓存能把这部分直接压到 0。
 *
 * <h2>为什么按内容哈希而不是按句子</h2>
 * 文件名里放中文会把路径长度、转义、跨平台都搞复杂。哈希只要求
 * "同样的输入得到同样的名字"，正好。
 */
@Component
public class VoiceCache {

    private static final Logger log = LoggerFactory.getLogger(VoiceCache.class);

    /** 缓存目录总量上限。超过后按最后修改时间从旧到新删到 80% */
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    private final TtsPolicy policy;

    public VoiceCache(TtsPolicy policy) {
        this.policy = policy;
    }

    public byte[] get(String key) {
        Path file = pathOf(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            // 命中就刷新 mtime，让它别被容量清理先盯上
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("[VOICE] 读缓存失败：{}", e.getMessage());
            return null;
        }
    }

    public void put(String key, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            Files.write(pathOf(key), bytes);
            trim(dir);
        } catch (IOException e) {
            log.warn("[VOICE] 写缓存失败（不影响本次合成）：{}", e.getMessage());
        }
    }

    private void trim(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> all = files.filter(Files::isRegularFile).toList();
            long total = all.stream().mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
            if (total <= MAX_BYTES) {
                return;
            }
            long target = (long) (MAX_BYTES * 0.8);
            List<Path> sorted = all.stream()
                    .sorted(Comparator.comparingLong(VoiceCache::modified))
                    .toList();
            for (Path p : sorted) {
                if (total <= target) {
                    break;
                }
                long size = Files.size(p);
                Files.deleteIfExists(p);
                total -= size;
            }
            log.info("[VOICE] 缓存超过上限，已清理到 {} MB", target / 1024 / 1024);
        } catch (IOException e) {
            log.warn("[VOICE] 缓存清理失败：{}", e.getMessage());
        }
    }

    private Path pathOf(String key) {
        return dir().resolve(key + ".mp3");
    }

    private Path dir() {
        return Path.of(policy.getTtsCacheDir()).toAbsolutePath().normalize();
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
