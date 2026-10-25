package com.example.qqbot.kb.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 选图的验收：**只认 infobox 主图，而且文件名必须能安全地变成磁盘路径**。
 *
 * <p>这些 fixture 都抄自真实的 {@code action=parse} 输出，包括缩略图地址、
 * {@code alt} 里的文件名、以及没有 infobox 的剧情页。
 */
class WikiImagePickerTest {

    private static final String ITEM_PAGE = """
            <div class="mw-parser-output">
              <div class="druid-infobox druid-container" id="druid-container-1">
                <div><div class="druid-title">Fell Dragon Youngling</div></div>
                <div class="druid-section-container">
                  <div class="druid-main-image">
                    <a href="/wiki/File:Fell_Dragon_Youngling.png" class="image">
                      <img alt="Fell Dragon Youngling.png"
                           src="/images/thumb/Fell_Dragon_Youngling.png/250px-Fell_Dragon_Youngling.png?7bc337">
                    </a>
                  </div>
                </div>
              </div>
              <p>正文段落</p>
            </div>
            """;

    @Test
    @DisplayName("物品页：取 infobox 主图，文件名来自 alt")
    void picksInfoboxMainImage() {
        WikiImagePicker.Pick pick = WikiImagePicker.pick(ITEM_PAGE).orElseThrow();

        assertThat(pick.fileName()).isEqualTo("Fell Dragon Youngling.png");
        assertThat(pick.url()).contains("250px-Fell_Dragon_Youngling.png");
    }

    @Test
    @DisplayName("剧情页没有 infobox → 不配图")
    void noInfoboxMeansNoImage() {
        assertThat(WikiImagePicker.pick("<div class=\"mw-parser-output\"><p>一段剧情</p></div>")).isEmpty();
    }

    @Test
    @DisplayName("没有 alt 时从缩略图地址还原文件名")
    void fallsBackToSrc() {
        String html = """
                <div class="druid-infobox"><div class="druid-main-image">
                  <img src="/images/thumb/Baby_Goat.png/250px-Baby_Goat.png?4f4170">
                </div></div>
                """;

        assertThat(WikiImagePicker.pick(html).orElseThrow().fileName()).isEqualTo("Baby Goat.png");
    }

    @Test
    @DisplayName("alt 被污染时不会挡住链接里的合法文件名")
    void unsafeAltFallsThrough() {
        String html = """
                <div class="druid-infobox"><div class="druid-main-image">
                  <a href="/wiki/File:Cyclops.png">
                    <img alt="../../etc/passwd" src="/images/thumb/Cyclops.png/250px-Cyclops.png">
                  </a>
                </div></div>
                """;

        assertThat(WikiImagePicker.pick(html).orElseThrow().fileName()).isEqualTo("Cyclops.png");
    }

    @Test
    @DisplayName("★ 文件名白名单：路径分隔符、..、冒号一律拒绝")
    void rejectsDangerousNames() {
        assertThat(WikiImagePicker.isSafeFileName("../.env")).isFalse();
        assertThat(WikiImagePicker.isSafeFileName("a/b.png")).isFalse();
        assertThat(WikiImagePicker.isSafeFileName("a\\b.png")).isFalse();
        assertThat(WikiImagePicker.isSafeFileName("C:secret.png")).isFalse();
        assertThat(WikiImagePicker.isSafeFileName("no-extension")).isFalse();
        assertThat(WikiImagePicker.isSafeFileName("icon.svg")).isFalse();
        assertThat(WikiImagePicker.isSafeFileName("Artificer's Boots.png")).isTrue();
        assertThat(WikiImagePicker.isSafeFileName("Ripped Alchemist's Wall Hanging (Left).png")).isTrue();
    }

    @Test
    @DisplayName("API 的 File: 与 HTML 的下划线形式归一到同一个字符串")
    void normalizesNames() {
        assertThat(WikiImagePicker.normalize("File:Fell_Dragon_Youngling.png"))
                .isEqualTo("Fell Dragon Youngling.png");
        assertThat(WikiImagePicker.normalize("Image:Baby_Goat.png?abc"))
                .isEqualTo("Baby Goat.png");
        assertThat(WikiImagePicker.normalize("  Baby   Goat.png ")).isEqualTo("Baby Goat.png");
    }
}
