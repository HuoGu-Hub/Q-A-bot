package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地图截图的验收测试。
 *
 * <p>要证明的是三件事：**图能出、点画在该在的位置、缺料时宁可不画**。
 * 用的是一张合成的画布与纯色假瓦片 —— 渲染逻辑与真实底图无关，
 * 而"点画在哪个像素"正是最容易静默出错的地方。
 */
class MapShotRendererTest {

    /** 2560×2560 的画布，2×2 张 1280 的瓦片 */
    private static final MapProjection.Canvas CANVAS = MapProjection.Canvas.of(
            new double[]{0, 2560}, new double[]{2560, 0}, new double[]{0, 0}, new double[]{1280, 1280});

    private static final List<MapViewService.TileView> TILES = List.of(
            new MapViewService.TileView("t00.png", 0, 0),
            new MapViewService.TileView("t10.png", 1, 0),
            new MapViewService.TileView("t01.png", 0, 1),
            new MapViewService.TileView("t11.png", 1, 1));

    private MapOptions options;
    private MapShotRenderer renderer;
    private byte[] whiteTile;

    @BeforeEach
    void setUp() throws IOException {
        options = new MapOptions();
        options.getShot().setWidth(200);
        options.getShot().setHeight(125);
        renderer = new MapShotRenderer(options);
        whiteTile = solidPng(255, 255, 255);
    }

    private MapShotRenderer.TileFetcher whiteFetcher() {
        return (image, width) -> Optional.of(new MapImageCache.Image(whiteTile, "image/png", true));
    }

    @Test
    @DisplayName("★ 出图：尺寸按配置，只有目标点套红圈，底图铺满")
    void rendersMarksOnTiles() throws IOException {
        byte[] shot = renderer.render(CANVAS, TILES, "",
                List.of(new MapShotRenderer.Mark(1280, 1280, true, "Target"),
                        new MapShotRenderer.Mark(1980, 1280, false, "Landmark")),
                whiteFetcher(), System.currentTimeMillis() + 5000).orElseThrow();

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(shot));
        assertThat(img.getWidth()).isEqualTo(200);
        assertThat(img.getHeight()).isEqualTo(125);

