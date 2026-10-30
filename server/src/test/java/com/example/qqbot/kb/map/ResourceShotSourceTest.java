package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 资源层截图的验收：工具发的 {@code oreshot} 身份必须正好能被解析成一张点云图。 */
class ResourceShotSourceTest {

    private static final String MAP = "Embervale";
    private static final String LAYER = "ore:copper";
    private static final String CANVAS_PAGE = "Map:Embervale/Background";

    private MapOptions options;
    private MapResourceService resources;
    private MapImageCache images;
    private MapShotWarmer warmer;
    private MapViewService view;
    private MapViewService.CanvasView canvasView;
    private ResourceShotSource source;

    @BeforeEach
    void setUp() throws IOException {
        options = new MapOptions();
        options.getShot().setWidth(200);
        options.getShot().setHeight(125);

        canvasView = new MapViewService.CanvasView(CANVAS_PAGE, "余烬谷",
                new double[]{0, 2560}, new double[]{2560, 0}, new double[]{0, 0},
                new double[]{1280, 1280},
                List.of(new MapViewService.TileView("t00.png", 0, 0),
                        new MapViewService.TileView("t10.png", 1, 0),
                        new MapViewService.TileView("t01.png", 0, 1),
                        new MapViewService.TileView("t11.png", 1, 1)),
                "", "", 0.1, 2.0);
        view = mock(MapViewService.class);
        when(view.canvasesOf(MAP)).thenReturn(List.of(canvasView));

        MapResourceService.LayerView layer = new MapResourceService.LayerView(
                LAYER, "铜", "ore", "copper", 5, "#c97b3c", "point");
        MapResourceService.CategoryView category = new MapResourceService.CategoryView(
                "ore", "采矿", 5, List.of(layer));
        resources = mock(MapResourceService.class);
        when(resources.catalog(MAP)).thenReturn(new MapResourceService.CatalogView(
                true, "1.3.18", "2026-10-30", "", List.of(category)));
        when(resources.points(MAP, LAYER)).thenReturn(List.of(
                new MapResourceService.PointView(1200, 1200, "铜", 1),
                new MapResourceService.PointView(1210, 1200, "铜", 1),
                new MapResourceService.PointView(1220, 1200, "铜", 1),
                new MapResourceService.PointView(1205, 1215, "铜", 1),
                new MapResourceService.PointView(1215, 1215, "铜", 1)));

        byte[] tile = solidPng();
        images = mock(MapImageCache.class);
        when(images.get(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Optional.of(new MapImageCache.Image(tile, "image/png", true)));

        warmer = mock(MapShotWarmer.class);
        source = new ResourceShotSource(view, resources, images,
                new MapShotRenderer(options), warmer, options);
    }

    @Test
    @DisplayName("★ oreshot 身份能被解析成一张点云图")
    void resolvesIntoShot() {
        Optional<OutboundImage> got = source.resolve(MAP + "/" + LAYER + "/1");

        assertThat(got).isPresent();
        assertThat(got.get().bytesMime()).isEqualTo("image/jpeg");
        assertThat(got.get().byteSize()).isGreaterThan(0);
    }

    @Test
    @DisplayName("★ 工具发出的标记是合法标记，key 与解析器对得上")
    void emittedMarkerIsLegal() {
        String marker = "[[img:oreshot:" + MAP + "/" + LAYER + "/1]]";

        ImageMarkers.Parsed parsed = ImageMarkers.parse(marker);
        assertThat(parsed.keys()).containsExactly("oreshot:" + MAP + "/" + LAYER + "/1");
        assertThat(parsed.text()).isEmpty();
    }

    @Test
    @DisplayName("★ 图上带 wiki 地点：图标 + 名字，左上角有资源说明")
    void drawsWikiPlacesAsReference() {
        MapViewService.GroupView village = new MapViewService.GroupView(
                "Village", "村庄", "Village.png", false, true, "landmark", 1);
        when(view.catalog()).thenReturn(List.of(new MapViewService.MapView(
                MAP, "余烬谷", 1, 0, List.of(canvasView), List.of(village), null)));
        when(view.markers(MAP, true)).thenReturn(List.of(new MapViewService.MarkerView(
                "Map:Embervale/Main#TestVillage", MAP, "Map:Embervale/Main", CANVAS_PAGE,
                "Village", "测试村", "Test Village", "", "Village",
                1200, 1200, "", true, "landmark", 0, "", "wiki")));

        CapturingDotsRenderer capturing = new CapturingDotsRenderer(options);
        ResourceShotSource shot = new ResourceShotSource(view, resources, images, capturing, warmer, options);

        assertThat(shot.resolve(MAP + "/" + LAYER + "/1")).isEmpty();
        assertThat(capturing.context).singleElement().satisfies(m -> {
            assertThat(m.label()).isEqualTo("测试村");
            assertThat(m.icon()).isEqualTo("Village.png");
        });
        assertThat(capturing.caption).isEqualTo("铜矿 · 5 个点");
    }

    @Test
    @DisplayName("没有第 2 片、图层不存在、身份形状不对：都不出图，也不抛异常")
    void unknownKeysAreEmpty() {
        assertThat(source.resolve(MAP + "/" + LAYER + "/2")).isEmpty();
        assertThat(source.resolve(MAP + "/ore:none/1")).isEmpty();
        assertThat(source.resolve("bad")).isEmpty();
    }

    @Test
    @DisplayName("总开关关掉：一张都不画")
    void disabledIsEmpty() {
        options.getShot().setEnabled(false);

        assertThat(source.resolve(MAP + "/" + LAYER + "/1")).isEmpty();
    }

    /** 只记录"传进来什么"的渲染器，用来验收参照地点与说明文字 */
    private static final class CapturingDotsRenderer extends MapShotRenderer {

        List<MapShotRenderer.Mark> context = List.of();
        String caption = "";

        CapturingDotsRenderer(MapOptions options) {
            super(options);
        }

        @Override
        public Optional<byte[]> renderDots(MapProjection.Canvas canvas, List<MapViewService.TileView> tiles,
                                           String background, List<MapShotRenderer.Dot> dots,
                                           List<MapProjection.Point> focus,
                                           List<MapShotRenderer.Mark> context, String caption,
                                           TileFetcher fetcher, long deadlineAtMillis) {
            this.context = List.copyOf(context);
            this.caption = caption;
            return Optional.empty();
        }
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
