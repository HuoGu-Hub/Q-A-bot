package com.example.qqbot.kb.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 派生逻辑验收 —— 全部是**纯函数**，不联网、不碰数据库。
 *
 * <p>这里锁住的是几条"踩过才知道"的规则：Lore 要排除、锚点要剥、坏 article 要丢、
 * 区域中文名只能来自核对过的那张表。
 */
class LocationCorpusBuilderTest {

    private static KbMapStore.Marker marker(String article, String group, String description, double x) {
        return new KbMapStore.Marker("Embervale", "Map:Embervale/Main", group, "id-" + x,
                "name", description, article, x, 100, null);
    }

    @Test
    @DisplayName("词条归一化：Lore 排除、锚点剥掉、坏值丢掉")
    void normalizeArticle() {
        assertThat(LocationCorpusBuilder.normalizeArticle("Flame Shrine")).isEqualTo("Flame Shrine");
        assertThat(LocationCorpusBuilder.normalizeArticle("Collectibles#Fossils")).isEqualTo("Collectibles");
        assertThat(LocationCorpusBuilder.normalizeArticle(":c:Vukah")).isNull();
        assertThat(LocationCorpusBuilder.normalizeArticle("Lore/Dark Rites")).isNull();
        assertThat(LocationCorpusBuilder.normalizeArticle("  ")).isNull();
        assertThat(LocationCorpusBuilder.normalizeArticle(null)).isNull();
    }

    @Test
    @DisplayName("分类：区域 / NPC / POI（>=5 个 marker）/ 具名地点")
    void classify() {
        assertThat(LocationCorpusBuilder.classify("Springlands", 38)).isEqualTo("region");
        assertThat(LocationCorpusBuilder.classify("Blacksmith", 1)).isEqualTo("npc");
        assertThat(LocationCorpusBuilder.classify("Flame Shrine", 111)).isEqualTo("poi");
        assertThat(LocationCorpusBuilder.classify("Flame Shrine", 5)).isEqualTo("poi");
        assertThat(LocationCorpusBuilder.classify("Wolf Cave", 2)).isEqualTo("place");
    }

    @Test
    @DisplayName("区域判定：Village 的 article 就是区域；Lore 组后缀是区域；描述里的 in the X 兜底")
    void regionOf() {
        // 信号一：article 本身是区域
        assertThat(LocationCorpusBuilder.regionOf(marker("Springlands", "Village", "", 1)))
                .isEqualTo("春之原野");
        // 信号二：Lore 组后缀（实测 707/1703 个 marker 走这条）
        assertThat(LocationCorpusBuilder.regionOf(marker("Lore/xxx", "LoreVeilwaterBasin", "", 1)))
                .isEqualTo("雾水盆地");
        assertThat(LocationCorpusBuilder.regionOf(marker("Lore/xxx", "LoreAlbaneveSummits", "", 1)))
                .isEqualTo("阿尔巴内夫峰");
        // 信号三：描述里的 in the <Region>
        assertThat(LocationCorpusBuilder.regionOf(marker("Ancient Spire", "Ancient_Spire",
                "The Ancient Spire in the Springlands.", 1))).isEqualTo("春之原野");
        assertThat(LocationCorpusBuilder.regionOf(marker("Sun Temple", "Sun_Temple",
                "A Sun Temple in the Kindlewastes, inhabited by [[Scavengers]].", 1)))
                .isEqualTo("燃烬荒原");
        // 拿不到就不猜
        assertThat(LocationCorpusBuilder.regionOf(marker("Shroud Root", "Shroud_Root",
                "A Shroud Root.", 1))).isNull();
    }

