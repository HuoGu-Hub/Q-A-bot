package com.example.qqbot.site;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 首页轮播的**图片文件**管理：校验 → 落盘 → 读取。
 *
 * <p>清单（顺序、说明、启停）在 {@link CarouselStore}，这里只管盘上的字节。
 *
 * <h2>校验为什么按"内容"而不是"文件名"</h2>
 * 只看扩展名的话，把 {@code evil.svg} 改成 {@code evil.png} 就能传上来 ——
 * SVG 里可以塞脚本，图片接口又是公开可访问的，那就是一个存储型 XSS。
 * 所以这里：
 * <ol>
 *   <li>先看<b>magic bytes</b>认出真实格式（PNG / JPEG / GIF / WEBP），认不出直接拒；</li>
 *   <li>PNG/JPEG/GIF 再用 {@link ImageIO} 真解码一次，解不开也拒（顺带拿到宽高）；</li>
 *   <li>存盘文件名由我们生成（UUID + 真实扩展名），**绝不使用客户端传来的文件名**，
 *       从根上断掉目录穿越。</li>
 * </ol>
 * WEBP 是个例外：JDK 自带的 ImageIO 不认它，但它在前端很常用 ——
 * 所以只校验 magic bytes，宽高留 0（前端按默认比例渲染）。
 */
@Service
public class CarouselService {

    private static final Logger log = LoggerFactory.getLogger(CarouselService.class);

    /** 自动切换间隔的下限：再小就成闪光弹了 */
    private static final int MIN_INTERVAL_MS = 1000;

    private final CarouselStore store;
    private final SitePolicy props;

    public CarouselService(CarouselStore store, SitePolicy props) {
        this.store = store;
        this.props = props;
    }

    /** 上传或格式不对时抛这个，控制器把它翻成 400 + 人话 */
    public static class CarouselException extends RuntimeException {
        public CarouselException(String message) {
            super(message);
        }
    }

    /** 读出来的一张图 */
    public record ImageData(byte[] bytes, String mime) {
    }

    /**
     * 启动时把**解析后的绝对路径**打进日志。
     *
     * <p>因为它是相对路径（默认 {@code ./data/site-images}），实际落在哪取决于**进程的工作目录** ——
     * 从仓库根起、从 server/ 起、systemd 里的 WorkingDirectory，三者结果都不一样。
     * 不打印的话，"图传上去了但不知道存哪了"就得靠猜。
     */
    @PostConstruct
    void logDir() {
        log.info("[SITE] 首页轮播图片目录：{}（配置项 app.site.carousel.dir）", dir());
    }

    private Carousel config() {
        return props.getCarousel();
    }

    /** 图片目录（绝对路径，已规范化） */
    public Path dir() {
        return Paths.get(config().getDir()).toAbsolutePath().normalize();
    }

    /**
     * {@code imageUrl} 上的版本号 —— 用磁盘文件名算（每次上传都是新的 UUID）。
     *
     * <p>图片接口给的是 {@code immutable} 长缓存：没有这个版本号的话，
     * "删掉再传一张"万一复用了同一个 id，浏览器会一直显示上一张的缓存图。
     */
    public static String versionOf(CarouselStore.Item item) {
        return Integer.toHexString(String.valueOf(item.file()).hashCode());
    }

    /** 公开站用的切换间隔（已按下限夹紧） */
    public int publicIntervalMs() {
        return Math.max(MIN_INTERVAL_MS, config().getIntervalMs());
    }

