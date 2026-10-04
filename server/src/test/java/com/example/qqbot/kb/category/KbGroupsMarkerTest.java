package com.example.qqbot.kb.category;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容类型标记不能被当成分类 —— 它们会**劫持**板块判定。
 *
 * <h2>真实故障（2026-10-04 实测）</h2>
 * wiki 自动导入的块，tags 是 <b>[来源标记, 类型, 真实分类]</b>，形如
 * {@code wiki,category,Mage Skills}。而 {@code KbTermService.boardOf} 取的是
 * <b>第一个非 HIDDEN</b> 的判定结果 —— 于是 {@code autoGroup("wiki")} 返回 {@code other}
 * 直接把板块定死，**第三个标签（真正的分类）永远轮不到**。
 *
 * <p>后果：403 + 364 = 767 个导入块全被丢进「其他」，占该桶的 **93%**。
 * 表现是「按板块分批导出」这件事做不了 —— 只有一个 605 条的大批。
 *
 * <p>修法：{@code wiki / category / prefix} 和已有的「物品」一样归 HIDDEN，
 * {@code boardOf} 会跳过 HIDDEN 继续看下一个标签。
 */
class KbGroupsMarkerTest {

    @Test
    @DisplayName("★ 内容类型标记必须隐藏，否则会劫持真分类（boardOf 取第一个非 HIDDEN）")
    void contentTypeMarkersAreHidden() {
        for (String marker : new String[]{"wiki", "category", "prefix", "物品"}) {
            assertThat(KbGroups.autoGroup(marker))
                    .as("「%s」是内容类型标记，必须 HIDDEN —— 否则真分类永远轮不到", marker)
                    .isEqualTo(KbGroups.HIDDEN);
        }
    }

    @Test
    @DisplayName("★ 真分类要能判对（含子串陷阱 Lore→quest，不是 material）")
    void realCategoriesMapCorrectly() {
        assertThat(KbGroups.autoGroup("Mage Skills")).isEqualTo("system");
        assertThat(KbGroups.autoGroup("Ranger Skills")).isEqualTo("system");
        assertThat(KbGroups.autoGroup("Warrior Skills")).isEqualTo("system");
        assertThat(KbGroups.autoGroup("Gameplay")).isEqualTo("system");
        assertThat(KbGroups.autoGroup("Bosses")).isEqualTo("creature");
        assertThat(KbGroups.autoGroup("Wildlife")).isEqualTo("creature");
        assertThat(KbGroups.autoGroup("Quests/")).isEqualTo("quest");
        assertThat(KbGroups.autoGroup("Armor Set")).isEqualTo("combat");
        // ⚠️ 「Lore」里含子串「ore」，子串规则会把它判成材料 —— 精确表兜住
        assertThat(KbGroups.autoGroup("Lore"))
                .as("Lore 是收集品笔记/背景故事，归任务剧情；不能被子串 'ore' 抢走")
                .isEqualTo("quest");
    }
}
