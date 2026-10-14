package com.example.qqbot.kb.map;

import com.example.qqbot.kb.MapOptions;
import com.example.qqbot.kb.PlaceDirectory;
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
import static org.mockito.Mockito.mock;
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

    private MapOptions options;
    private MapViewService view;
    private PlaceDirectory places;
    private MapImageCache images;
    private MapShotSource source;

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

        view = mock(MapViewService.class);
        when(view.catalog()).thenReturn(List.of(new MapViewService.MapView(MAP, "余烬谷", 1, 0,
                List.of(canvas), List.of(), List.of())));
        when(view.markers(MAP, false)).thenReturn(List.of(marker));
        when(view.canvasesOf(MAP)).thenReturn(List.of(canvas));

        PlacePoint target = new PlacePoint(MAP, CANVAS_PAGE, "春之原野远古尖塔", List.of(),
                1280, 1280, "landmark", "", true, 0, "wiki", REF);
        PlacePoint anchor = new PlacePoint(MAP, CANVAS_PAGE, "灵火祭坛", List.of(),
                1980, 1280, "landmark", "", true, 0, "wiki", "Map:Embervale/Main#FlameAltar");
        places = mock(PlaceDirectory.class);
        when(places.all()).thenReturn(List.of(target, anchor));
        when(places.worldSizes()).thenReturn(java.util.Map.of(MAP, new double[]{2560, 2560}));

        byte[] tile = solidPng();
        images = mock(MapImageCache.class);
        when(images.get(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(new MapImageCache.Image(tile, "image/png", true)));

        source = new MapShotSource(view, places, images, new MapShotRenderer(options), options);
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
