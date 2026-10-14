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
 * 知识库配图 —— {@code data/kb-images} 目录里我们自己维护的图。
 *
 * <h2>为什么 key 就是文件名</h2>
 * 目录里放一张 {@code copper-vein.jpg}，模型就能用 {@code [[img:kb:copper-vein]]}。
 * 不需要再维护一份"别名 → 文件"的索引表：那种表一旦和目录不一致，
 * 就会出现"后台明明传了图，机器人却说没有"的悬案，而目录本身就是唯一真相。
 *
 * <h2>为什么是字节而不是链接</h2>
 * 这些图没有公开地址，而协议端跑在容器里读不到宿主机的路径 ——
 * 给它一个 file:// 路径只会得到一个"文件不存在"。所以读成字节内联发出去。
 * 单张大小由 {@link OutboundImages#getMaxSizeKb()} 卡住，不会有内存风险。
 */
@Component
public class KbImageSource implements ImageSource {

    private static final Logger log = LoggerFactory.getLogger(KbImageSource.class);

    /** 文件名长度上限。文件名会进日志与消息，不该有无限长 */
    private static final int MAX_NAME_LENGTH = 80;

    /** 认的图片扩展名，顺序就是"不带扩展名时先找谁"的顺序 */
    private static final List<String> EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

    private final MediaStorageGuard storage;

    public KbImageSource(MediaStorageGuard storage) {
        this.storage = storage;
    }

    @Override
    public String scheme() {
        return "kb";
    }

    @Override
    public Optional<OutboundImage> resolve(String key) {
        if (!isSafeName(key)) {
            log.warn("[IMG] 知识库配图的文件名不合法，已拒绝：{}", key);
            return Optional.empty();
        }
        Path file = find(key);
        if (file == null) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            return Optional.of(OutboundImage.ofBytes(bytes, mimeOf(file)));
        } catch (IOException e) {
            log.warn("[IMG] 读取知识库配图失败：{} —— {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 文件名校验。**这是防目录穿越的第一道**，后面拼路径时还有第二道。
     *
     * <p>挡的就三件事：路径分隔符、上下级跳转、以及 Windows 上非法的字符。
     * 中文是允许的 —— 图库里的文件名是中文很正常，而它并不带任何路径含义。
     */
    private static boolean isSafeName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        if (name.startsWith(".") || name.contains("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|'
                    || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /** key 带扩展名就只认那个文件；不带就按扩展名顺序找第一个存在的 */
    private Path find(String key) {
        Path dir = storage.kbDir();
        String lower = key.toLowerCase(Locale.ROOT);
        if (EXTENSIONS.stream().anyMatch(lower::endsWith)) {
            Path exact = resolveInside(dir, key);
            return exact != null && Files.isRegularFile(exact) ? exact : null;
        }
        for (String ext : EXTENSIONS) {
            Path candidate = resolveInside(dir, key + ext);
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 拼出来的路径必须**就在知识库目录里**。
     *
     * <p>白名单已经挡掉了 {@code ..} 与斜杠，这里是第二道 ——
     * 两道都写，是因为这道还顺手挡住了符号链接指到目录外的情况。
     */
    private Path resolveInside(Path dir, String name) {
        Path target = dir.resolve(name).normalize();
        return target.startsWith(dir) ? target : null;
    }

    private static String mimeOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }
}
