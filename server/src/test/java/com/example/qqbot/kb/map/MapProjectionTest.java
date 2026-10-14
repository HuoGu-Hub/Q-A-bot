package com.example.qqbot.kb.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 坐标换算的验收测试 —— 用的是**数据库里的真实画布与真实点位**。
 *
 * <p>这一层是"标记会不会画偏"的唯一防线：算错不会抛异常，只会让圈画在别处。
 * 所以期望值全部是手算出来的具体数字，而不是"再调一次被测代码"。
 *
 * <h2>样本从哪来</h2>
 * <ul>
 *   <li>主画布 {@code Map:Embervale/Background}：四至 (0,10240) → (10240,0)，瓦片 1280，网格原点 (0,0)</li>
 *   <li>点位 {@code Ancient Spire - Springlands}：crs (3508, 1982)</li>
 *   <li>收集品画布 {@code Map:Embervale/Collectibles}：原始四至 (-3695,-9045) → (6545,1195)，
 *       归一化后是 (-3695,1195) → (6545,-9045)；点位 {@code Ammonite Imprint} crs (-834, -984)</li>
 * </ul>
 */
class MapProjectionTest {

    private static final MapProjection.Canvas MAIN = MapProjection.Canvas.of(
            new double[]{0, 10240}, new double[]{10240, 0}, new double[]{0, 0}, new double[]{1280, 1280});

    /** 收集品画布：四至已按 y 轴向上归一化 */
    private static final MapProjection.Canvas COLLECTIBLES = MapProjection.Canvas.of(
            new double[]{-3695, 1195}, new double[]{6545, -9045}, new double[]{-3695, -9045},
            new double[]{1280, 1280});

    private static final List<Integer> WIDTHS = List.of(0, 160, 320, 640, 1280);

    /* ==================== 四至归一化 ==================== */

    @Test
    @DisplayName("★ y 轴向下的画布被翻正 —— 收集品画布就是这种")
    void normalizesDownwardY() {
        double[][] corners = MapProjection.normalizeCorners(new double[]{-3695, -9045},
                new double[]{6545, 1195});

        assertThat(corners[0]).containsExactly(-3695, 1195);
        assertThat(corners[1]).containsExactly(6545, -9045);
    }

    @Test
    @DisplayName("本来就向上的画布原样不动")
    void keepsUpwardYUntouched() {
        double[][] corners = MapProjection.normalizeCorners(new double[]{0, 10240}, new double[]{10240, 0});

        assertThat(corners[0]).containsExactly(0, 10240);
        assertThat(corners[1]).containsExactly(10240, 0);
    }

    /* ==================== crs → 像素 ==================== */

    @Test
    @DisplayName("★ 真实点位：crs (3508,1982) → 画布像素 (3508,8258)，y 是翻转的")
    void worldOfFlipsY() {
        MapProjection.Point p = MapProjection.worldOf(MAIN, 3508, 1982);

        assertThat(p.x()).isEqualTo(3508.0);
        assertThat(p.y()).isEqualTo(8258.0);
    }

    @Test
    @DisplayName("★ 瓦片行号用 crs 算：真实点位落在第 2 列第 1 行")
    void tileOfUsesCrs() {
        assertThat(MapProjection.tileOf(MAIN, 3508, 1982)).containsExactly(2, 1);
    }

    @Test
    @DisplayName("★ 瓦片 top 取上边：第 2 列第 1 行在画布上的位置是 (2560,7680)")
    void tileBoxTakesTopEdge() {
        MapProjection.Box box = MapProjection.tileBox(MAIN, 2, 1);

        assertThat(box.left()).isEqualTo(2560.0);
        assertThat(box.top()).isEqualTo(7680.0);
        assertThat(box.width()).isEqualTo(1280.0);
        assertThat(box.height()).isEqualTo(1280.0);
    }

    @Test
    @DisplayName("★ 交叉校验：点位的像素必须落在它自己那块瓦片里")
    void pointFallsInsideItsOwnTile() {
        MapProjection.Point p = MapProjection.worldOf(MAIN, 3508, 1982);
        int[] tile = MapProjection.tileOf(MAIN, 3508, 1982);
        MapProjection.Box box = MapProjection.tileBox(MAIN, tile[0], tile[1]);

        assertThat(p.x()).isBetween(box.left(), box.left() + box.width());
        assertThat(p.y()).isBetween(box.top(), box.top() + box.height());
    }

