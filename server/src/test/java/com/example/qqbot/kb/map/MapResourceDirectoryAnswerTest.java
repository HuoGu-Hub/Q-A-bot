package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.PlaceDirectory;
import com.example.qqbot.kb.ResourceDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link MapResourceDirectory#answer} 的接线验收：目录 → 点位 → 热点 → 文字。 */
class MapResourceDirectoryAnswerTest {

    private static MapResourceDirectory directory(List<MapResourceService.PointView> points) {
        MapResourceService.LayerView layer = new MapResourceService.LayerView(
                "ore:copper", "铜", "ore", "copper", points.size(), "#c97b3c", "point");
        MapResourceService.CategoryView category = new MapResourceService.CategoryView(
                "ore", "采矿", points.size(), List.of(layer));
        MapResourceService.CatalogView catalog = new MapResourceService.CatalogView(
                true, "1.3.18", "2026-10-30", "", List.of(category));

        MapResourceService service = mock(MapResourceService.class);
        when(service.catalog("Embervale")).thenReturn(catalog);
        when(service.points("Embervale", "ore:copper")).thenReturn(points);

        MapScope scope = mock(MapScope.class);
        when(scope.activeMaps()).thenReturn(Set.of("Embervale"));
        MapOptions options = mock(MapOptions.class);
        when(options.isModEnabled()).thenReturn(true);
        PlaceDirectory places = mock(PlaceDirectory.class);
        when(places.isAvailable()).thenReturn(false);

        return new MapResourceDirectory(service, places, mock(KbMapStore.class), scope, options);
    }

    @Test
    @DisplayName("★ 「哪里有密集的铜矿」→ 命中铜层，文字里有总数、热点与坐标")
    void answersDenseCopper() {
        List<MapResourceService.PointView> points = List.of(
                new MapResourceService.PointView(100, 100, "铜", 1, "Nomad Highlands"),
                new MapResourceService.PointView(110, 100, "铜", 1, "Nomad Highlands"),
                new MapResourceService.PointView(120, 100, "铜", 1, "Nomad Highlands"),
                new MapResourceService.PointView(105, 115, "铜", 1, "Nomad Highlands"),
                new MapResourceService.PointView(115, 115, "铜", 1, "Nomad Highlands"));

        ResourceDirectory.Answer answer = directory(points).answer("哪里有密集的铜矿");

        assertThat(answer).isNotNull();
        assertThat(answer.term()).isEqualTo("铜矿");
        assertThat(answer.total()).isEqualTo(5);
        assertThat(answer.hotspots()).hasSize(1);
        assertThat(answer.hotspots().get(0).count()).isEqualTo(5);
        assertThat(answer.text())
                .contains("「铜矿」在地图上有 5 个点位")
                .contains("半径 250 内 5 个点")
                .contains("在游牧高地")
                .contains("坐标 (110, 106)");
    }

    @Test
    @DisplayName("问物品不命中资源层，直接返回 null，交给地点/资料链路")
    void itemQuestionDoesNotMatch() {
        assertThat(directory(List.of(new MapResourceService.PointView(1, 1, "铜", 1, "Springlands")))
                .answer("青铜锭怎么获得")).isNull();
    }

    @Test
    @DisplayName("图层在但一个点都没有：返回 null，不编造分布")
    void emptyLayerReturnsNull() {
        assertThat(directory(List.of()).answer("铜矿在哪")).isNull();
    }
}
