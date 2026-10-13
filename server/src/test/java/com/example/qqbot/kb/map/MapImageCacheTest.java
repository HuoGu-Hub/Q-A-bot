package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 地图图片缓存。
 *
 * <p>用的是一个**假来源**（{@link MapImageSource}）而不是真去连 wiki：
 * 这里要证明的是"第二次不再下载"与"可疑文件名进不来"，
 * 连网验证只会让这两条断言变得又慢又不可靠。
 */
class MapImageCacheTest {

    /** 计数用的假来源 —— 每次取图都会 +1，于是"命中缓存"这件事可被断言 */
    private static final class CountingSource implements MapImageSource {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger lastWidth = new AtomicInteger(-1);

        @Override
        public byte[] fetch(String fileName, int width) {
            calls.incrementAndGet();
            lastWidth.set(width);
            return ("bytes:" + fileName + ":" + width).getBytes();
        }

        @Override
        public String describe() {
            return "counting";
        }
    }

    private static MapOptions options(Path dir) {
        MapOptions o = new MapOptions();
        o.setTileDir(dir.toString());
        return o;
    }

    @Test
    @DisplayName("第一次取图走来源并落盘，第二次直接命中缓存 —— 不再打扰 wiki")
    void secondCallHitsDisk(@TempDir Path dir) {
        CountingSource src = new CountingSource();
        MapImageCache cache = new MapImageCache(options(dir), src);

        Optional<MapImageCache.Image> first = cache.get("Embervale", "Map:Embervale/Background", "0_0.jpg", 320);
        Optional<MapImageCache.Image> second = cache.get("Embervale", "Map:Embervale/Background", "0_0.jpg", 320);

        assertThat(first).isPresent();
        assertThat(first.get().fromCache()).isFalse();
        assertThat(second).isPresent();
        assertThat(second.get().fromCache()).isTrue();
        assertThat(src.calls).hasValue(1);
        assertThat(second.get().bytes()).isEqualTo(first.get().bytes());
    }

    @Test
    @DisplayName("不同宽度是不同缓存项 —— 缩略图与原图不能互相顶掉")
    void widthIsPartOfTheKey(@TempDir Path dir) {
        CountingSource src = new CountingSource();
        MapImageCache cache = new MapImageCache(options(dir), src);

        cache.get("Embervale", "Map:Embervale/Background", "0_0.jpg", 320);
        cache.get("Embervale", "Map:Embervale/Background", "0_0.jpg", 0);

        assertThat(src.calls).hasValue(2);
        assertThat(src.lastWidth).hasValue(0);
    }

    @Test
    @DisplayName("宽度不在白名单就退回原图，而不是报错 —— 它只影响清晰度")
    void widthWhitelistFallsBackToOriginal(@TempDir Path dir) {
        MapImageCache cache = new MapImageCache(options(dir), new CountingSource());
        assertThat(cache.normalizeWidth(999)).isZero();
        assertThat(cache.normalizeWidth(-5)).isZero();
        assertThat(cache.normalizeWidth(320)).isEqualTo(320);
    }

    @Test
    @DisplayName("总开关关掉时一次网络都不发")
    void disabledDoesNothing(@TempDir Path dir) {
        CountingSource src = new CountingSource();
        MapOptions o = options(dir);
        o.setEnabled(false);
        MapImageCache cache = new MapImageCache(o, src);

        assertThat(cache.get("Embervale", "p", "0_0.jpg", 0)).isEmpty();
        assertThat(src.calls).hasValue(0);
    }

    @Test
    @DisplayName("★ 防目录穿越：带路径片段的文件名一律拒绝，且不发请求")
    void rejectsTraversal(@TempDir Path dir) {
        CountingSource src = new CountingSource();
        MapImageCache cache = new MapImageCache(options(dir), src);

        assertThat(cache.get("m", "p", "../../etc/passwd", 0)).isEmpty();
        assertThat(cache.get("m", "p", "..\\windows\\system32", 0)).isEmpty();
        assertThat(cache.get("m", "p", "sub/dir.png", 0)).isEmpty();
        assertThat(cache.get("m", "p", ".hidden", 0)).isEmpty();
        assertThat(src.calls).hasValue(0);
    }

    @Test
    @DisplayName("合法的古怪文件名要放行 —— 组图标里有撇号和括号")
    void acceptsOddButLegalNames() {
        assertThat(MapImageCache.safeName("Ripped Alchemist's Wall Hanging (Left).png"))
                .isEqualTo("Ripped_Alchemist's_Wall_Hanging_(Left).png");
        assertThat(MapImageCache.safeName("0_0.jpg")).isEqualTo("0_0.jpg");
        assertThatThrownBy(() -> MapImageCache.safeName("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("缓存目录按地图/页面/宽度分层，路径可预测")
    void layout(@TempDir Path dir) {
        MapImageCache cache = new MapImageCache(options(dir), new CountingSource());
        Path p = cache.pathOf("Embervale", "Map:Embervale/Background", "0_0.jpg", 320);
        assertThat(p.toString().replace('\\', '/'))
                .endsWith("Embervale/Map_Embervale_Background/320/0_0.jpg");
    }

    @Test
    @DisplayName("来源抛异常时不写坏文件，调用方拿到空值")
    void sourceFailureIsSwallowed(@TempDir Path dir) {
        MapImageSource broken = new MapImageSource() {
            @Override
            public byte[] fetch(String fileName, int width) throws IOException {
                throw new IOException("boom");
            }

            @Override
            public String describe() {
                return "broken";
            }
        };
        MapImageCache cache = new MapImageCache(options(dir), broken);
        assertThat(cache.get("m", "p", "0_0.jpg", 0)).isEmpty();
    }

    @Test
    @DisplayName("扩展名推 MIME；认不出给八位流")
    void mime() {
        assertThat(MapImageCache.mimeOf("0_0.JPG")).isEqualTo("image/jpeg");
        assertThat(MapImageCache.mimeOf("a.png")).isEqualTo("image/png");
        assertThat(MapImageCache.mimeOf("a.webp")).isEqualTo("image/webp");
        assertThat(MapImageCache.mimeOf("a.bin")).isEqualTo("application/octet-stream");
    }
}
