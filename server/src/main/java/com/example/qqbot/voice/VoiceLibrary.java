package com.example.qqbot.voice;

import com.example.qqbot.persistence.VoiceAssetRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 语音素材库 —— 上传、落盘、挑选、删除。
 *
 * <h2>为什么文件进目录、元数据进库</h2>
 * 音频是二进制，塞进 SQLite 会让备份、试听、清理都变难；而"叫什么、多长、什么标签"
 * 是要查询和编辑的小结构数据。两边用内容哈希命名的文件名关联，天然去重。
 *
 * <h2>为什么上传时就量时长</h2>
 * 时长是判断"这段能不能当语音条"的依据，也是前端列表要显示的东西。
 * 等到发送时才发现超过 5 分钟就太晚了 —— 用户已经以为传成功了。
 *
 * <h2>挑选为什么随机</h2>
 * 关键词只负责触发语音，不负责指定内容。同一个「唱首歌」每次都给同一首会腻，
 * 所以匹配到多条时随机取一条；一条都没有时再交给 TTS。
 */
@Component
public class VoiceLibrary {

    private static final Logger log = LoggerFactory.getLogger(VoiceLibrary.class);

    /** 单文件上限。再大基本是整轨无损，语音条也用不上 */
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    /** 单条时长上限（秒），与 VoicePolicy 的发送上限保持同一量级 */
    private static final int MAX_SECONDS = 300;

    private static final Set<String> ALLOWED_EXT =
            Set.of("mp3", "wav", "m4a", "flac", "ogg", "amr", "aac");

    private final VoiceAssetRepository repo;
    private final VoicePolicy policy;
    private final VoiceProcessor processor;

    private volatile boolean available;

    public VoiceLibrary(VoiceAssetRepository repo, VoicePolicy policy, VoiceProcessor processor) {
        this.repo = repo;
        this.policy = policy;
        this.processor = processor;
    }

    /** 上传结果。{@code duplicate} 为 true 表示库里已有同样的内容，没有重复落盘 */
    public record Uploaded(VoiceAsset asset, boolean duplicate) {
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[VOICE] 数据库不可用，素材库关闭");
            return;
        }
        try {
            Files.createDirectories(dir());
            repo.initSchema();
            available = true;
            log.info("[VOICE] 素材库就绪：{} 条，目录 {}", repo.count(), dir().toAbsolutePath());
        } catch (Exception e) {
            log.warn("[VOICE] 素材库初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available && repo.isAvailable();
    }

    public List<VoiceAsset> list() {
        if (!available) {
            return List.of();
        }
        try {
            return repo.all().stream().map(VoiceLibrary::toAsset).toList();
        } catch (Exception e) {
            log.warn("[VOICE] 读取素材库失败：{}", e.getMessage());
            return List.of();
        }
    }

    public VoiceAsset get(long id) {
        if (!available) {
            return null;
        }
        VoiceAssetRepository.Row row = repo.byId(id);
        return row == null ? null : toAsset(row);
    }

    /** 读音频字节，给管理端试听用 */
    public byte[] audio(long id) {
        VoiceAsset asset = get(id);
        if (asset == null) {
            throw new VoiceException("没有这条语音：" + id);
        }
        try {
            return Files.readAllBytes(pathOf(asset));
        } catch (IOException e) {
            throw new VoiceException("读取音频失败：" + e.getMessage(), e);
        }
    }

    /** 音频在磁盘上的位置。文件名只可能是哈希，这里再挡一次路径穿越 */
    public Path pathOf(VoiceAsset asset) {
        String file = asset.file();
        if (file == null || file.contains("/") || file.contains("\\") || file.contains("..")) {
            throw new VoiceException("素材文件名不合法：" + file);
        }
        return dir().resolve(file);
    }

    /**
     * 上传一段音频。
     *
     * @param fileName 原始文件名，只用来推扩展名
     * @param mime     客户端给的 MIME，空则按扩展名推断
     * @param bytes    音频字节
     * @param title    显示名，空则用去掉扩展名的原始文件名
     * @param aliases  别名，逗号分隔，供模型按不同叫法找
     * @param tags     标签，逗号分隔，例如 歌曲,副歌
     */
    public Uploaded upload(String fileName, String mime, byte[] bytes,
                           String title, String aliases, String tags) {
        if (!available) {
            throw new VoiceException("素材库不可用");
        }
        if (bytes == null || bytes.length == 0) {
            throw new VoiceException("文件是空的");
        }
        if (bytes.length > MAX_BYTES) {
            throw new VoiceException(String.format(Locale.ROOT, "文件太大：%.1f MB，上限 %d MB",
                    bytes.length / 1024.0 / 1024.0, MAX_BYTES / 1024 / 1024));
        }
        String ext = extension(fileName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new VoiceException("不支持的格式：" + ext + "，可用 " + ALLOWED_EXT);
        }

        Path temp = null;
        try {
            Files.createDirectories(dir());
            temp = Files.createTempFile(dir(), "upload-", "." + ext);
            Files.write(temp, bytes);

            double seconds = 0;
            try {
                seconds = processor.probe(temp);
            } catch (Exception e) {
                // 量不出时长不阻止上传：发送时协议端还会再量一次
                log.warn("[VOICE] 量时长失败，按 0 记：{}", e.getMessage());
            }
            if (seconds > MAX_SECONDS) {
                throw new VoiceException(String.format(Locale.ROOT,
                        "音频太长：%.0f 秒，上限 %d 秒", seconds, MAX_SECONDS));
            }

            String stored = sha256(bytes) + "." + ext;
            VoiceAssetRepository.Row existing = repo.byFile(stored);
            if (existing != null) {
                return new Uploaded(toAsset(existing), true);
            }
            Files.move(temp, dir().resolve(stored), StandardCopyOption.REPLACE_EXISTING);
            temp = null;

            String safeTitle = blankTo(title, stripExtension(fileName));
            String safeMime = blankTo(mime, mimeOf(ext));
            long id = repo.insert(safeTitle, nullToEmpty(aliases), nullToEmpty(tags), stored,
                    safeMime, bytes.length, Math.round(seconds * 1000), Instant.now().toString());
            log.info("[VOICE] 已上传素材 #{}「{}」（{} 秒，{} KB）", id, safeTitle,
                    String.format(Locale.ROOT, "%.1f", seconds), bytes.length / 1024);
            return new Uploaded(toAsset(repo.byId(id)), false);
        } catch (IOException e) {
            throw new VoiceException("保存音频失败：" + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清不掉不影响结果
                }
            }
        }
    }

