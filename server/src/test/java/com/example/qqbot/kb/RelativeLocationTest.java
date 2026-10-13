package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 相对位置算法。
 *
 * <p>这些断言全部是**纯几何**的，不碰数据库也不碰网络 —— 这正是把算法放进
 * {@link RelativeLocation} 这类纯函数的原因：它能被钉死，而不是"跑一次看着对"。
 */
class RelativeLocationTest {

    /**
     * 造一个点位。
     *
     * <p>ref 由坐标与名字生成 —— 曾经这里所有点共用同一个 ref，于是
     * {@code pickAnchor} 把每一个候选都当成"目标自己"过滤掉了，测试全绿也测不出东西。
     */
    private static PlacePoint point(String name, double x, double y, boolean landmark, int priority) {
        return new PlacePoint("Embervale", "Map:Embervale/Background", name, List.of(),
                x, y, landmark ? PlaceCategory.LANDMARK : PlaceCategory.POI, "",
                landmark, priority, "admin", "place:" + name + "@" + x + "," + y);
    }

    /* ==================== 方向 ==================== */

    @Test
    @DisplayName("八扇区：y 轴向上，0 号是正北，顺时针排")
    void sectors() {
        assertThat(RelativeLocation.direction(0, 1)).isEqualTo("正北");
        assertThat(RelativeLocation.direction(1, 1)).isEqualTo("东北");
        assertThat(RelativeLocation.direction(1, 0)).isEqualTo("正东");
        assertThat(RelativeLocation.direction(1, -1)).isEqualTo("东南");
        assertThat(RelativeLocation.direction(0, -1)).isEqualTo("正南");
        assertThat(RelativeLocation.direction(-1, -1)).isEqualTo("西南");
        assertThat(RelativeLocation.direction(-1, 0)).isEqualTo("正西");
        assertThat(RelativeLocation.direction(-1, 1)).isEqualTo("西北");
    }

    @Test
    @DisplayName("同一方位要同时给罗盘说法与屏幕说法 —— 玩家盯着地图想的是「右上角」")
    void screenHints() {
        assertThat(RelativeLocation.screenHint(1, 1)).isEqualTo("右上角");
        assertThat(RelativeLocation.screenHint(-1, -1)).isEqualTo("左下角");
        assertThat(RelativeLocation.screenHint(0, 1)).isEqualTo("正上方");
    }

