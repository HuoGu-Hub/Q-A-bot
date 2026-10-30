package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** locate 的资源层兜底：地点没命中时给"哪一片最密"，地点命中时不被资源层抢走。 */
class KbLookupToolResourceTest {

    private static PlaceDirectory places(PlacePoint... points) {
        List<PlacePoint> all = List.of(points);
        return new PlaceDirectory() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<PlacePoint> all() {
                return all;
            }

            @Override
            public List<PlacePoint> find(String term, int limit) {
                return all;
            }

            @Override
            public Map<String, double[]> worldSizes() {
                return Map.of("Embervale", new double[]{10240, 10240});
            }
        };
    }

    private static KbLookupTool tool(PlaceDirectory places, ResourceDirectory resources) {
        return new KbLookupTool(mock(KbRetriever.class), mock(KbCorpus.class), places,
                new MapOptions(), resources);
    }

    private static ResourceDirectory.Answer copper() {
        ResourceDirectory.Layer layer = new ResourceDirectory.Layer("Embervale", "ore:copper",
                "铜", "ore", "copper", 747, "#c97b3c");
        ResourceDirectory.Hotspot hotspot = new ResourceDirectory.Hotspot(5250, 2350, 32,
                5000, 2200, 5500, 2500, "火焰神龛·雾水盆地", 680, 100, 100);
        return new ResourceDirectory.Answer("铜矿", layer, 747, 250, List.of(hotspot));
    }

    @Test
    @DisplayName("★ 地点没命中、资源层命中：返回热点文字，并附第一片的点云取图凭据")
    void fallsBackToResourceDistribution() {
        ResourceDirectory resources = mock(ResourceDirectory.class);
        when(resources.answer("铜矿")).thenReturn(copper());

        String out = tool(places(), resources).locate("铜矿", null);

        assertThat(out).contains("747").contains("点位最密").contains("半径 250 内 32 个点")
                .contains("火焰神龛·雾水盆地").contains("坐标 (5250, 2350)")
                .contains("配图：[[img:oreshot:Embervale/ore:copper/1]]");
        assertThat(out).doesNotContain("[[img:map:");
    }

    @Test
    @DisplayName("截图总开关关掉：只回文字，不附取图凭据")
    void noMarkerWhenShotDisabled() {
        ResourceDirectory resources = mock(ResourceDirectory.class);
        when(resources.answer("铜矿")).thenReturn(copper());
        MapOptions options = new MapOptions();
        options.getShot().setEnabled(false);
        KbLookupTool tool = new KbLookupTool(mock(KbRetriever.class), mock(KbCorpus.class),
                places(), options, resources);

        String out = tool.locate("铜矿", null);

        assertThat(out).contains("747");
        assertThat(out).doesNotContain("[[img:");
    }

    @Test
    @DisplayName("具名地点命中时优先地点，不再查资源层")
    void placeWinsOverResource() {
        PlacePoint point = new PlacePoint("Embervale", "Map:Embervale/Background", "测试矿",
                List.of("测试矿"), 100, 200, "poi", "", true, 0, "wiki",
                "Map:Embervale/Main#TestMine");
        ResourceDirectory resources = mock(ResourceDirectory.class);

        String out = tool(places(point), resources).locate("测试矿", null);

        assertThat(out).contains("「测试矿」在地图上有 1 处");
        verifyNoInteractions(resources);
    }

    @Test
    @DisplayName("资源层也没命中：保持原来的「没有标记过」话术")
    void keepsNotFoundWhenNothingMatches() {
        ResourceDirectory resources = mock(ResourceDirectory.class);
        when(resources.answer("不存在的东西")).thenReturn(null);

        String out = tool(places(), resources).locate("不存在的东西", null);

        assertThat(out).contains("地图上没有标记过");
    }

    @Test
    @DisplayName("资源层抛异常也只是退回「没查到」，不影响 locate")
    void resourceFailureIsSwallowed() {
        ResourceDirectory resources = mock(ResourceDirectory.class);
        when(resources.answer("铜矿")).thenThrow(new IllegalStateException("模拟故障"));

        String out = tool(places(), resources).locate("铜矿", null);

        assertThat(out).contains("地图上没有标记过");
    }
}
