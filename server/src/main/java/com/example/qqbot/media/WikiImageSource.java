package com.example.qqbot.media;

import com.example.qqbot.onebot.model.OutboundImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * wiki 抓来的配图 —— 标记 {@code [[img:wiki:<页面>/<文件>]]} 由它解析。
 *
 * <h2>与 {@link KbImageSource} 的区别</h2>
 * 两者都是"从目录读字节"，差别在**目录**与**身份**：
 * <ul>
 *   <li>kb 配图是人工资产，key 就是文件名，目录是平铺的；</li>
 *   <li>wiki 配图是自动抓的，key 带**页面**这一段，因为同一张图可能被两页各存一份，
 *       而"哪一页的图"本身就是回答上下文的一部分。</li>
 * </ul>
 *
 * <h2>安全边界</h2>
 * key 来自模型写的 marker，最终会拼成磁盘路径。这里做三道：
 * 语法校验（段数、字符）、{@code ..} 与分隔符拒绝、拼完再确认仍在 wiki 目录内。
 * 任何一道不过都返回 empty —— 少一张图，绝不读不该读的文件。
 */
@Component
public class WikiImageSource implements ImageSource {

    private static final Logger log = LoggerFactory.getLogger(WikiImageSource.class);

    /** 认的扩展名与 MIME。SVG 不在其中：协议端显示不稳定 */
    private static final List<String> EXTENSIONS = List.of("png", "jpg", "jpeg", "webp", "gif");

    private final MediaStorageGuard storage;

    public WikiImageSource(MediaStorageGuard storage) {
        this.storage = storage;
    }

    @Override
    public String scheme() {
        return "wiki";
    }

    @Override
    public Optional<OutboundImage> resolve(String key) {
        if (key == null || key.isBlank() || key.length() > 160) {
            return Optional.empty();
        }
        // 形状必须是 <页面>/<文件>：多一段少一段都不认
        String[] parts = key.split("/", -1);
        if (parts.length != 2 || !isSafeSegment(parts[0]) || !isSafeSegment(parts[1])) {
            log.warn("[WIKI-IMG] marker key 形状不合法，已拒绝：{}", key);
            return Optional.empty();
        }
        String fileName = parts[1];
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || !EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        try {
            Path root = storage.wikiDir();
            Path dir = root.resolve(parts[0]).normalize();
            Path file = dir.resolve(fileName).normalize();
            if (!dir.startsWith(root) || !file.startsWith(dir) || !Files.isRegularFile(file)) {
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(file);
            return Optional.of(OutboundImage.ofBytes(bytes, mimeOf(fileName)));
        } catch (IOException e) {
            log.warn("[WIKI-IMG] 读取失败：{} —— {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 段校验：不能有路径含义，也不能是 {@code .} / {@code ..}。
     *
     * <p>允许中文与空格 —— 文件名 slug 理论上只有 a-z0-9-，但历史文件可能是人工放进来的，
     * 不该因为字符集把它们判死。
     */
    private static boolean isSafeSegment(String s) {
        if (s == null || s.isBlank() || s.equals(".") || s.equals("..") || s.length() > 80) {
            return false;
        }
        if (s.startsWith(".")) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    private static String mimeOf(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }
}