        // 视口以两点中点 (1630,1280) 为中心，跨度被抬到下限 1200、高 750，缩放 k = 200/1200
        // 目标点 (1280,1280) → 屏幕 (41.7, 62.5)；参照点 (1980,1280) → (158.3, 62.5)
        assertThat(hasRedNear(img, 42, 63, 12)).as("目标点附近应有红圈").isTrue();
        assertThat(hasAmberNear(img, 158, 63, 12)).as("参照点不该再套一个圈").isFalse();
        assertThat(isWhiteNear(img, 5, 5)).as("左上角应该是底图").isTrue();
    }

    @Test
    @DisplayName("★ 资源点云：点画在该在的位置，热点套圈，不写名字")
    void rendersDots() throws IOException {
        Color copper = new Color(201, 123, 60);
        byte[] shot = renderer.renderDots(CANVAS, TILES, "",
                List.of(new MapShotRenderer.Dot(1280, 1280, copper),
                        new MapShotRenderer.Dot(1400, 1280, copper)),
                List.of(new MapProjection.Point(1280, 1280),
                        new MapProjection.Point(1400, 1280)),
                new MapProjection.Point(1340, 1280), 40,
                whiteFetcher(), System.currentTimeMillis() + 5000).orElseThrow();

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(shot));
        assertThat(img.getWidth()).isEqualTo(200);
        assertThat(img.getHeight()).isEqualTo(125);
        // 视口 1200×750 居中于 (1340,1280)，k = 200/1200：两个点落在 (90,63) 与 (110,63)
        assertThat(hasColorNear(img, 90, 63, 3, 201, 123, 60)).as("左边的铜点").isTrue();
        assertThat(hasColorNear(img, 110, 63, 3, 201, 123, 60)).as("右边的铜点").isTrue();
        // 热点半径 40 → 屏幕约 7 像素，圈在 x≈107
        assertThat(hasRedNear(img, 107, 63, 4)).as("热点圈").isTrue();
    }

    @Test
    @DisplayName("★ 缺一张瓦片就整张不出 —— 半张图比没有图更糟")
    void missingTileGivesUp() {
        MapShotRenderer.TileFetcher partial = (image, width) -> image.equals("t01.png")
                ? Optional.empty()
                : Optional.of(new MapImageCache.Image(whiteTile, "image/png", true));

        assertThat(renderer.render(CANVAS, TILES, "",
                List.of(new MapShotRenderer.Mark(1280, 1280, true, "")),
                partial, System.currentTimeMillis() + 5000)).isEmpty();
    }

    @Test
    @DisplayName("★ 一张缺就整张不出，但会把相交的瓦片全部试一遍 —— 调用方要靠它拿到完整缺口")
    void missingTileStillTriesEveryTile() {
        List<String> asked = new java.util.ArrayList<>();
        MapShotRenderer.TileFetcher partial = (image, width) -> {
            asked.add(image);
            return image.equals("t01.png")
                    ? Optional.empty()
                    : Optional.of(new MapImageCache.Image(whiteTile, "image/png", true));
        };

        assertThat(renderer.render(CANVAS, TILES, "",
                List.of(new MapShotRenderer.Mark(1280, 1280, true, "")),
                partial, System.currentTimeMillis() + 5000)).isEmpty();
        assertThat(asked).containsExactlyInAnyOrder("t00.png", "t10.png", "t01.png", "t11.png");
    }

    @Test
    @DisplayName("★ 超出取图预算就放弃，不把出站路径拖住；但**没取上的瓦片必须登记出去**")
    void deadlineGivesUpButReportsSkippedTiles() {
        List<String> needed = new java.util.ArrayList<>();
        List<String> fetched = new java.util.ArrayList<>();
        MapShotRenderer.TileFetcher recording = new MapShotRenderer.TileFetcher() {
            @Override
            public Optional<MapImageCache.Image> get(String image, int width) {
                fetched.add(image);
                return Optional.of(new MapImageCache.Image(whiteTile, "image/png", true));
            }

            @Override
            public void need(String image, int width) {
                needed.add(image);
            }
        };

        assertThat(renderer.render(CANVAS, TILES, "",
                List.of(new MapShotRenderer.Mark(1280, 1280, true, "")),
                recording, System.currentTimeMillis() - 1)).isEmpty();
        // 一张都不该去取：预算已经用完，取图只会把出站路径拖住
        assertThat(fetched).isEmpty();
        // 缺口一张都不能少 —— 调用方要靠它去按需预热，否则同一个点位问多少次都不出图
        assertThat(needed).containsExactlyInAnyOrder("t00.png", "t10.png", "t01.png", "t11.png");
    }

    @Test
    @DisplayName("★ 背景图标只提供参照，不参与取景 —— 否则远处一个图标就把目标点挤到角落")
    void contextIconsDoNotMoveViewport() throws IOException {
        MapShotRenderer.TileFetcher withIcons = new MapShotRenderer.TileFetcher() {
            @Override
            public Optional<MapImageCache.Image> get(String image, int width) {
                return Optional.of(new MapImageCache.Image(whiteTile, "image/png", true));
            }

            @Override
            public Optional<MapImageCache.Image> icon(String image) {
                return Optional.of(new MapImageCache.Image(whiteTile, "image/png", true));
            }
        };

        byte[] shot = renderer.render(CANVAS, TILES, "",
                List.of(new MapShotRenderer.Mark(1280, 1280, true, "目标")),
                List.of(MapShotRenderer.Mark.icon(2500, 2500, "Far Mine", "Far.png"),
                        MapShotRenderer.Mark.icon(1400, 1280, "Near Camp", "Near.png")),
                withIcons, System.currentTimeMillis() + 5000).orElseThrow();

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(shot));
        // 只有一个目标点时视口是 1200×750 居中于它 → 屏幕正中心 (100,63)。
        // 远处那个 (2500,2500) 的图标要是参与了取景，目标点就会被挤到角落
        assertThat(hasRedNear(img, 100, 63, 12)).as("远处图标不该改变取景").isTrue();
    }

    @Test
    @DisplayName("★ 背景图标画得出：暗底 + 图标文件本身")
    void contextIconsAreDrawn() throws IOException {
        int[] asked = {0};
        MapShotRenderer.TileFetcher withIcons = new MapShotRenderer.TileFetcher() {
            @Override
            public Optional<MapImageCache.Image> get(String image, int width) {
                return Optional.of(new MapImageCache.Image(whiteTile, "image/png", true));
            }

            @Override
            public Optional<MapImageCache.Image> icon(String image) {
                asked[0]++;
                return Optional.of(new MapImageCache.Image(whiteTile, "image/png", true));
            }
        };

        byte[] quiet = renderer.render(CANVAS, TILES, "",
                List.of(new MapShotRenderer.Mark(1280, 1280, true, "")),
                List.of(), withIcons, System.currentTimeMillis() + 5000).orElseThrow();
        byte[] shot = renderer.render(CANVAS, TILES, "",
                List.of(new MapShotRenderer.Mark(1280, 1280, true, "")),
                List.of(MapShotRenderer.Mark.icon(1400, 1280, "Near Camp", "Near.png"),
                        MapShotRenderer.Mark.icon(1420, 1300, "Far Camp", "Near.png")),
                withIcons, System.currentTimeMillis() + 5000).orElseThrow();

        // 两个点同一个分组：图标只解码一次
        assertThat(asked[0]).isEqualTo(1);
        // 白底 + 不透明的白图标，能看见的只有图标四周那圈暗底（真正的图标是带透明底的 PNG，
        // 暗底会露出更多）。这里只钉住"暗底确实画了"，不去数具体像素
        assertThat(darkPixels(ImageIO.read(new ByteArrayInputStream(shot))))
                .as("图标底下必须有一圈暗色，否则浅底图上就看不见了")
                .isGreaterThan(darkPixels(ImageIO.read(new ByteArrayInputStream(quiet))) + 20);
    }

    @Test
    @DisplayName("★ 目标点与地标都要写上名字 —— 没有名字的圈定不了位")
    void drawsLabelsForMarks() throws IOException {
        // 用纯白瓦片当底图：那片区域默认是白的，多出来的深色像素只可能来自标签胶囊
        byte[] quiet = renderer.render(CANVAS, TILES, "",
                List.of(new MapShotRenderer.Mark(1280, 1280, true, "")),
                whiteFetcher(), System.currentTimeMillis() + 5000).orElseThrow();
        byte[] labelled = renderer.render(CANVAS, TILES, "",
                List.of(new MapShotRenderer.Mark(1280, 1280, true, "Iron Mine"),
                        new MapShotRenderer.Mark(1980, 1280, false, "Flame Shrine")),
                whiteFetcher(), System.currentTimeMillis() + 5000).orElseThrow();

        assertThat(darkPixels(ImageIO.read(new ByteArrayInputStream(labelled))))
                .as("写了名字就一定有字与底衬画上去")
                .isGreaterThan(darkPixels(ImageIO.read(new ByteArrayInputStream(quiet))) + 200);
    }

    @Test
    @DisplayName("既没有瓦片也没有整图：画不出来，老实返回空")
    void noBaseMapGivesUp() {
        assertThat(renderer.render(CANVAS, List.of(), "", List.of(new MapShotRenderer.Mark(1280, 1280, true, "")),
                whiteFetcher(), System.currentTimeMillis() + 5000)).isEmpty();
    }

    @Test
    @DisplayName("旧 schema 只有一张整图时也能出图")
    void rendersWholeBackground() throws IOException {
        byte[] shot = renderer.render(CANVAS, List.of(), "whole.png",
                List.of(new MapShotRenderer.Mark(1280, 1280, true, "")),
                whiteFetcher(), System.currentTimeMillis() + 5000).orElseThrow();

        assertThat(ImageIO.read(new ByteArrayInputStream(shot)).getWidth()).isEqualTo(200);
    }

    @Test
    @DisplayName("总开关关掉：一张都不画")
    void disabledRendersNothing() {
        options.getShot().setEnabled(false);

        assertThat(renderer.render(CANVAS, TILES, "",
                List.of(new MapShotRenderer.Mark(1280, 1280, true, "")),
                whiteFetcher(), System.currentTimeMillis() + 5000)).isEmpty();
    }

    /* ==================== 断言工具 ==================== */

    private static boolean hasColorNear(BufferedImage img, int cx, int cy, int radius, int r, int g, int b) {
        return anyPixel(img, cx, cy, radius, (rr, gg, bb) ->
                Math.abs(rr - r) < 90 && Math.abs(gg - g) < 90 && Math.abs(bb - b) < 90);
    }

    private static boolean hasRedNear(BufferedImage img, int cx, int cy, int radius) {
        return anyPixel(img, cx, cy, radius, (r, g, b) -> r > 140 && r - g > 50 && r - b > 50);
    }

    private static boolean hasAmberNear(BufferedImage img, int cx, int cy, int radius) {
        return anyPixel(img, cx, cy, radius, (r, g, b) -> r > 170 && g > 120 && b < 120 && r - b > 80);
    }

    /** 整张图里"明显偏暗"的像素数 —— 白底图上，只有标签胶囊与圈会把它抬起来 */
    private static int darkPixels(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) < 150 && ((rgb >> 8) & 0xFF) < 150 && (rgb & 0xFF) < 150) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean isWhiteNear(BufferedImage img, int cx, int cy) {
        return anyPixel(img, cx, cy, 2, (r, g, b) -> r > 200 && g > 200 && b > 200);
    }

    private interface PixelTest {
        boolean test(int r, int g, int b);
    }

    private static boolean anyPixel(BufferedImage img, int cx, int cy, int radius, PixelTest test) {
        for (int y = Math.max(0, cy - radius); y <= Math.min(img.getHeight() - 1, cy + radius); y++) {
            for (int x = Math.max(0, cx - radius); x <= Math.min(img.getWidth() - 1, cx + radius); x++) {
                int rgb = img.getRGB(x, y);
                if (test.test((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] solidPng(int r, int g, int b) throws IOException {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D gr = img.createGraphics();
        gr.setColor(new Color(r, g, b));
        gr.fillRect(0, 0, 64, 64);
        gr.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
