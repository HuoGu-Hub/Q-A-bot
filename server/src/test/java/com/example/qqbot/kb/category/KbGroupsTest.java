package com.example.qqbot.kb.category;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分类归属规则。
 *
 * <p>这套测试是**对着一份实测清单**写的，不是凭印象：把 604 个 Wiki 原始分类逐个过
 * {@code autoGroup} + {@code LABELS}，挑出「词典里已经有中文名、但子串规则没归类」
 * 的那批（28 个分类、463 条内容），补了 {@link KbGroups} 的精确匹配表。
 *
 * <p>之所以要锁住：子串规则对**复数和专名**天然失效 —— {@code Shelves} 不含
 * {@code shelf}，{@code Two-handed Hammers} 的 {@code hammer} 压根不在武器词表里。
 * 这类漏洞肉眼看不出来，只有把实际数据跑一遍才会暴露；没有测试就会悄悄退回去。
 */
class KbGroupsTest {

    @Test
    @DisplayName("★ 回归：这批复数/专名原先全被丢进「其他」，不能退回去")
    void exactGroupRulesHold() {
        // 建造装饰：家具与装饰（子串规则抓不到复数或专名）
        assertThat(KbGroups.autoGroup("Statues")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Shelves")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Benches")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Cupboards")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Showcases")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Banners")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Bathroom")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Storage")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Dividers")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Festive")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Flame Mosaic")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Musical Instruments")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Water Utilities")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Drak Relief")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Medium Flower Pots")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Small Flower Pots")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Long Flower Pots")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Lunar New Year Flower Pots")).isEqualTo("build");

        // 战斗装备
        assertThat(KbGroups.autoGroup("Two-handed Hammers")).isEqualTo("combat");
        assertThat(KbGroups.autoGroup("Sets")).isEqualTo("combat");

        // 地点探索
        assertThat(KbGroups.autoGroup("Cemetery")).isEqualTo("world");
        assertThat(KbGroups.autoGroup("Cyclops Statue")).isEqualTo("world");

        // 材料消耗
        assertThat(KbGroups.autoGroup("Fossils")).isEqualTo("material");
        assertThat(KbGroups.autoGroup("Artifacts")).isEqualTo("material");
        assertThat(KbGroups.autoGroup("Trophies")).isEqualTo("material");
        assertThat(KbGroups.autoGroup("Essentials")).isEqualTo("material");

        // 敌人生物 / 任务剧情
        assertThat(KbGroups.autoGroup("Assistants")).isEqualTo("creature");
        assertThat(KbGroups.autoGroup("Books")).isEqualTo("quest");
    }

    @Test
    @DisplayName("精确表只影响列进去的名字，不误伤子串规则（statue 一族）")
    void exactRulesDoNotLeak() {
        // 精确表是为了避免 "statue" 这种子串误伤：家具「雕像」进建造，
        // 地标「独眼巨人雕像」进地点探索 —— 一个子串规则没法同时满足。
        assertThat(KbGroups.autoGroup("Statues")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("Cyclops Statue")).isEqualTo("world");
        // 没列进去的名字仍然走原来的子串规则
        assertThat(KbGroups.autoGroup("Stone Statues Of Old")).isEqualTo("other");
    }

    @Test
    @DisplayName("原有的隐藏与子串规则不受影响")
    void existingRulesStillWork() {
        assertThat(KbGroups.autoGroup("Early Access Launch")).isEqualTo(KbGroups.HIDDEN);
        // 元页面（Wiki 的编辑指南/数据页/广告页），对玩家没有导航价值
        assertThat(KbGroups.autoGroup("Enshrouded Wiki")).isEqualTo(KbGroups.HIDDEN);
        assertThat(KbGroups.autoGroup("Item catalogs")).isEqualTo(KbGroups.HIDDEN);
        assertThat(KbGroups.autoGroup("Enshrouded Server Hosting Providers")).isEqualTo(KbGroups.HIDDEN);
        assertThat(KbGroups.autoGroup("One-handed Swords")).isEqualTo("combat");
        assertThat(KbGroups.autoGroup("Two-handed Axes")).isEqualTo("combat");
        assertThat(KbGroups.autoGroup("Production Places")).isEqualTo("system");
        assertThat(KbGroups.autoGroup("Wildlife")).isEqualTo("creature");
        assertThat(KbGroups.autoGroup("")).isEqualTo(KbGroups.DEFAULT_GROUP);
        assertThat(KbGroups.autoGroup(null)).isEqualTo(KbGroups.DEFAULT_GROUP);
        // 「杂项」本来就该在「其他」—— 精确表刻意没收它
        assertThat(KbGroups.autoGroup("Miscellaneous")).isEqualTo(KbGroups.DEFAULT_GROUP);
    }

    @Test
    @DisplayName("★ 中文标签也要归类（文档导入之后标签是中文的，原先整库落进「其他」）")
    void chineseTagsAreMapped() {
        assertThat(KbGroups.autoGroup("护甲")).isEqualTo("combat");
        assertThat(KbGroups.autoGroup("单手剑")).isEqualTo("combat");
        assertThat(KbGroups.autoGroup("家具")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("围栏")).isEqualTo("build");
        assertThat(KbGroups.autoGroup("制作材料")).isEqualTo("material");
        assertThat(KbGroups.autoGroup("动物")).isEqualTo("creature");
        assertThat(KbGroups.autoGroup("雾水盆地")).isEqualTo("world");
        assertThat(KbGroups.autoGroup("技能树")).isEqualTo("system");
        assertThat(KbGroups.autoGroup("攻略")).isEqualTo("guide");
    }

    @Test
    @DisplayName("★ 「物品」是内容类型标记不是分类 → 隐藏（否则 97% 的块都堆在「其他」）")
    void genericItemTagIsHidden() {
        assertThat(KbGroups.autoGroup("物品")).isEqualTo(KbGroups.HIDDEN);
        assertThat(KbGroups.autoGroup("版本更新")).isEqualTo(KbGroups.HIDDEN);
        assertThat(KbGroups.autoGroup("Bug修复")).isEqualTo(KbGroups.HIDDEN);
    }

    @Test
    @DisplayName("表里没有的中文标签，靠子串规则兜住")
    void unknownChineseTagFallsBackToSubstring() {
        assertThat(KbGroups.autoGroup("传说护甲套装")).isEqualTo("combat");
        assertThat(KbGroups.autoGroup("某种新材料")).isEqualTo("material");
        assertThat(KbGroups.autoGroup("完全没见过的东西")).isEqualTo("other");
    }
}
