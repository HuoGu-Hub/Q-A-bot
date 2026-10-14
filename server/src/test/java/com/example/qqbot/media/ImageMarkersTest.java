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

    @Test
    @DisplayName("★ 单个标记被提到正文末尾单独一行 —— 模型改写正文时最不容易漏")
    void hoistsSingleMarkerToEnd() {
        String hoisted = ImageMarkers.hoistSingleMarker(
                "一把涂满酸液的砍刀。[[img:kb:acid-cleaver]]\n获取：由拾荒者掉落。");

        assertThat(hoisted).isEqualTo("一把涂满酸液的砍刀。\n获取：由拾荒者掉落。\n[[img:kb:acid-cleaver]]");
    }

    @Test
    @DisplayName("已经在末尾的标记：原样返回，不来回搬")
    void keepsMarkerAlreadyAtEnd() {
        String text = "正文\n[[img:kb:a]]";

        assertThat(ImageMarkers.hoistSingleMarker(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("★ 两个标记一个都不挪 —— 挪了就把「哪张图配哪一段」弄丢了")
    void doesNotHoistWhenMultipleMarkers() {
        String text = "甲：[[img:kb:a]] 乙：[[img:kb:b]]";

        assertThat(ImageMarkers.hoistSingleMarker(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("没有标记：一个字都不改")
    void hoistWithoutMarkerIsNoop() {
        String text = "就是一句 普通的话";

        assertThat(ImageMarkers.hoistSingleMarker(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("只有标记时，挪完还是那一行")
    void hoistMarkerOnly() {
        assertThat(ImageMarkers.hoistSingleMarker("[[img:kb:a]]")).isEqualTo("[[img:kb:a]]");
    }

    @Test
    @DisplayName("不合法的标记不参与「唯一标记」的判断，也不会被搬到末尾")
    void hoistIgnoresMalformedMarker() {
        String text = "正文 [[img:没来源]] [[img:kb:a]]";

        assertThat(ImageMarkers.hoistSingleMarker(text)).endsWith("[[img:kb:a]]");
    }
}
