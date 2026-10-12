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
 *
 * <h2>2026-10-11 原则反转</h2>
 * 旧版是"白名单"：认识的模板取值，不认识的整块丢。实测丢掉三分之一的字，
 * 而且丢的是攻略内容（技能解锁条件、怪物抗性与区域、掉落表、工匠位置）。
 * 现在改成"<b>默认保留，只丢明确噪声</b>"，这组测试跟着改：原来断言
 * "不认识的模板整块丢"，现在断言"它的参数值必须进文档"。
 */
class WikitextCleanerTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = WikitextCleanerTest.class.getResourceAsStream("/wiki/" + name)) {
            assertThat(in).as("找不到夹具 " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("★ 任务页：标记清干净，前置任务与奖励物品都在，表格内容不漏")
    void questPage() throws IOException {
        String out = WikitextCleaner.clean(fixture("Quests_A_Beehive_Smoker.wiki"), "Quests/A Beehive Smoker");

        assertThat(out).doesNotContain("{{").doesNotContain("}}").doesNotContain("[[")
                .doesNotContain("{|").doesNotContain("|-").doesNotContain("'''").doesNotContain("|}");
        assertThat(out).contains("In Need Of A Tanning Station");
        assertThat(out).contains("Beehive Smoker");
        assertThat(out).contains("Investigate");
        assertThat(out).contains("Farmer");
        assertThat(out).as("图标尺寸不是内容").doesNotContain("25px");
    }

    @Test
    @DisplayName("机制页：纯正文 + 大量内链 + 表格")
    void mechanicsPage() throws IOException {
        String out = WikitextCleaner.clean(fixture("Combat_Mechanics.wiki"), "Combat Mechanics");

        assertThat(out).contains("Enemy Resistances").contains("Effective").contains("Resisted");
        assertThat(out).doesNotContain("[[").doesNotContain("]]").doesNotContain("{|");
        assertThat(out).doesNotContain("'''");
    }

    @Test
    @DisplayName("★ 怪物页：description、行为、阵营、区域、抗性全部保留（旧版只留 description）")
    void bossPage() throws IOException {
        String out = WikitextCleaner.clean(fixture("Cyclops.wiki"), "Cyclops");

        assertThat(out).contains("A Cyclops");
        assertThat(out).doesNotContain("{{").doesNotContain("NPC Infobox").doesNotContain("Cyclops.png");
        // ★ 新原则：这些原来被丢掉，现在必须留下 —— 它们就是"在哪打、怕什么"
        assertThat(out).contains("Hostile");
        assertThat(out).contains("Region");
    }

    @Test
    @DisplayName("制作页：含 HTML 标签与模板")
    void craftingPage() throws IOException {
        String out = WikitextCleaner.clean(fixture("Crafting.wiki"), "Crafting");

        assertThat(out).doesNotContain("{{").doesNotContain("[[").doesNotContain("'''");
        assertThat(out).doesNotContain("<br").doesNotContain("<ref").doesNotContain("</");
        assertThat(out.length()).isGreaterThan(200);
    }

    @Test
    @DisplayName("另一篇任务页也清得干净")
    void questPage2() throws IOException {
        String out = WikitextCleaner.clean(fixture("Quests_Alchemic_Wisdom.wiki"), "Quests/Alchemic Wisdom");

        assertThat(out).doesNotContain("{{").doesNotContain("[[").doesNotContain("'''");
        assertThat(out.length()).isGreaterThan(150);
    }

    @Test
    @DisplayName("模板配对扫描能处理嵌套（{{Quest|{{SUBPAGENAME}}}}）")
    void nestedTemplates() {
        assertThat(WikitextCleaner.matchClose("{{A|{{B}}}}", 0)).isEqualTo(9);
        // 不配对时返回 -1（调用方丢掉剩余内容，而不是留半截）
        assertThat(WikitextCleaner.matchClose("{{A|{{B}}", 0)).isEqualTo(-1);
        assertThat(WikitextCleaner.clean("x {{Quest|{{SUBPAGENAME}}}} y", "Quests/Foo")).isEqualTo("x Foo y");
    }

    @Test
    @DisplayName("★ 默认保留：不认识的模板也要把参数值留下来（旧版整块丢）")
    void unknownTemplatesKeepTheirValues() {
        // 技能页的 infobox：Class / Unlock Cost 就是"怎么解锁、要多少点"
        String skill = "{{Infobox/Skill|title=Kick|image=Kick.png|description=A kick.|Class=Ranger|Unlock Cost=3}}";
        String out = WikitextCleaner.clean(skill, "Kick");
        assertThat(out).contains("Class: Ranger").contains("Unlock Cost: 3").contains("A kick.");
        assertThat(out).as("文件名不是内容").doesNotContain("Kick.png");

        // 完全陌生的模板同样默认保留
        assertThat(WikitextCleaner.clean("{{Whatever|name=Cyclops|health=100}}", "x"))
                .contains("Name: Cyclops").contains("Health: 100");

        // 没有上下文时 {{PAGENAME}} 展开为空，但整块不会崩
        assertThat(WikitextCleaner.clean("{{SUBPAGENAME}}")).isEmpty();
    }

    @Test
    @DisplayName("★ 页面上下文：{{PAGENAME}} 必须展开，否则主语被清没")
    void pageNameIsExpanded() {
        assertThat(WikitextCleaner.clean("'''{{PAGENAME}}''' is a skill in [[Enshrouded]].", "Kick"))
                .isEqualTo("Kick is a skill in Enshrouded.");
        // 子页面：SUBPAGENAME 去掉前缀
        assertThat(WikitextCleaner.clean("{{SUBPAGENAME}} is a quest", "Quests/A Glimmer Of Hope"))
                .isEqualTo("A Glimmer Of Hope is a quest");
    }

    @Test
    @DisplayName("★ 递归：外层模板不认识，内层的表格式模板照样取值（掉落表就是这么丢的）")
    void recursionKeepsInnerTemplates() {
        String wiki = "{{ItemsTableHeader\n|content=\n"
                + "{{ItemsLine|image=Fell Cyclops Head.png|item=Fell Cyclops Head|quantity=1|type=Materials|source=Drop}}\n"
                + "{{ItemsLine|image=Cyclops Eye.png|item=Enshrouded Cyclops Eye|quantity=2|type=Materials|source=Drop}}\n"
                + "}}";
        String out = WikitextCleaner.clean(wiki, "Fell Cyclops");

        assertThat(out).contains("Fell Cyclops Head × 1 (Materials, Drop)");
        assertThat(out).contains("Enshrouded Cyclops Eye × 2 (Materials, Drop)");
        assertThat(out).doesNotContain("File:").doesNotContain(".png");
    }

    @Test
    @DisplayName("★ MapLink 这类特殊形状：取给人看的名字，不取 marker id")
    void knownShapes() {
        assertThat(WikitextCleaner.resolveTemplate(
                "MapLink | icon= Camp | FarAwayFrayTavern | \"Far Away Fray\" Tavern | size= 25px"))
                .isEqualTo("Far Away Fray Tavern");
        // 没有引号时：排除 marker id（字母+数字）与噪声，取最后一个像名字的
        assertThat(WikitextCleaner.resolveTemplate("MapLink | ElixirWell15 | Elixir Well"))
                .isEqualTo("Elixir Well");
        // 表格式模板 → 一行一条
        assertThat(WikitextCleaner.resolveTemplate("ItemsLine|item=Fur Patch|quantity=1-3|type=Materials|source=Drop"))
                .isEqualTo("Fur Patch × 1-3 (Materials, Drop)");
    }

    @Test
    @DisplayName("★ 噪声模板整块丢：导航、图标条没有一句是这一页的正文")
    void noiseTemplatesAreDropped() {
        assertThat(WikitextCleaner.clean("正文。\n{{Bestiary}}\n", "x")).isEqualTo("正文。");
        assertThat(WikitextCleaner.clean("正文。\n{{Skills}}\n", "x")).isEqualTo("正文。");
        // 但 update needed 这类"待核实"提示要留下 —— 它影响回答的可信度
        assertThat(WikitextCleaner.clean("{{Update Needed|update=Wake of Water}}", "x")).isNotEmpty();
    }

    @Test
    @DisplayName("★ 长文本不重复：infobox 的描述与正文是同一段时，只留一份")
    void longTextIsNotDuplicated() {
        String desc = "When equipped with a Melee weapon you can perform a Kick by attacking while blocking. "
                + "Kick deals little damage but hits the target with massive force.";
        String wiki = "{{Infobox/Skill|description=" + desc + "|Class=Ranger}}\n\n"
                + "== Description ==\n" + desc + "\n";
        String out = WikitextCleaner.clean(wiki, "Kick");

        int first = out.indexOf("When equipped with a Melee weapon");
        assertThat(first).as("这段描述必须在文档里").isGreaterThanOrEqualTo(0);
        assertThat(out.indexOf("When equipped with a Melee weapon", first + 1))
                .as("同一段描述只许出现一次，重复会让两个块互相稀释").isEqualTo(-1);
        assertThat(out).contains("Class: Ranger");
    }

    @Test
    @DisplayName("★ gallery：取标签拼成清单，不留文件名与 link= 噪声")
    void galleryBecomesLabelList() {
        String wiki = "<gallery widths=100px>\n"
                + "File:Iron Door.png|link=Decorations/Doors|[[Decorations/Doors|Doors]]\n"
                + "File:Iron Fence.png|link=Decorations/Fences|[[Decorations/Fences|Fences]]\n"
                + "Preset 1.png|Preset 1\n"
                + "File:Orphan.png\n"
                + "</gallery>\n";
        String out = WikitextCleaner.clean(wiki, "Decorations");

        assertThat(out).contains("Doors").contains("Fences");
        assertThat(out).as("没有 File: 前缀的标签行也要取到").contains("Preset 1");
        assertThat(out).doesNotContain("File:").doesNotContain(".png").doesNotContain("link=")
                .doesNotContain("100px").doesNotContain("Orphan");
    }

    @Test
    @DisplayName("链接：带显示名取显示名，文件/分类/interwiki 链接整条丢")
    void links() {
        assertThat(WikitextCleaner.stripLinks("见 [[Enshrouded]] 与 [[Farmer|农夫]]")).isEqualTo("见 Enshrouded 与 农夫");
        assertThat(WikitextCleaner.stripLinks("x [[File:a.png|thumb|说明]] y")).isEqualTo("x  y");
        assertThat(WikitextCleaner.stripLinks("x [[Category:Quests]] y")).isEqualTo("x  y");
        assertThat(WikitextCleaner.stripLinks("x [[C:Two-handed Greatswords]] y")).isEqualTo("x  y");
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
    @DisplayName("★ 表格一行一条记录，而不是一格一行")
    void tableRowStaysOneLine() {
        String wiki = "{| class=\"wikitable\"\n! Region !! Location\n|-\n"
                + "| [[Albaneve Summits]] || Elixir Well\n"
                + "|}";
        String out = WikitextCleaner.clean(wiki, "x");

        assertThat(out.lines().toList()).as("表头一行、数据一行").hasSize(2);
        assertThat(out).contains("Region | Location");
        assertThat(out).contains("Albaneve Summits | Elixir Well");
    }

    @Test
    @DisplayName("★ 表格：单元格里 <br> 之后的内容、以及表格内的散行都不能丢")
    void tableKeepsEverything() {
        String wiki = "{| class=\"wikitable\"\n|-\n"
                + "| [[File:Knight Chestplate.png|100px|link=Armor/Melee]]<br>[[Armor/Melee|Melee Armor]]\n"
                + "| [[File:Mystic Chest.png|100px|link=Armor/Magic]]<br>[[Armor/Magic|Magic Armor]]\n"
                + "|}\n";
        String out = WikitextCleaner.clean(wiki, "Equipment");
        assertThat(out).isNotEmpty();
        assertThat(out).contains("Melee Armor").contains("Magic Armor");
        assertThat(out).doesNotContain("File:").doesNotContain("Chestplate.png");
        // 单元格里的**真实换行**（不是 <br>）也要留住 —— 靠 stripTables 的兜底分支
        assertThat(WikitextCleaner.clean("{|\n| 第一行\n第二行\n|}", "x"))
                .contains("第一行").contains("第二行");
    }

    @Test
    @DisplayName("★ HTML 实体要解码：&nbsp; 原样进语料就是噪声（实测 Survivors 单页 118 处）")
    void entitiesAreDecoded() {
        assertThat(WikitextCleaner.clean("A&nbsp;B &amp; C &lt;tag&gt; &#65; &#x42;", "x"))
                .isEqualTo("A B & C <tag> A B");
        // 不换行空格也是空格
        assertThat(WikitextCleaner.clean("&nbsp;&nbsp;Charcoal Kiln", "x")).isEqualTo("Charcoal Kiln");
    }

    @Test
    @DisplayName("★ 字段行必须排在正文之后：重排模型看重开头，元数据挡在正文前面会把它打下去")
    void fieldLinesComeAfterTheProse() {
        String wiki = "{{Infobox/Skill|Class=Mage|Unlock Cost=3|Behavior=Hostile}}\n"
                + "'''{{PAGENAME}}''' is a skill in [[Enshrouded]].\n"
                + "All Shrouded damage is increased.\n";
        String out = WikitextCleaner.clean(wiki, "Dark Arts");

        assertThat(out).contains("Dark Arts is a skill in Enshrouded.");
        assertThat(out).contains("Class: Mage").contains("Unlock Cost: 3").contains("Behavior: Hostile");
        assertThat(out.indexOf("Dark Arts is a skill"))
                .as("正文要排在字段前面")
                .isLessThan(out.indexOf("Class: Mage"));
    }

    @Test
    @DisplayName("★ 长字段值也要带键名并排到末尾，不能孤零零留在开头")
    void longFieldKeepsItsKey() {
        String wiki = "{{NPC Infobox|Region=Springlands, Revelwood, Nomad Highlands, Kindlewastes, Albaneve Summits|Behavior=Hostile}}\n"
                + "The bat is a creature.\n";
        String out = WikitextCleaner.clean(wiki, "Bat");

        assertThat(out).contains("Region: Springlands, Revelwood, Nomad Highlands");
        assertThat(out).contains("Behavior: Hostile");
        assertThat(out.indexOf("The bat is a creature")).isLessThan(out.indexOf("Region:"));
    }

    @Test
    @DisplayName("★ 短的重复描述也要去重：32 字的描述同样会稀释打分")
    void shortDuplicateIsDropped() {
        String desc = "+15% damage to ranged enemies.";
        String wiki = "{{Infobox/Skill|description=" + desc + "|Class=Ranger}}\n"
                + "== Description ==\n" + desc + "\n";
        String out = WikitextCleaner.clean(wiki, "Counter Battery");

        int first = out.indexOf(desc);
        assertThat(first).isGreaterThanOrEqualTo(0);
        assertThat(out.indexOf(desc, first + 1)).as("同一句只许出现一次").isEqualTo(-1);
        assertThat(out).contains("Class: Ranger");
    }

    @Test
    @DisplayName("空输入不炸")
    void empty() {
        assertThat(WikitextCleaner.clean(null)).isEmpty();
        assertThat(WikitextCleaner.clean("   ")).isEmpty();
    }
}