    @Test
    @DisplayName("方位角从正北顺时针，东北是 45 度")
    void bearing() {
        assertThat(RelativeLocation.bearingDegrees(0, 1)).isEqualTo(0);
        assertThat(RelativeLocation.bearingDegrees(1, 1)).isCloseTo(45, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(RelativeLocation.bearingDegrees(1, 0)).isCloseTo(90, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(RelativeLocation.bearingDegrees(-1, 0)).isCloseTo(270, org.assertj.core.data.Offset.offset(1e-6));
    }

    /* ==================== 距离分档 ==================== */

    @Test
    @DisplayName("远近按地图对角线归一化 —— 同样的距离在不同大小的图上含义不同")
    void bucketsAreRelativeToMapSize() {
        double diagonal = 1000;
        assertThat(RelativeLocation.bucket(10, diagonal)).isEqualTo("就在旁边");
        assertThat(RelativeLocation.bucket(30, diagonal)).isEqualTo("很近");
        assertThat(RelativeLocation.bucket(100, diagonal)).isEqualTo("不远");
        assertThat(RelativeLocation.bucket(200, diagonal)).isEqualTo("有点远");
        assertThat(RelativeLocation.bucket(400, diagonal)).isEqualTo("很远");
    }

    @Test
    @DisplayName("取不到地图尺寸时退回中性档，而不是把一切都算成「就在旁边」")
    void bucketWithoutMapSize() {
        assertThat(RelativeLocation.bucket(500, 0)).isEqualTo("不远");
        assertThat(RelativeLocation.bucket(500, Double.NaN)).isEqualTo("不远");
    }

    @Test
    @DisplayName("对角线取宽高的平方和开方")
    void diagonal() {
        assertThat(RelativeLocation.diagonal(new double[]{3, 4})).isEqualTo(5);
        assertThat(RelativeLocation.diagonal(null)).isZero();
    }

    /* ==================== 锚点选择 ==================== */

    @Test
    @DisplayName("距离是主因：近的普通地标胜过远的名地标")
    void distanceWins() {
        PlacePoint target = point("铁矿", 100, 100, false, 0);
        // 锚点在目标的**西边**，所以从锚点看过去，目标在正东
        PlacePoint near = point("近处尖塔", 80, 100, true, 0);
        PlacePoint far = point("远处尖塔", 900, 900, true, 100);
        RelativeLocation.Neighbour n = RelativeLocation.pickAnchor(target, List.of(near, far), 1000);
        assertThat(n).isNotNull();
        assertThat(n.anchor().name()).isEqualTo("近处尖塔");
        assertThat(n.direction()).isEqualTo("正东");
    }

    @Test
    @DisplayName("不拿自己当自己的参照物")
    void neverAnchorsToItself() {
        PlacePoint target = point("铁矿", 100, 100, true, 0);
        PlacePoint other = point("别的地方", 400, 400, true, 0);
        RelativeLocation.Neighbour n = RelativeLocation.pickAnchor(target, List.of(target, other), 1000);
        assertThat(n).isNotNull();
        assertThat(n.anchor().name()).isEqualTo("别的地方");
    }

    @Test
    @DisplayName("跨地图不算：不同地图的原点没有可比性")
    void ignoresOtherMaps() {
        PlacePoint target = point("铁矿", 100, 100, false, 0);
        PlacePoint otherMap = new PlacePoint("Blackmire", "Map:Blackmire/Main", "黑沼尖塔",
                List.of(), 110, 110, PlaceCategory.LANDMARK, "", true, 0, "wiki", "r1");
        assertThat(RelativeLocation.pickAnchor(target, List.of(otherMap), 1000)).isNull();
    }

    @Test
    @DisplayName("优先同画布：跨画布的坐标差是垃圾")
    void prefersSameCanvas() {
        PlacePoint target = new PlacePoint("Embervale", "Map:Embervale/Background", "铁矿",
                List.of(), 100, 100, PlaceCategory.RESOURCE, "", false, 0, "admin", "place:1");
        PlacePoint sameCanvas = new PlacePoint("Embervale", "Map:Embervale/Background", "主图尖塔",
                List.of(), 300, 100, PlaceCategory.LANDMARK, "", true, 0, "wiki", "r2");
        PlacePoint otherCanvas = new PlacePoint("Embervale", "Map:Embervale/Collectibles", "收集图尖塔",
                List.of(), 110, 105, PlaceCategory.LANDMARK, "", true, 0, "wiki", "r3");
        RelativeLocation.Neighbour n = RelativeLocation.pickAnchor(target,
                List.of(otherCanvas, sameCanvas), 1000);
        assertThat(n).isNotNull();
        assertThat(n.anchor().name()).isEqualTo("主图尖塔");
    }

    @Test
    @DisplayName("没有地标就退回普通点位 —— 有参照总比只有一个裸坐标强")
    void fallsBackToNonLandmark() {
        PlacePoint target = point("铁矿", 100, 100, false, 0);
        PlacePoint plain = point("某个营地", 150, 100, false, 0);
        RelativeLocation.Neighbour n = RelativeLocation.pickAnchor(target, List.of(plain), 1000);
        assertThat(n).isNotNull();
        assertThat(n.anchor().name()).isEqualTo("某个营地");
    }

    @Test
    @DisplayName("名望封顶：人工优先级拉不满整张地图的距离差")
    void renownIsCapped() {
        PlacePoint top = new PlacePoint("Embervale", "c", "满优先级",
                List.of(), 0, 0, PlaceCategory.LANDMARK, "", true, 100, "admin", "a");
        assertThat(RelativeLocation.renown(top)).isLessThanOrEqualTo(1.0);
    }

    /* ==================== 成句 ==================== */

    @Test
    @DisplayName("描述里有方向、屏幕说法、距离与远近档")
    void phrase() {
        PlacePoint target = point("铁矿", 110, 100, false, 0);
        PlacePoint anchor = point("远古尖塔", 100, 100, true, 0);
        RelativeLocation.Neighbour n = RelativeLocation.pickAnchor(target, List.of(anchor), 1000);
        assertThat(n).isNotNull();
        String s = n.phrase();
        assertThat(s)
                .contains("远古尖塔")
                .contains("正东")
                .contains("地图正右方")
                .contains("10 坐标单位")
                .contains("就在旁边");
        assertThat(s).doesNotContain("米");
    }

    @Test
    @DisplayName("默认不写米；配了比例才换算 —— 猜一个比例会被模型当成事实转述")
    void phraseWithMeters() {
        PlacePoint target = point("铁矿", 110, 100, false, 0);
        PlacePoint anchor = point("远古尖塔", 100, 100, true, 0);
        RelativeLocation.Neighbour n = RelativeLocation.pickAnchor(target, List.of(anchor), 1000);
        assertThat(n).isNotNull();
        assertThat(n.phrase()).doesNotContain("米");
        assertThat(n.phrase(0.5)).contains("20 米");
    }
}
