package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资料注入器的验收测试，只钉一件事：**块正文里的配图标记必须原样出现在资料段里**。
 *
 * <p>标记是"模型知道这里该配图"的唯一途径。它在中途被清掉或改写不会有任何报错，
 * 表现只是"资料里明明有图，机器人从来不发"。
 */
class TwoSectionInjectorTest {

    private final TwoSectionInjector injector = new TwoSectionInjector();

    private static KbRetriever.Hit hit(String id, String title, String text) {
        return new KbRetriever.Hit(new KbCorpus.Entry(id, id, title, text,
                "https://example.com/" + id, List.of("测试"), false, "2026-01-01", new float[] {1f}),
                0.9, "vector");
    }

    @Test
    @DisplayName("★ 标记进资料段，并被提到块尾单独一行")
    void keepsImageMarkerAtBlockEnd() {
        String rendered = injector.render(List.of(hit("acid-cleaver", "酸蚀砍刀（Acid Cleaver）",
                "一把涂满酸液的砍刀。[[img:kb:acid-cleaver]]\n获取：由拾荒者掉落。")));

        assertThat(rendered).contains("[[img:kb:acid-cleaver]]");
        assertThat(rendered).contains("获取：由拾荒者掉落。\n[[img:kb:acid-cleaver]]");
        assertThat(rendered).doesNotContain("砍刀。[[img");
    }

    @Test
    @DisplayName("没有配图标记的块：正文保持原样")
    void leavesPlainTextAlone() {
        String rendered = injector.render(List.of(hit("flame-altar-0", "灵火祭坛", "灵火祭坛是复活点。")));

        assertThat(rendered).contains("灵火祭坛是复活点。").doesNotContain("[[img:");
    }
}
