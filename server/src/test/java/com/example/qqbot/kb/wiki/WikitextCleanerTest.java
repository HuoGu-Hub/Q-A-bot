package com.example.qqbot.kb.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 清洗器验收 —— 用**真实抓下来的 wiki 原文**做夹具
 * （{@code server/src/test/resources/wiki/}，5 篇覆盖：任务页 / 机制页 / 怪物页 / 制作页）。
 *
 * <p>不联网：清洗是纯函数。
 */
class WikitextCleanerTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = WikitextCleanerTest.class.getResourceAsStream("/wiki/" + name)) {
            assertThat(in).as("找不到夹具 " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("★ 任务页：模板取出有用的参数，表格保留内容，标记清干净")
    void questPage() throws IOException {
        String out = WikitextCleaner.clean(fixture("Quests_A_Beehive_Smoker.wiki"));

        // 标记必须清干净 —— 留着这些标记等于把噪声喂给向量模型
        assertThat(out).doesNotContain("{{").doesNotContain("}}").doesNotContain("[[")
                .doesNotContain("{|").doesNotContain("|-").doesNotContain("'''").doesNotContain("|}");
        // 白名单模板的参数被保留下来（前置任务 / 奖励物品）
        assertThat(out).contains("In Need Of A Tanning Station");
        assertThat(out).contains("Beehive Smoker");
        // 表格里的正文保留（否则任务的目标与描述全丢了）
        assertThat(out).contains("Investigate");
        assertThat(out).contains("Farmer");
        // 正文里不该出现模板参数里的噪声（图标名、尺寸）
        assertThat(out).doesNotContain("25px");
    }

    @Test
    @DisplayName("机制页：纯正文 + 大量内链 + 表格")
    void mechanicsPage() throws IOException {
        String out = WikitextCleaner.clean(fixture("Combat_Mechanics.wiki"));
        assertThat(out).contains("Enemy Resistances").contains("Effective").contains("Resisted");
        assertThat(out).doesNotContain("[[").doesNotContain("]]").doesNotContain("{|");
        assertThat(out).doesNotContain("'''");
    }

    @Test
    @DisplayName("怪物页：Infobox 的 description 取出来，其它参数与标记丢掉")
    void bossPage() throws IOException {
        String out = WikitextCleaner.clean(fixture("Cyclops.wiki"));
        assertThat(out).contains("Cyclops");
        assertThat(out).doesNotContain("{{").doesNotContain("NPC Infobox");
        // ★ 2026-10-05：{{NPC Infobox}} 的 description 现在会被取出（原来整块丢，
        //    9 个 Baby * 页 + Bees 因此清洗成 0 字）
        assertThat(out).contains("A Cyclops");
        // 参数里的噪声不许混进来
        assertThat(out).doesNotContain("Cyclops.png").doesNotContain("Hostile");
    }

    @Test
    @DisplayName("制作页：含 HTML 标签与模板")
    void craftingPage() throws IOException {
        String out = WikitextCleaner.clean(fixture("Crafting.wiki"));
        assertThat(out).doesNotContain("{{").doesNotContain("<").doesNotContain("[[").doesNotContain("'''");
        assertThat(out.length()).isGreaterThan(200);
    }

    @Test
    @DisplayName("另一篇任务页也清得干净")
    void questPage2() throws IOException {
        String out = WikitextCleaner.clean(fixture("Quests_Alchemic_Wisdom.wiki"));
        assertThat(out).doesNotContain("{{").doesNotContain("[[").doesNotContain("'''");
        assertThat(out.length()).isGreaterThan(150);
    }

    @Test
    @DisplayName("模板配对扫描能处理嵌套（{{Quest|{{SUBPAGENAME}}}}）")
    void nestedTemplates() {
        // {{A|{{B}}}} 的下标是 0..10，外层 }} 落在 9
        assertThat(WikitextCleaner.matchClose("{{A|{{B}}}}", 0)).isEqualTo(9);
        // 不配对时返回 -1（调用方选择整段丢掉，而不是留半截）
        assertThat(WikitextCleaner.matchClose("{{A|{{B}}", 0)).isEqualTo(-1);
        assertThat(WikitextCleaner.clean("x {{Quest|{{SUBPAGENAME}}}} y")).isEqualTo("x y");
    }

    @Test
    @DisplayName("白名单模板取对参数；不认识的模板整块丢掉（宁可少留，不要留错）")
    void templateWhitelist() {
        assertThat(WikitextCleaner.resolveTemplate("quest+icon|In Need Of A Tanning Station"))
                .isEqualTo("In Need Of A Tanning Station");
        assertThat(WikitextCleaner.resolveTemplate("item+iconright|Beehive Smoker"))
                .isEqualTo("Beehive Smoker");
        // MapLink：位置参数是 [图标名, markerId, 显示名] —— 要的是显示名，不是 markerId
        assertThat(WikitextCleaner.resolveTemplate(
                "MapLink | icon= Camp | FarAwayFrayTavern | \"Far Away Fray\" Tavern | size= 25px"))
                .isEqualTo("Far Away Fray Tavern");
        // 不认识的模板一律丢
        assertThat(WikitextCleaner.resolveTemplate("NPC Infobox|name=Cyclops|health=100")).isEmpty();
        assertThat(WikitextCleaner.resolveTemplate("SUBPAGENAME")).isEmpty();
    }

    @Test
    @DisplayName("链接：带显示名取显示名，文件/分类链接整条丢")
    void links() {
        assertThat(WikitextCleaner.stripLinks("见 [[Enshrouded]] 与 [[Farmer|农夫]]")).isEqualTo("见 Enshrouded 与 农夫");
        assertThat(WikitextCleaner.stripLinks("x [[File:a.png|thumb|说明]] y")).isEqualTo("x  y");
        assertThat(WikitextCleaner.stripLinks("x [[Category:Quests]] y")).isEqualTo("x  y");
    }

    @Test
    @DisplayName("顶层切分忽略嵌套里的竖线")
    void splitTopLevel() {
        List<String> a = WikitextCleaner.splitTopLevel("MapLink | icon= Camp | id | \"Label\" | size= 25px");
        assertThat(a).hasSize(5);
        assertThat(a.get(0).strip()).isEqualTo("MapLink");
        List<String> b = WikitextCleaner.splitTopLevel("Quest|{{a|b}}|c");
        assertThat(b).hasSize(3);
    }

    @Test
    @DisplayName("★ NPC Infobox：取 description；没有 description 仍然整块丢")
    void npcInfobox() {
        // 实测 Baby Capybara / Bees 这类页面的正文**只有**这一个模板
        String wiki = "{{NPC Infobox\n| images = Baby Capybara.png\n"
                + "| description = A tiny Capybara. It enjoys headpats.\n"
                + "| Behavior = Fleeting\n| Tameable = No\n}}";
        assertThat(WikitextCleaner.clean(wiki)).isEqualTo("A tiny Capybara. It enjoys headpats.");
        // 缺 description → 整块丢（宁可少留，不要留错）
        assertThat(WikitextCleaner.clean("{{NPC Infobox|name=Cyclops|health=100}}")).isEmpty();
        // 单词类参数不许混进来（它们只会灌噪声）
        assertThat(WikitextCleaner.clean(wiki))
                .doesNotContain("Baby Capybara.png").doesNotContain("Fleeting").doesNotContain("No");
    }

    @Test
    @DisplayName("★ 表格：单元格里 <br> 之后的内容、以及表格内的散行都不能丢")
    void tableKeepsEverything() {
        // 实测形态：Equipment 整页就是一个"图片链接表格" —— 原来会清成 0 字
        String wiki = "{| class=\"wikitable\"\n|-\n"
                + "| [[File:Knight Chestplate.png|100px|link=Armor/Melee]]<br>[[Armor/Melee|Melee Armor]]\n"
                + "| [[File:Mystic Chest.png|100px|link=Armor/Magic]]<br>[[Armor/Magic|Magic Armor]]\n"
                + "|}\n";
        String out = WikitextCleaner.clean(wiki);
        assertThat(out).isNotEmpty();
        assertThat(out).contains("Melee Armor").contains("Magic Armor");
        assertThat(out).doesNotContain("File:").doesNotContain("Chestplate.png");
        // 单元格里的**真实换行**（不是 <br>）也要留住 —— 靠 stripTables 的兜底分支
        assertThat(WikitextCleaner.clean("{|\n| 第一行\n第二行\n|}"))
                .contains("第一行").contains("第二行");
    }

    @Test
    @DisplayName("空输入不炸")
    void empty() {
        assertThat(WikitextCleaner.clean(null)).isEmpty();
        assertThat(WikitextCleaner.clean("   ")).isEmpty();
    }
}
