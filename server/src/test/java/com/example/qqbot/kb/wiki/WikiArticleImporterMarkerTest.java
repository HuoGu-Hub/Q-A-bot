package com.example.qqbot.kb.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配图标记的格式验收。
 *
 * <p>marker 是提示词、{@link com.example.qqbot.media.ImageMarkers} 与出站图片三方共用的协议：
 * 格式错了不会报错，只会表现为"资料里明明有图，机器人却从来不发"。
 */
class WikiArticleImporterMarkerTest {

    @Test
    @DisplayName("第 1 块末尾追加 marker，单独一行")
    void appendsMarkerAtBlockEnd() {
        String text = WikiArticleImporter.withImageMarker("酸蚀砍刀。一把涂满酸液的砍刀。",
                "acid-cleaver/acid-cleaver.png");

        assertThat(text).endsWith("\n\n[[img:wiki:acid-cleaver/acid-cleaver.png]]");
    }

    @Test
    @DisplayName("没有图时正文一个字都不改")
    void leavesTextUntouchedWithoutImage() {
        assertThat(WikiArticleImporter.withImageMarker("正文", null)).isEqualTo("正文");
        assertThat(WikiArticleImporter.withImageMarker("正文", "  ")).isEqualTo("正文");
    }
}