    @Test
    @DisplayName("★ 网格原点不等于左上角：收集品画布两者差着上万单位")
    void tileGridOriginIsNotTopLeft() {
        // 归一化后的四至左上角是 (-3695,1195)，而瓦片网格原点是 (-3695,-9045)
        MapProjection.Point p = MapProjection.worldOf(COLLECTIBLES, -834, -984);
        assertThat(p.x()).isEqualTo(2861.0);
        assertThat(p.y()).isEqualTo(2179.0);

        int[] tile = MapProjection.tileOf(COLLECTIBLES, -834, -984);
        assertThat(tile).containsExactly(2, 6);

        MapProjection.Box box = MapProjection.tileBox(COLLECTIBLES, tile[0], tile[1]);
        assertThat(box.left()).isEqualTo(2560.0);
        assertThat(box.top()).isEqualTo(1280.0);
        assertThat(p.x()).isBetween(box.left(), box.left() + box.width());
        assertThat(p.y()).isBetween(box.top(), box.top() + box.height());
    }

    /* ==================== 视口 ==================== */

    @Test
    @DisplayName("★ 视口把目标点与地标都框进去，并保持输出长宽比")
    void viewportFramesBothPoints() {
        MapProjection.Point target = MapProjection.worldOf(MAIN, 3508, 1982);
        MapProjection.Point landmark = MapProjection.worldOf(MAIN, 2560, 2560);

        MapProjection.Viewport vp = MapProjection.viewport(MAIN, List.of(target, landmark),
                1.6, 1200, 6000, 0.15);

        assertThat(vp.left()).isLessThanOrEqualTo(Math.min(target.x(), landmark.x()));
        assertThat(vp.top()).isLessThanOrEqualTo(Math.min(target.y(), landmark.y()));
        assertThat(vp.left() + vp.width()).isGreaterThanOrEqualTo(Math.max(target.x(), landmark.x()));
        assertThat(vp.top() + vp.height()).isGreaterThanOrEqualTo(Math.max(target.y(), landmark.y()));
        assertThat(vp.width() / vp.height()).isCloseTo(1.6, within(1e-6));
        assertThat(vp.width()).isBetween(1200.0, 6000.0);
    }

    @Test
    @DisplayName("两点离得再远也不超过最大跨度 —— 否则地标会缩到看不见")
    void viewportClampsToMaxSpan() {
        MapProjection.Viewport vp = MapProjection.viewport(MAIN,
                List.of(new MapProjection.Point(0, 0), new MapProjection.Point(10000, 10000)),
                1.6, 1200, 6000, 0.15);

        assertThat(vp.width()).isEqualTo(6000.0);
    }

    @Test
    @DisplayName("两点几乎重合时用最小跨度兜底 —— 不然会放大成一片马赛克")
    void viewportClampsToMinSpan() {
        MapProjection.Viewport vp = MapProjection.viewport(MAIN,
                List.of(new MapProjection.Point(5000, 5000), new MapProjection.Point(5000, 5000)),
                1.6, 1200, 6000, 0.15);

        assertThat(vp.width()).isEqualTo(1200.0);
        assertThat(vp.height()).isCloseTo(750.0, within(1e-6));
    }

    @Test
    @DisplayName("视口不越出画布：贴边时被推回来")
    void viewportStaysInsideCanvas() {
        MapProjection.Viewport vp = MapProjection.viewport(MAIN,
                List.of(new MapProjection.Point(30, 40)), 1.6, 1200, 6000, 0.15);

        assertThat(vp.left()).isGreaterThanOrEqualTo(0.0);
        assertThat(vp.top()).isGreaterThanOrEqualTo(0.0);
        assertThat(vp.left() + vp.width()).isLessThanOrEqualTo(MAIN.width());
        assertThat(vp.top() + vp.height()).isLessThanOrEqualTo(MAIN.height());
    }

    /* ==================== 档位反推 ==================== */

    @Test
    @DisplayName("★ 档位反推：跨度 2560 用 640 档、5120 用 320 档、10240 用 160 档")
    void picksTileWidthBySpan() {
        assertThat(MapProjection.pickTileWidth(2560, 1280, WIDTHS)).isEqualTo(640);
        assertThat(MapProjection.pickTileWidth(5120, 1280, WIDTHS)).isEqualTo(320);
        assertThat(MapProjection.pickTileWidth(10240, 1280, WIDTHS)).isEqualTo(160);
    }

    @Test
    @DisplayName("跨度很小就用最高档，不会退化成原图")
    void picksHighestWhenSpanIsSmall() {
        assertThat(MapProjection.pickTileWidth(1200, 1280, WIDTHS)).isEqualTo(1280);
    }

    @Test
    @DisplayName("跨度大到没有够小的档位时退回最小档，而不是返回原图")
    void fallsBackToSmallest() {
        assertThat(MapProjection.pickTileWidth(40000, 1280, WIDTHS)).isEqualTo(160);
    }

    @Test
    @DisplayName("没有可用档位时返回 0，调用方据此放弃出图")
    void noWidthsMeansZero() {
        assertThat(MapProjection.pickTileWidth(2560, 1280, List.of(0))).isZero();
        assertThat(MapProjection.pickTileWidth(2560, 1280, List.of())).isZero();
    }
}
