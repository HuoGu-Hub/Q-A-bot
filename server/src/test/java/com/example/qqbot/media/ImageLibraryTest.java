package com.example.qqbot.media;

import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.onebot.model.OutboundImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站图库的验收测试 —— 三道闸门：**开关、张数、单张大小**。
 *
 * <p>这三道都是"宁可少发一张图，也不能多发"的闸门，
 * 而且失守时不会有任何报错 —— 所以必须在这里钉死。
 */
class ImageLibraryTest {

    private static final byte[] SMALL = new byte[100];
    private static final byte[] BIG = new byte[3 * 1024 * 1024];

    /** 假供给方：按名字回固定字节 */
    private static final class FakeSource implements ImageSource {
        private final String scheme;
        private boolean explode;

        FakeSource(String scheme) {
            this.scheme = scheme;
        }

        @Override
        public String scheme() {
            return scheme;
        }

        @Override
        public Optional<OutboundImage> resolve(String key) {
            if (explode) {
                throw new IllegalStateException("供给方内部炸了");
            }
            return switch (key) {
                case "small" -> Optional.of(OutboundImage.ofBytes(SMALL, "image/png"));
                case "big" -> Optional.of(OutboundImage.ofBytes(BIG, "image/png"));
                default -> Optional.empty();
            };
        }
    }

    private MediaProperties media;
    private FakeSource kb;
    private ImageLibrary library;

    @BeforeEach
    void setUp() {
        media = new MediaProperties();
        kb = new FakeSource("kb");
        library = new ImageLibrary(media, List.of(kb));
    }

    @Test
    @DisplayName("正常解析：key 变成图片")
    void resolvesKnownKey() {
        List<OutboundImage> images = library.resolveAll(List.of("kb:small"));

        assertThat(images).hasSize(1);
        assertThat(images.get(0).byteSize()).isEqualTo(SMALL.length);
        assertThat(images.get(0).bytesMime()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("★ 张数闸门：超过上限的丢掉，前面的保留")
    void capsImageCount() {
        media.getOutboundImages().setMaxPerReply(1);

        assertThat(library.resolveAll(List.of("kb:small", "kb:small2"))).hasSize(1);
    }

    @Test
    @DisplayName("★ 单张大小闸门：超大图丢掉，其余照发")
    void dropsOversizedImage() {
        media.getOutboundImages().setMaxSizeKb(1024);

        assertThat(library.resolveAll(List.of("kb:big", "kb:small"))).hasSize(1);
    }

    @Test
    @DisplayName("同一个 key 出现两次只发一张 —— 模型重复抄标记不该变成两张图")
    void dedupesSameKey() {
        assertThat(library.resolveAll(List.of("kb:small", "kb:small"))).hasSize(1);
    }

    @Test
    @DisplayName("没有对应供给方的来源：丢掉，不抛异常")
    void unknownSchemeIsDropped() {
        assertThat(library.resolveAll(List.of("nope:small"))).isEmpty();
        assertThat(library.resolveAll(List.of("没有冒号"))).isEmpty();
    }

    @Test
    @DisplayName("★ 供给方自己出问题也只丢这一张，不能把整条回复带走")
    void sourceFailureIsContained() {
        kb.explode = true;

        assertThat(library.resolveAll(List.of("kb:small"))).isEmpty();
    }

    @Test
    @DisplayName("总开关关掉：一个 key 都不解析")
    void disabledResolvesNothing() {
        media.getOutboundImages().setEnabled(false);

        assertThat(library.resolveAll(List.of("kb:small"))).isEmpty();
    }
}
