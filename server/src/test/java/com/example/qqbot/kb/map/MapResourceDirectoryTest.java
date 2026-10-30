package com.example.qqbot.kb.map;

import com.example.qqbot.kb.ResourceDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 资源层别名的验收：中文「某矿」能命中，单字与颜色前缀不能误伤。 */
class MapResourceDirectoryTest {

    private static ResourceDirectory.Layer copper() {
        return new ResourceDirectory.Layer("Embervale", "ore:copper", "铜", "ore", "copper",
                747, "#c97b3c");
    }

    private static ResourceDirectory.Layer iron() {
        return new ResourceDirectory.Layer("Embervale", "ore:iron", "铁", "ore", "iron",
                1398, "#8a4b32");
    }

    private static ResourceDirectory.Layer coal() {
        return new ResourceDirectory.Layer("Embervale", "ore:coal", "煤炭", "ore", "coal",
                681, "#2f2f33");
    }

    @Test
    @DisplayName("单字「铜」不单独当别名，避免「青铜锭」误命中")
    void singleCharIsNotAlias() {
        assertThat(MapResourceDirectory.aliasesFor(copper()))
                .contains("铜矿", "铜矿石", "铜矿脉", "copper", "copper ore")
                .doesNotContain("铜");
    }

    @Test
    @DisplayName("「哪里有密集的铜矿」命中铜层")
    void matchesDenseCopper() {
        assertThat(MapResourceDirectory.match("哪里有密集的铜矿", List.of(copper(), iron())))
                .isEqualTo(copper());
    }

    @Test
    @DisplayName("「铜矿石」按最长别名仍然命中铜层")
    void matchesLongestAlias() {
        assertThat(MapResourceDirectory.match("铜矿石在哪", List.of(copper())))
                .isEqualTo(copper());
    }

    @Test
    @DisplayName("模型只传单字「铜」时，整词等于层名也认；「青铜」不算")
    void matchesExactLayerName() {
        assertThat(MapResourceDirectory.match("铜", List.of(copper(), iron()))).isEqualTo(copper());
        assertThat(MapResourceDirectory.match("青铜", List.of(copper()))).isNull();
    }

    @Test
    @DisplayName("「铁矿在哪」命中铁层")
    void matchesIron() {
        assertThat(MapResourceDirectory.match("铁矿在哪", List.of(copper(), iron(), coal())))
                .isEqualTo(iron());
    }

    @Test
    @DisplayName("「煤矿在哪」命中煤炭层")
    void matchesCoal() {
        assertThat(MapResourceDirectory.match("煤矿在哪", List.of(copper(), coal())))
                .isEqualTo(coal());
    }

    @Test
    @DisplayName("英文按整词匹配：copper ore 命中，copperhead 不命中")
    void englishNeedsWordBoundary() {
        assertThat(MapResourceDirectory.match("where is copper ore", List.of(copper())))
                .isEqualTo(copper());
        assertThat(MapResourceDirectory.match("copperhead", List.of(copper()))).isNull();
        assertThat(MapResourceDirectory.match("environment", List.of(iron()))).isNull();
    }

    @Test
    @DisplayName("问青铜、问地点都不该命中资源层")
    void doesNotMatchItemsOrPlaces() {
        assertThat(MapResourceDirectory.match("青铜锭怎么获得", List.of(copper(), iron()))).isNull();
        assertThat(MapResourceDirectory.match("远古尖塔在哪", List.of(copper(), iron()))).isNull();
        assertThat(MapResourceDirectory.match("在哪里", List.of(copper()))).isNull();
    }
}
