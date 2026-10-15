package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.PlacePoint;
import com.example.qqbot.media.ImageMarkers;
import com.example.qqbot.onebot.model.OutboundImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 地图截图来源的验收测试。
 *
 * <p>要证明的是"生产者与消费者对得上"：{@code locate} 工具发出的那串身份，
 * 必须正好能被这里解析、画出一张图；任何一环对不上都只会静默地不出图。
 */
class MapShotSourceTest {

    private static final String MAP = "Embervale";
    private static final String CANVAS_PAGE = "Map:Embervale/Background";
    private static final String REF = "Map:Embervale/Main#AncientSpire-Springlands";

    private byte[] tilePng;

    private MapOptions options;
    private MapViewService view;
    private MapImageCache images;
    private MapShotWarmer warmer;
    private MapShotSource source;

    /** 夹具里的画布与点位 —— 个别用例要另配它们的兄弟点位 */
    private MapViewService.CanvasView canvasView;
    private MapViewService.MarkerView markerView;

    @BeforeEach
    void setUp() throws IOException {
        options = new MapOptions();
        options.getShot().setWidth(200);
        options.getShot().setHeight(125);

        List<MapViewService.TileView> tiles = List.of(
                new MapViewService.TileView("t00.png", 0, 0),
                new MapViewService.TileView("t10.png", 1, 0),
                new MapViewService.TileView("t01.png", 0, 1),
                new MapViewService.TileView("t11.png", 1, 1));
        MapViewService.CanvasView canvas = new MapViewService.CanvasView(CANVAS_PAGE, "余烬谷",
                new double[]{0, 2560}, new double[]{2560, 0}, new double[]{0, 0}, new double[]{1280, 1280},
                tiles, "", "", 0.1, 2.0);
        MapViewService.MarkerView marker = new MapViewService.MarkerView(REF, MAP,
                "Map:Embervale/Main", CANVAS_PAGE, "Landmark", "春之原野远古尖塔", "Ancient Spire - Springlands",
                "", "Ancient_Spire", 1280, 1280, "", true, "landmark", 0, "", "wiki");

        canvasView = canvas;
        markerView = marker;
        view = mock(MapViewService.class);
        when(view.catalog()).thenReturn(List.of(new MapViewService.MapView(MAP, "余烬谷", 1, 0,
                List.of(canvas), List.of(), List.of())));
        when(view.markers(MAP, false)).thenReturn(List.of(marker));
        when(view.canvasesOf(MAP)).thenReturn(List.of(canvas));

        tilePng = solidPng();
        images = mock(MapImageCache.class);
        when(images.get(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(new MapImageCache.Image(tilePng, "image/png", true)));

        warmer = mock(MapShotWarmer.class);
        source = new MapShotSource(view, images, new MapShotRenderer(options), warmer, options);
    }

    @Test
    @DisplayName("★ 工具发出的身份能被解析成一张图")
    void resolvesKeyIntoShot() {
        Optional<OutboundImage> got = source.resolve(PlacePoint.keyOf(REF));

        assertThat(got).isPresent();
        assertThat(got.get().bytesMime()).isEqualTo("image/jpeg");
        assertThat(got.get().byteSize()).isGreaterThan(0);
    }

    @Test
    @DisplayName("★ 生产者与消费者对得上：工具发的那行标记是合法标记")
    void emittedMarkerIsLegal() {
        String marker = "[[img:map:" + PlacePoint.keyOf(REF) + "]]";

        ImageMarkers.Parsed parsed = ImageMarkers.parse(marker);
        assertThat(parsed.keys()).containsExactly("map:" + PlacePoint.keyOf(REF));
        assertThat(parsed.text()).isEmpty();
    }

    @Test
    @DisplayName("带空格的 wiki 点位 id 会被洗成合法身份")
    void sanitizesSpaces() {
        assertThat(PlacePoint.keyOf("Map:Embervale/Main#Tower Ruin"))
                .isEqualTo("Map:Embervale/Main#Tower_Ruin");
        assertThat(ImageMarkers.parse("[[img:map:" + PlacePoint.keyOf("Map:Embervale/Main#Tower Ruin") + "]]")
                .keys()).hasSize(1);
    }

    @Test
    @DisplayName("没有这个身份的点位：不出图，也不抛异常")
    void unknownKeyIsEmpty() {
        assertThat(source.resolve("Map:Embervale/Main#NotThere")).isEmpty();
    }

    @Test
    @DisplayName("画布不可渲染时不出图 —— 宁可没有图，也不画一张错的")
    void unrenderableCanvasIsEmpty() {
        when(view.canvasesOf(MAP)).thenReturn(List.of());

        assertThat(source.resolve(PlacePoint.keyOf(REF))).isEmpty();
    }

    @Test
    @DisplayName("★ 缺瓦片时把缺口交给按需预热 —— 下次问同一个点位就有图")
    void handsMissingTilesToWarmer() {
        when(images.get(any(), any(), any(), anyInt()))
                .thenAnswer(inv -> "t01.png".equals(inv.getArgument(2))
                        ? Optional.empty()
                        : Optional.of(new MapImageCache.Image(tilePng, "image/png", true)));

        assertThat(source.resolve(PlacePoint.keyOf(REF))).isEmpty();
        verify(warmer).warmAsync(eq(MAP), eq(CANVAS_PAGE), eq(List.of("t01.png")), anyInt());
    }

    /**
     * 只登记缺口、一张都不取的假渲染器 —— 复刻"取第一张就把预算耗光"的真实情形。
     *
     * <p>真实场景里渲染器会**取到**第一张瓦片（只是花了 6 秒，远超 2 秒预算），
     * 所以那几张不会出现在 {@code get} 返回空的缺口里；旧实现直接 return，
     * 调用方拿到空缺口清单 → 按需预热一直不补 → 同一个点位问多少次都不出图。
     */
    private static final class BudgetExhaustedRenderer extends MapShotRenderer {

        BudgetExhaustedRenderer(MapOptions options) {
            super(options);
        }

        @Override
        public Optional<byte[]> render(MapProjection.Canvas canvas, List<MapViewService.TileView> tiles,
                                       String background, List<Mark> marks, List<Mark> context,
                                       TileFetcher fetcher, long deadlineAtMillis) {
            tiles.forEach(tile -> fetcher.need(tile.image(), 640));
            return Optional.empty();
        }
    }

    @Test
    @DisplayName("★ 超预算没取上的瓦片也要交给按需预热 —— 否则问多少次都不出图")
    void handsBudgetSkippedTilesToWarmer() {
        MapShotSource budgeted = new MapShotSource(view, images,
                new BudgetExhaustedRenderer(options), warmer, options);

        assertThat(budgeted.resolve(PlacePoint.keyOf(REF))).isEmpty();
        verify(warmer).warmAsync(eq(MAP), eq(CANVAS_PAGE),
                eq(List.of("t00.png", "t10.png", "t01.png", "t11.png")), eq(640));
    }

    /** 只把"背景图标"记下来、不真画图的渲染器 —— 筛选题材是这里的被测对象 */
    private static final class CapturingRenderer extends MapShotRenderer {

        List<Mark> context = List.of();

        CapturingRenderer(MapOptions options) {
            super(options);
        }

        @Override
        public Optional<byte[]> render(MapProjection.Canvas canvas, List<MapViewService.TileView> tiles,
                                       String background, List<Mark> marks, List<Mark> context,
                                       TileFetcher fetcher, long deadlineAtMillis) {
            this.context = List.copyOf(context);
            return Optional.empty();
        }
    }

    @Test
    @DisplayName("★ 背景图标只截默认显示的分组：收集品不入图，目标点与地标也不重复画")
    void contextIconsFollowDefaultLayers() {
        MapViewService.MarkerView shrine = new MapViewService.MarkerView(
                "Map:Embervale/Main#FlameShrine-1", MAP, "Map:Embervale/Main", CANVAS_PAGE,
                "Flame_Shrine", "火焰神龛·春之原野", "Flame Shrine", "", "Flame_Shrine",
                1980, 1280, "", true, "landmark", 0, "", "wiki");
        MapViewService.MarkerView fossil = new MapViewService.MarkerView(
                "Map:Embervale/Main#Fossil-1", MAP, "Map:Embervale/Main", CANVAS_PAGE,
                "Fossil", "化石", "Fossil", "", "Fossil",
                1300, 1980, "", false, "collection", 0, "", "wiki");
        MapViewService.MarkerView otherCanvas = new MapViewService.MarkerView(
                "Map:Embervale/Collectibles#Chest-1", MAP, "Map:Embervale/Collectibles",
                "Map:Embervale/Collectibles", "Camp", "箱子", "Chest", "", "",
                1280, 1280, "", false, "poi", 0, "", "wiki");
        when(view.markers(MAP, true)).thenReturn(List.of(markerView, shrine, fossil, otherCanvas));

        List<MapViewService.GroupView> groups = List.of(
                new MapViewService.GroupView("Landmark", "地标", "Landmark.png", false, true, "landmark", 1),
                new MapViewService.GroupView("Flame_Shrine", "火焰神龛", "Flame_Shrine.png", false, true, "landmark", 63),
                new MapViewService.GroupView("Fossil", "化石", "Fossil.png", true, false, "collection", 120));
        // defaultGroups = null：按内置经验值兜底（收集品之外都开），与公开站同一条规则
        when(view.catalog()).thenReturn(List.of(new MapViewService.MapView(
                MAP, "余烬谷", 4, 0, List.of(canvasView), groups, null)));

        CapturingRenderer capturing = new CapturingRenderer(options);
        MapShotSource shot = new MapShotSource(view, images, capturing, warmer, options);

        assertThat(shot.resolve(PlacePoint.keyOf(REF))).isEmpty();
        // 图标要带上，名字也要带上 —— 只画图标，读图的人认不出"这是哪个敌营"
        assertThat(capturing.context).singleElement().satisfies(m -> {
            assertThat(m.icon()).isEqualTo("Flame_Shrine.png");
            assertThat(m.label()).isEqualTo("火焰神龛·春之原野");
            assertThat(m.primary()).as("背景点不套高亮圈").isFalse();
        });
    }

    @Test
    @DisplayName("出图成功时不该去打扰预热")
    void noWarmWhenShotSucceeds() {
        assertThat(source.resolve(PlacePoint.keyOf(REF))).isPresent();

        verify(warmer, never()).warmAsync(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("总开关关掉：一张都不画")
    void disabledIsEmpty() {
        options.getShot().setEnabled(false);

        assertThat(source.resolve(PlacePoint.keyOf(REF))).isEmpty();
    }

    private static byte[] solidPng() throws IOException {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(210, 210, 210));
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
