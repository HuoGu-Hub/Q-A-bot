package com.example.qqbot.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片标记解析的验收测试。
 *
 * <p>要证明的只有一件事：**标记一定被摘掉，正文一个字都不多不少**。
 * 摘不掉就会在群里出现 {@code [[img:...]]} 这种字样；
 * 多删了字就是把回答改坏了 —— 两种都是"静默的错"。
 */
class ImageMarkersTest {

    @Test
    @DisplayName("正常标记：正文留下、key 取出")
    void parsesSingleMarker() {
        ImageMarkers.Parsed p = ImageMarkers.parse("它长这样 [[img:kb:copper-vein]]");

        assertThat(p.text()).isEqualTo("它长这样");
        assertThat(p.keys()).containsExactly("kb:copper-vein");
    }

    @Test
    @DisplayName("多个标记按出现顺序取出，正文里的位置被摘干净")
    void parsesMultipleMarkers() {
        ImageMarkers.Parsed p = ImageMarkers.parse("前 [[img:kb:a]] 中 [[img:map:b/c]] 后");

        assertThat(p.text()).isEqualTo("前 中 后");
        assertThat(p.keys()).containsExactly("kb:a", "map:b/c");
    }

    @Test
    @DisplayName("没有标记时正文一个字都不能动")
    void noMarkerKeepsTextIntact() {
        String raw = "就是 一句  正常的话";

        ImageMarkers.Parsed p = ImageMarkers.parse(raw);

        assertThat(p.text()).isEqualTo(raw);
        assertThat(p.keys()).isEmpty();
    }

    @Test
    @DisplayName("不合法的 key：标记照样摘掉，但不收进 key 列表")
    void malformedKeyIsRemovedButNotResolved() {
        ImageMarkers.Parsed p = ImageMarkers.parse("图 [[img:没有来源前缀]] 完");

        assertThat(p.text()).isEqualTo("图 完");
        assertThat(p.keys()).isEmpty();
    }

    @Test
    @DisplayName("带目录穿越的 key 直接判不合法 —— 它会变成磁盘路径，不能放进列表")
    void traversalKeyIsRejected() {
        ImageMarkers.Parsed p = ImageMarkers.parse("[[img:kb:../../.env]]");

        assertThat(p.keys()).isEmpty();
        assertThat(p.text()).isEmpty();
    }

    @Test
    @DisplayName("没闭合的方括号不是标记，原样留在正文里")
    void unterminatedMarkerStaysInText() {
        String raw = "半个标记 [[img:kb:demo";

        ImageMarkers.Parsed p = ImageMarkers.parse(raw);

        assertThat(p.text()).isEqualTo(raw);
        assertThat(p.keys()).isEmpty();
    }

    @Test
    @DisplayName("标记在开头/结尾：不会留下多余空格")
    void trimsAroundRemovedMarker() {
        assertThat(ImageMarkers.parse("[[img:kb:a]] 结尾").text()).isEqualTo("结尾");
        assertThat(ImageMarkers.parse("开头 [[img:kb:a]]").text()).isEqualTo("开头");
    }

    @Test
    @DisplayName("只有标记：正文为空，key 还在 —— 这决定了「只发一张图」能不能成立")
    void markerOnly() {
        ImageMarkers.Parsed p = ImageMarkers.parse("[[img:kb:a]]");

        assertThat(p.text()).isEmpty();
        assertThat(p.keys()).containsExactly("kb:a");
        assertThat(p.hasKeys()).isTrue();
    }
}