    @Test
    @DisplayName("★ 聚合：一个词条一个块，区域用中文，正文带处数与区域")
    void planAggregatesByArticle() {
        List<KbMapStore.Marker> markers = List.of(
                marker("Springlands", "Village", "A village in the Springlands.", 1),
                marker("Springlands", "Village", "Another one.", 2),
                marker("Lore/Dark Rites", "LoreRevelwood", "lore text", 3));

        List<LocationCorpusBuilder.Entry> entries = LocationCorpusBuilder.plan(markers);

        // Lore 被排除，只剩 Springlands 一条
        assertThat(entries).hasSize(1);
        LocationCorpusBuilder.Entry e = entries.get(0);
        assertThat(e.kind()).isEqualTo("region");
        assertThat(e.title()).isEqualTo("春之原野（Springlands）");
        assertThat(e.body()).contains("共 2 处").contains("春之原野");
        assertThat(e.url()).isEqualTo("https://enshrouded.wiki.gg/wiki/Springlands");
        assertThat(e.tags()).contains("地图", "区域", "Village");
        // id 确定性 —— 重跑幂等的前提
        assertThat(e.id()).isEqualTo(LocationCorpusBuilder.idOf("Springlands"));
    }

    @Test
    @DisplayName("id 是确定性 slug（重跑不会造出新块）")
    void deterministicId() {
        assertThat(LocationCorpusBuilder.idOf("Flame Shrine")).isEqualTo("map-loc-flame-shrine");
        assertThat(LocationCorpusBuilder.idOf("Nomad Highlands")).isEqualTo("map-loc-nomad-highlands");
        assertThat(LocationCorpusBuilder.idOf("'Far Away Fray' Tavern")).isEqualTo("map-loc-far-away-fray-tavern");
        // 同一个词条两次算出来必须一样
        assertThat(LocationCorpusBuilder.idOf("Wolf Cave")).isEqualTo(LocationCorpusBuilder.idOf("Wolf Cave"));
    }

    @Test
    @DisplayName("描述里的 wiki 内链标记被清掉（保留可读文本）")
    void cleanDescription() {
        assertThat(LocationCorpusBuilder.cleanDescription("Guarded by a [[Fell Thunderbrute]]."))
                .isEqualTo("Guarded by a Fell Thunderbrute.");
    }

    @Test
    @DisplayName("Lore 被刻意排除 —— 数量会把真正的 POI 挤下去")
    void loreExcluded() {
        List<KbMapStore.Marker> markers = List.of(
                marker("Lore/A Knock At Night", "LoreRevelwood", "x", 1),
                marker("Lore/Dark Rites", "LoreRevelwood", "y", 2),
                marker("Wolf Cave", "Cave_Passage", "A cave.", 3));
        List<LocationCorpusBuilder.Entry> entries = LocationCorpusBuilder.plan(markers);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).title()).isEqualTo("Wolf Cave");
        assertThat(LocationCorpusBuilder.countSkippedLore(markers)).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 核对过的中文名进标题（英文标题会被物品块压过去）")
    void verifiedChineseNames() {
        // 铁匠：语料『制作：铁匠』102 次 —— 实测不带中文名时「铁匠在哪」top-5 全是铁匠长袍/帽/裤
        assertThat(LocationCorpusBuilder.plan(List.of(
                marker("Blacksmith", "Ancient_Vault", "A Blacksmith in the Ancient Vault.", 1)))
                .get(0).title()).isEqualTo("铁匠（Blacksmith）");
        // 火焰神龛：语料『遍布余烬谷的火焰神龛与火焰圣所』
        assertThat(LocationCorpusBuilder.plan(List.of(
                marker("Flame Shrine", "Flame_Shrine", "A Flame Sanctum in the Springlands.", 1)))
                .get(0).title()).isEqualTo("火焰神龛（Flame Shrine）");
        // 没核对到的**留英文**，不编
        assertThat(LocationCorpusBuilder.plan(List.of(
                marker("Sun Temple", "Sun_Temple", "A Sun Temple in the Kindlewastes.", 1)))
                .get(0).title()).isEqualTo("Sun Temple");
    }

    @Test
    @DisplayName("NPC 单列一类（实测他们在 Ancient_Vault 分组里）")
    void npcKind() {
        List<LocationCorpusBuilder.Entry> entries = LocationCorpusBuilder.plan(List.of(
                marker("Blacksmith", "Ancient_Vault", "A Blacksmith in the Ancient Vault.", 1),
                marker("Alchemist", "Ancient_Vault", "An Alchemist.", 2)));
        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(e -> assertThat(e.kind()).isEqualTo("npc"));
        assertThat(entries).allSatisfy(e -> assertThat(e.tags()).contains("NPC"));
    }
}
