package com.example.qqbot.site;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SiteCarouselRepository;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;

import com.example.qqbot.config.QaProperties;
import com.example.qqbot.config.SiteProperties;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 轮播图片上传 / 读取的验收测试。
 *
 * <p>重点是**校验**：这块是"管理端唯一收文件的地方"，也是整个站点唯一能把字节写到
 * 服务器上的入口。所以「改了扩展名的非图片」「超限」「超张数」都必须被挡住，
 * 而真正合法的图片必须原样存取。
 */
class CarouselServiceTest {

    @TempDir
    Path base;

    private QaStore qaStore;
    private SqliteDatabase db;

    private CarouselStore store;
    private CarouselService service;
    private SiteProperties props;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();

        store = new CarouselStore(new SiteCarouselRepository(new Jdbc(db)));
        store.init();

        props = new SiteProperties();
        props.getCarousel().setDir(base.resolve("site-images").toString());
        props.getCarousel().setMaxSizeKb(64);
        props.getCarousel().setMaxCount(3);

        service = new CarouselService(store, props);
    }

    /** 真的生成一张 PNG（ImageIO 编码），不是硬编码的字节 */
    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /**
     * 噪声图：纯色图 PNG 压得极小（200×200 才 195 字节），
     * 拿它测"超过大小上限"会误判 —— 随机像素才压不动。
     */
    private static byte[] noisyPng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(42);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    @AfterEach
    void closeStores() {
        db.close();
    }

    @Test
    @DisplayName("★ 上传真图片：存下来、宽高正确、能原样读回")
    void uploadsRealImage() throws Exception {
        byte[] bytes = png(12, 7);

        CarouselStore.Item item = service.add(file("随便什么名字.png", bytes), "https://a.b", "说明");

        assertThat(item).isNotNull();
        assertThat(item.mime()).isEqualTo("image/png");
        assertThat(item.width()).isEqualTo(12);
        assertThat(item.height()).isEqualTo(7);
        assertThat(item.caption()).isEqualTo("说明");

        CarouselService.ImageData data = service.read(item.id());
        assertThat(data).isNotNull();
        assertThat(data.mime()).isEqualTo("image/png");
        assertThat(data.bytes()).isEqualTo(bytes);

        // 落盘文件名是我们生成的 UUID，不是客户端那个名字
        assertThat(item.file()).doesNotContain("随便什么名字");
        assertThat(Files.isRegularFile(service.dir().resolve(item.file()))).isTrue();
    }

    @Test
    @DisplayName("★ 改了扩展名的非图片被拒（否则就是一个存储型 XSS 的口子）")
    void rejectsFakeImage() {
        byte[] notAnImage = "<svg onload=alert(1)></svg>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.add(file("evil.png", notAnImage), "", ""))
                .isInstanceOf(CarouselService.CarouselException.class)
                .hasMessageContaining("只支持");
        assertThat(store.count()).as("被拒之后不能留下半条记录").isZero();
    }

    @Test
    @DisplayName("★ 魔数对得上但内容坏掉的也拒（真解码一次）")
    void rejectsCorruptPng() {
        // PNG 的魔数 + 一堆垃圾：能骗过 magic bytes，但 ImageIO 解不开
        byte[] fake = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5};

        assertThatThrownBy(() -> service.add(file("x.png", fake), "", ""))
                .isInstanceOf(CarouselService.CarouselException.class)
                .hasMessageContaining("有效的图片");
    }

    @Test
    @DisplayName("超过单张大小上限被拒")
    void rejectsTooLarge() throws Exception {
        byte[] bytes = noisyPng(200, 200);   // 噪声图，压不动，稳稳超过 64KB
        assertThat(bytes.length).isGreaterThan(64 * 1024);

        assertThatThrownBy(() -> service.add(file("big.png", bytes), "", ""))
                .isInstanceOf(CarouselService.CarouselException.class)
                .hasMessageContaining("太大");
    }

    @Test
    @DisplayName("到张数上限被拒（让人先删一张）")
    void rejectsWhenFull() throws Exception {
        byte[] bytes = png(4, 4);
        for (int i = 0; i < 3; i++) {
            service.add(file("a" + i + ".png", bytes), "", "第" + i);
        }

        assertThatThrownBy(() -> service.add(file("d.png", bytes), "", "第四张"))
                .isInstanceOf(CarouselService.CarouselException.class)
                .hasMessageContaining("最多 3 张");
    }

    @Test
    @DisplayName("WEBP 只验魔数也收（JDK 的 ImageIO 不认它），宽高留 0")
    void acceptsWebpByMagic() throws Exception {
        // RIFF....WEBP + 后面随便几个字节
        byte[] webp = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1, 2, 3};

        CarouselStore.Item item = service.add(file("x.webp", webp), "", "webp");

        assertThat(item.mime()).isEqualTo("image/webp");
        assertThat(item.width()).isZero();
        assertThat(service.read(item.id()).bytes()).isEqualTo(webp);
    }

    @Test
    @DisplayName("删除会把磁盘文件一起带走；读不存在的 id 返回 null")
    void deleteRemovesFile() throws Exception {
        CarouselStore.Item item = service.add(file("a.png", png(3, 3)), "", "");
        Path onDisk = service.dir().resolve(item.file());
        assertThat(Files.isRegularFile(onDisk)).isTrue();

        assertThat(service.delete(item.id())).isNotNull();
        assertThat(Files.exists(onDisk)).as("文件也要删掉").isFalse();
        assertThat(service.read(item.id())).isNull();
        assertThat(service.delete(item.id())).as("再删一次返回 null").isNull();
    }

    @Test
    @DisplayName("切换间隔有下限：配成 0 也按 1000 算")
    void intervalHasFloor() {
        props.getCarousel().setIntervalMs(0);
        assertThat(service.publicIntervalMs()).isEqualTo(1000);

        props.getCarousel().setIntervalMs(6000);
        assertThat(service.publicIntervalMs()).isEqualTo(6000);
    }

    /* ==================== 按需缩放（见 CarouselService.read(long, int)） ====================
       背景：原图 2560 宽，而首屏那块只显示到大约 600×340。
       直接下发原图让六张图合计 16.86 MB，弱网下把链路占满、点导航要等 20 秒以上。
       下面验的是**接口契约**，不是画质 —— 画质靠 ImageScaler 里那两段逻辑。 */

    /** 真的用到透明的 PNG：左半边透明、右半边不透明 */
    private static byte[] transparentPng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, x < w / 2 ? 0x00000000 : 0xFF3366CC);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** 缓存目录里属于这张原图的缩放件数量。目录还不存在就是 0 —— 它是按需创建的 */
    private long variantCount(String file) throws Exception {
        Path dir = service.cacheDir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        String base = file.substring(0, file.lastIndexOf("."));
        try (var stream = Files.newDirectoryStream(dir, base + "@*")) {
            long n = 0;
            for (Path ignored : stream) {
                n++;
            }
            return n;
        }
    }

    @Test
    @DisplayName("★ 传了宽度就缩到那个宽度，全不透明的图重编码成 JPEG")
    void scalesToRequestedWidth() throws Exception {
        props.getCarousel().setMaxSizeKb(20000);
        CarouselStore.Item item = service.add(file("big.png", noisyPng(1200, 600)), "", "");

        CarouselService.ImageData scaled = service.read(item.id(), 400);

        assertThat(scaled.mime())
                .as("照片存成 PNG 自带的全不透明 alpha 不该让它继续以 PNG 下发，否则等于白缩")
                .isEqualTo("image/jpeg");
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(scaled.bytes()));
        assertThat(back.getWidth()).isEqualTo(400);
        assertThat(back.getHeight()).isEqualTo(200);

        CarouselService.ImageData raw = service.read(item.id());
        assertThat(scaled.bytes().length).as("缩放件必须明显小于原图").isLessThan(raw.bytes().length);
    }

    @Test
    @DisplayName("不放大：要的宽度比原图还大就老实发原图")
    void doesNotUpscale() throws Exception {
        byte[] bytes = png(300, 200);
        CarouselStore.Item item = service.add(file("s.png", bytes), "", "");

        CarouselService.ImageData data = service.read(item.id(), 1280);

        assertThat(data.bytes()).isEqualTo(bytes);
        assertThat(data.mime()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("真的用到透明的图仍然发 PNG，不许压平")
    void keepsPngWhenAlphaIsUsed() throws Exception {
        props.getCarousel().setMaxSizeKb(20000);
        CarouselStore.Item item = service.add(file("t.png", transparentPng(600, 300)), "", "");

        CarouselService.ImageData scaled = service.read(item.id(), 200);

        assertThat(scaled.mime()).isEqualTo("image/png");
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(scaled.bytes()));
        assertThat(back.getWidth()).isEqualTo(200);
        assertThat(back.getColorModel().hasAlpha()).as("透明通道要留住").isTrue();
    }

    @Test
    @DisplayName("GIF 原样透传：缩放只会把动图变成一张静帧")
    void passesGifThrough() throws Exception {
        BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "gif", bos);
        byte[] gif = bos.toByteArray();
        CarouselStore.Item item = service.add(file("a.gif", gif), "", "");

        CarouselService.ImageData data = service.read(item.id(), 100);

        assertThat(data.bytes()).isEqualTo(gif);
        assertThat(data.mime()).isEqualTo("image/gif");
        assertThat(variantCount(item.file())).as("透传就不该产生缓存件").isZero();
    }

    @Test
    @DisplayName("★ 同一宽度只生成一次；删图时缩放件一起清掉")
    void cachesVariantAndClearsOnDelete() throws Exception {
        props.getCarousel().setMaxSizeKb(20000);
        CarouselStore.Item item = service.add(file("c.png", noisyPng(1000, 500)), "", "");

        byte[] first = service.read(item.id(), 320).bytes();
        assertThat(variantCount(item.file())).as("第一次生成一份").isEqualTo(1);

        byte[] second = service.read(item.id(), 320).bytes();
        assertThat(second).isEqualTo(first);
        assertThat(variantCount(item.file())).as("第二次必须命中缓存，不能再写一份").isEqualTo(1);

        service.delete(item.id());
        assertThat(variantCount(item.file())).as("删图要连缩放件一起带走").isZero();
    }

    @Test
    @DisplayName("宽度超出允许范围就退回原图，不缩")
    void ignoresOutOfRangeWidth() throws Exception {
        props.getCarousel().setMaxSizeKb(20000);
        byte[] bytes = noisyPng(1200, 600);
        CarouselStore.Item item = service.add(file("r.png", bytes), "", "");

        assertThat(service.read(item.id(), 8).bytes()).as("比下限还小").isEqualTo(bytes);
        assertThat(service.read(item.id(), 99999).bytes()).as("比上限还大").isEqualTo(bytes);
        assertThat(variantCount(item.file())).isZero();
    }
}
