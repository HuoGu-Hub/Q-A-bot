package com.example.qqbot.kb.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渲染兜底的提取验收 —— 夹具是**真实抓下来的 action=parse HTML**。
 *
 * <p>这里盯四件事：数据层的值确实取到了、导航块被丢掉、编辑入口被丢掉、字段排在正文之后。
 */
class WikiHtmlCleanerTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = WikiHtmlCleanerTest.class.getResourceAsStream("/wiki/html/" + name)) {
            assertThat(in).as("找不到夹具 " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 把提取结果倒出来，方便人眼核对 */
    private static void dump(String name, String text) throws IOException {
        Path dir = Path.of("E:/Project/agentWs/qqbot/.toolchain/render-clean");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".txt"), text, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("★ 数据表页：掉落表与信息框的值都取到了，导航与编辑入口没进来")
    void dataTablePage() throws IOException {
        String out = WikiHtmlCleaner.clean(fixture("Bat.html"));
        dump("Bat", out);

        assertThat(out).contains("Fur Patch").contains("1-3").contains("Materials");
        assertThat(out).contains("Hostile").contains("Wildlife");
        assertThat(out).doesNotContain("visual editing").doesNotContain("edit source");
        assertThat(out).as("Bestiary 导航整块丢掉").doesNotContain("Fell Militia");
        assertThat(out).doesNotContain("<").doesNotContain("&nbsp;");
    }

    @Test
    @DisplayName("★ 技能页：Class / Unlock Cost 取到，Skills 导航丢掉")
    void skillPage() throws IOException {
        String out = WikiHtmlCleaner.clean(fixture("Counter_Battery.html"));
        dump("Counter_Battery", out);

        assertThat(out).contains("Class: Ranger").contains("Unlock Cost: 2");
        assertThat(out).contains("Counter Battery is a skill in Enshrouded.");
        assertThat(out).doesNotContain("Survivor Runner");
        assertThat(out).doesNotContain("visual editing");
        // 信息框的描述与正文是同一句时不重复放
        int first = out.indexOf("+15% damage to ranged enemies.");
        assertThat(first).isGreaterThanOrEqualTo(0);
        assertThat(out.indexOf("+15% damage to ranged enemies.", first + 1)).isEqualTo(-1);
    }

    @Test
    @DisplayName("★ 字段行排在正文之后：与 wikitext 那条路保持一致，别把重排分打下去")
    void fieldsComeAfterTheProse() throws IOException {
        String out = WikiHtmlCleaner.clean(fixture("Counter_Battery.html"));
        assertThat(out.indexOf("is a skill in Enshrouded"))
                .isLessThan(out.indexOf("Class: Ranger"));
    }

    @Test
    @DisplayName("空输入不炸")
    void empty() {
        assertThat(WikiHtmlCleaner.clean(null)).isEmpty();
        assertThat(WikiHtmlCleaner.clean("   ")).isEmpty();
        assertThat(WikiHtmlCleaner.clean("<div>hi</div>")).isEqualTo("hi");
    }
}