    public VoiceAsset update(long id, String title, String aliases, String tags, Boolean enabled) {
        VoiceAsset old = get(id);
        if (old == null) {
            return null;
        }
        repo.update(id, title == null ? old.title() : title,
                aliases == null ? old.aliases() : aliases,
                tags == null ? old.tags() : tags,
                enabled == null ? old.enabled() : enabled);
        return get(id);
    }

    /** 删除一条，连磁盘文件一起。文件删不掉也照样删记录，避免列表里留死链 */
    public VoiceAsset delete(long id) {
        VoiceAsset asset = get(id);
        if (asset == null) {
            return null;
        }
        repo.delete(id);
        try {
            Files.deleteIfExists(pathOf(asset));
        } catch (Exception e) {
            log.warn("[VOICE] 删除文件失败（记录已删）：{}", e.getMessage());
        }
        return asset;
    }

    /**
     * 按模型给的查询挑一段。空查询就是随机一条。
     *
     * <p>匹配范围是标题、别名、标签：模型可能说「晴天」「周杰伦那首」「歌曲」，
     * 三种都应该能找到。找不到返回空，由调用方决定改用 TTS。
     */
    public Optional<VoiceAsset> pick(String query) {
        List<VoiceAsset> pool = list().stream().filter(VoiceAsset::enabled).toList();
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        if (query == null || query.isBlank()) {
            return Optional.of(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<VoiceAsset> hits = pool.stream()
                .filter(a -> matches(a, q))
                .toList();
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(hits.get(ThreadLocalRandom.current().nextInt(hits.size())));
    }

    private static boolean matches(VoiceAsset a, String q) {
        return contains(a.title(), q) || contains(a.aliases(), q) || contains(a.tags(), q);
    }

    private static boolean contains(String field, String q) {
        return field != null && field.toLowerCase(Locale.ROOT).contains(q);
    }

    private Path dir() {
        return Path.of(policy.getVoicesDir()).toAbsolutePath().normalize();
    }

    private static VoiceAsset toAsset(VoiceAssetRepository.Row r) {
        return new VoiceAsset(r.id(), r.title(), r.aliases(), r.tags(), r.file(), r.mime(),
                r.sizeBytes(), r.durationMs(), r.enabled(), r.createdAt());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new VoiceException("当前 JVM 没有 SHA-256", e);
        }
    }

    private static String extension(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String mimeOf(String ext) {
        return switch (ext) {
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "flac" -> "audio/flac";
            case "ogg" -> "audio/ogg";
            case "amr" -> "audio/amr";
            case "aac" -> "audio/aac";
            default -> "audio/mpeg";
        };
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