    /**
     * 新增一张。
     *
     * @param file    上传的图片
     * @param link    点击跳转（可空）
     * @param caption 说明文字（可空）
     * @throws CarouselException 超张数 / 太大 / 不是图片 / 落盘失败
     */
    public CarouselStore.Item add(MultipartFile file, String link, String caption) throws IOException {
        if (!store.isAvailable()) {
            throw new CarouselException("轮播存储不可用（问答库没起来）");
        }
        if (file == null || file.isEmpty()) {
            throw new CarouselException("没有选到文件");
        }
        int max = Math.max(1, config().getMaxCount());
        if (store.count() >= max) {
            throw new CarouselException("最多 " + max + " 张，先删一张再传");
        }
        long maxBytes = Math.max(1, config().getMaxSizeKb()) * 1024L;
        if (file.getSize() > maxBytes) {
            throw new CarouselException("图片太大（" + (file.getSize() / 1024) + " KB），上限 "
                    + config().getMaxSizeKb() + " KB");
        }

        byte[] bytes = file.getBytes();
        if (bytes.length == 0) {
            throw new CarouselException("文件是空的");
        }
        if (bytes.length > maxBytes) {
            throw new CarouselException("图片太大，上限 " + config().getMaxSizeKb() + " KB");
        }

        Format fmt = sniff(bytes);
        if (!"image/webp".equals(fmt.mime())) {
            // 能解出宽高才算真图片；解不开说明是"改了扩展名的别的东西"
            BufferedImage img = decode(bytes);
            if (img == null) {
                throw new CarouselException("这不是有效的图片文件");
            }
            return save(bytes, fmt, img.getWidth(), img.getHeight(), link, caption);
        }
        return save(bytes, fmt, 0, 0, link, caption);
    }

    private CarouselStore.Item save(byte[] bytes, Format fmt, int width, int height,
                                    String link, String caption) throws IOException {
        Path dir = dir();
        Files.createDirectories(dir);
        // 文件名我们自己生成：UUID + 嗅探出来的真实扩展名。客户端文件名一概不用。
        String name = UUID.randomUUID().toString().replace("-", "") + "." + fmt.ext();
        Path tmp = dir.resolve(name + ".part");
        Path target = dir.resolve(name);
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        long id = store.add(name, fmt.mime(), width, height, trim(link), trim(caption));
        if (id <= 0) {
            Files.deleteIfExists(target);   // 入库失败就把文件收回来，别留下没人认领的字节
            throw new CarouselException("保存失败（写入清单时出错）");
        }
        log.info("[SITE] 轮播图已上传：id={} {}×{} {}（{} 字节）",
                id, width, height, fmt.mime(), bytes.length);
        return store.get(id);
    }

    /** 删除一张（连磁盘文件一起）。返回被删的行；没有返回 null */
    public CarouselStore.Item delete(long id) {
        CarouselStore.Item gone = store.delete(id);
        if (gone == null) {
            return null;
        }
        try {
            Files.deleteIfExists(safePath(gone.file()));
        } catch (Exception e) {
            // 文件删不掉不影响"这张图已下线"：清单里已经没有了，永远不会再被读到
            log.warn("[SITE] 轮播图文件删除失败（清单已删，图不会再出现）：{}", e.getMessage());
        }
        return gone;
    }

    /** 按 id 读图片字节；没有返回 null */
    public ImageData read(long id) throws IOException {
        CarouselStore.Item item = store.get(id);
        if (item == null) {
            return null;
        }
        Path p = safePath(item.file());
        if (p == null || !Files.isRegularFile(p)) {
            log.warn("[SITE] 轮播图文件缺失：id={} file={}", id, item.file());
            return null;
        }
        return new ImageData(Files.readAllBytes(p), item.mime());
    }

    /**
     * 把库里的文件名解析成目录内的路径。
     *
     * <p>文件名本来就是我们自己生成的 UUID，但这里仍然做一次前缀校验 ——
     * 万一日后有人手工往库里塞了 {@code ../../etc/passwd}，这一行能挡住。
     */
    private Path safePath(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        Path dir = dir();
        Path p = dir.resolve(file).normalize();
        return p.startsWith(dir) ? p : null;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private record Format(String mime, String ext) {
    }

    /** 按 magic bytes 认格式。认不出就抛（顺带把"改扩展名"这条堵死） */
    private static Format sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return new Format("image/png", "png");
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return new Format("image/jpeg", "jpg");
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return new Format("image/gif", "gif");
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return new Format("image/webp", "webp");
        }
        throw new CarouselException("只支持 PNG / JPEG / GIF / WEBP 四种图片");
    }

    /** 真解码一次；解不开返回 null */
    private static BufferedImage decode(byte[] b) {
        try {
            return ImageIO.read(new ByteArrayInputStream(b));
        } catch (Exception e) {
            return null;
        }
    }
}
