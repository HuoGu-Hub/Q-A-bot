package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 展示层文本清洗的验收：**配图标记不能出现在人看的地方**。
 *
 * <p>标记进知识库块正文是为了让模型发图；公开站的摘要与详情页直接展示块正文，
 * 不过这一道就会把 {@code [[img:kb:xxx]]} 原样给人看。
 */
class KbTextDisplayTest {

    private static final String WITH_MARKER = """
            一把涂满酸液与胆汁的砍刀，被它砍中的人能止住流血。
            获取：由拾荒者狂战士掉落。
            [[img:kb:acid-cleaver]]""";

    @Test
    @DisplayName("★ 详情页全文里没有标记，其他文字一个字不少")
    void fullStripsMarker() {
        String full = KbTextDisplay.full(WITH_MARKER);

        assertThat(full).doesNotContain("[[img:").doesNotContain("kb:acid-cleaver");
        assertThat(full).contains("一把涂满酸液与胆汁的砍刀").contains("由拾荒者狂战士掉落");
    }

    @Test
    @DisplayName("★ 列表摘要里同样没有标记")
    void excerptStripsMarker() {
        String excerpt = KbTextDisplay.excerpt(WITH_MARKER, 170);

        assertThat(excerpt).doesNotContain("[[img:");
    }
}
