package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 资源热点配置的默认值与下限：配错不能让程序算出 0 或负数。 */
class MapOptionsResourceTest {

    @Test
    @DisplayName("默认值就是热点算法现在用的那几个")
    void defaults() {
        MapOptions.Resource r = new MapOptions().getResource();
        assertThat(r.getCellSize()).isEqualTo(ResourceHotspots.CELL);
        assertThat(r.getNmsRadius()).isEqualTo(ResourceHotspots.NMS_RADIUS);
        assertThat(r.getHotRadius()).isEqualTo(ResourceHotspots.HOT_RADIUS);
        assertThat(r.getTop()).isEqualTo(ResourceHotspots.TOP);
        assertThat(r.getLabelMax()).isEqualTo(8);
        assertThat(r.getMaxContext()).isEqualTo(40);
    }

    @Test
    @DisplayName("配成 0 / 负数会被收在下限，不会算出除零或空结果")
    void clamps() {
        MapOptions.Resource r = new MapOptions.Resource();
        r.setCellSize(0);
        r.setHotRadius(-1);
        r.setTop(0);
        r.setLabelMax(-1);
        r.setMaxContext(0);
        r.setNmsRadius(-5);

        assertThat(r.getCellSize()).isEqualTo(1);
        assertThat(r.getHotRadius()).isEqualTo(1);
        assertThat(r.getTop()).isEqualTo(1);
        assertThat(r.getLabelMax()).isZero();
        assertThat(r.getMaxContext()).isEqualTo(1);
        assertThat(r.getNmsRadius()).isZero();
    }
}
