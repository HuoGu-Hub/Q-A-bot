package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 热点统计的纯函数验收：分得开、算得对、每次一样。 */
class ResourceHotspotsTest {

    @Test
    @DisplayName("两组密集点 + 一个孤立点：按点数排序，热点互不重叠")
    void findsSeparateHotspots() {
        List<ResourceHotspots.Point> points = List.of(
                new ResourceHotspots.Point(0, 0),
                new ResourceHotspots.Point(10, 0),
                new ResourceHotspots.Point(20, 0),
                new ResourceHotspots.Point(15, 15),
                new ResourceHotspots.Point(1000, 0),
                new ResourceHotspots.Point(1010, 0),
                new ResourceHotspots.Point(1020, 0),
                new ResourceHotspots.Point(500, 500));

        List<ResourceHotspots.Hotspot> found =
                ResourceHotspots.find(points, 100, 300, 150, 3);

        assertThat(found).hasSize(3);
        assertThat(found.get(0).count()).isEqualTo(4);
        assertThat(found.get(1).count()).isEqualTo(3);
        assertThat(found.get(2).count()).isEqualTo(1);
        assertThat(found.get(0).x()).isBetween(0.0, 40.0);
        assertThat(found.get(1).x()).isBetween(990.0, 1030.0);
        // 两个密集片的质心必须隔开，不能是同一片
        assertThat(Math.abs(found.get(0).x() - found.get(1).x())).isGreaterThan(300);
    }

    @Test
    @DisplayName("单格点数少但周围更密的，排在孤立的高格前面")
    void ranksByLocalDensityNotSingleCell() {
        List<ResourceHotspots.Point> points = List.of(
                // 五个点分在五个格子里，但彼此都在 250 内
                new ResourceHotspots.Point(50, 50),
                new ResourceHotspots.Point(150, 50),
                new ResourceHotspots.Point(250, 50),
                new ResourceHotspots.Point(50, 150),
                new ResourceHotspots.Point(150, 150),
                // 四个点挤在同一格，但周围没有别的点
                new ResourceHotspots.Point(1000, 1000),
                new ResourceHotspots.Point(1010, 1000),
                new ResourceHotspots.Point(1020, 1000),
                new ResourceHotspots.Point(1030, 1000));

        List<ResourceHotspots.Hotspot> found = ResourceHotspots.find(points, 100, 500, 250, 2);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).count()).isEqualTo(5);
        assertThat(found.get(0).x()).isLessThan(500);
        assertThat(found.get(1).count()).isEqualTo(4);
    }

    @Test
    @DisplayName("包围盒包住这一片的点，留给后续截图取景")
    void keepsBounds() {
        List<ResourceHotspots.Hotspot> found = ResourceHotspots.find(List.of(
                new ResourceHotspots.Point(100, 100),
                new ResourceHotspots.Point(120, 140),
                new ResourceHotspots.Point(80, 90)), 500, 500, 500, 1);

        assertThat(found).hasSize(1);
        ResourceHotspots.Hotspot h = found.get(0);
        assertThat(h.minX()).isEqualTo(80.0);
        assertThat(h.maxX()).isEqualTo(120.0);
        assertThat(h.minY()).isEqualTo(90.0);
        assertThat(h.maxY()).isEqualTo(140.0);
    }

    @Test
    @DisplayName("同一份点算两次结果完全一样")
    void deterministic() {
        List<ResourceHotspots.Point> points = List.of(
                new ResourceHotspots.Point(10, 10),
                new ResourceHotspots.Point(900, 900),
                new ResourceHotspots.Point(910, 900),
                new ResourceHotspots.Point(500, 500));

        assertThat(ResourceHotspots.find(points)).isEqualTo(ResourceHotspots.find(points));
    }

    @Test
    @DisplayName("没有点、top 为 0 都返回空表，不抛异常")
    void emptyInputs() {
        assertThat(ResourceHotspots.find(List.of())).isEmpty();
        assertThat(ResourceHotspots.find(null)).isEmpty();
        assertThat(ResourceHotspots.find(List.of(new ResourceHotspots.Point(1, 1)), 100, 500, 250, 0))
                .isEmpty();
    }
}
