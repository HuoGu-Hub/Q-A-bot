package com.example.qqbot.kb.map;

import com.example.qqbot.persistence.KbMapPlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自定义点位的校验与转换，以及**底图变化后的待复核判定**。
 *
 * <p>校验放在服务层而不是控制器，所以这里能直接测规则本身，
 * 不需要起一个 Web 上下文。
 */
class MapPlaceServiceTest {

    /** 一张可渲染的画布 —— 取景窗口由参数给，用来模拟"wiki 哪天换了图" */
    private static KbMapStore.Canvas canvas(String map, String page, double tlX, double tlY,
                                           double brX, double brY) {
        return new KbMapStore.Canvas(map, page, "https://enshrouded.wiki.gg/extensions/DataMaps/schemas/v17.3.json",
                "", true, new double[]{tlX, tlY}, new double[]{brX, brY},
                new double[]{0, 0}, new double[]{1280, 1280}, "",
                "Map-Embervale.jpg", "", 0, 2, "t");
    }

    private static KbMapStore.Canvas embervale(double tlX, double tlY, double brX, double brY) {
        return canvas("Embervale", "Map:Embervale/Background", tlX, tlY, brX, brY);
    }

    private KbMapPlaceRepository repo;
    private KbMapStore mapStore;
    private MapPlaceService service;

    @BeforeEach
    void setUp() {
        repo = mock(KbMapPlaceRepository.class);
        mapStore = mock(KbMapStore.class);
        when(repo.isAvailable()).thenReturn(true);
        when(mapStore.canvases()).thenReturn(List.of(embervale(0, 10240, 10240, 0)));
        service = new MapPlaceService(repo, mapStore);
        service.init();
    }

    private static MapPlaceService.Place input(String name, String map, String page) {
        return new MapPlaceService.Place(0, map, page, 100, 200, name, List.of(), "resource",
                "备注", "", "", false, 0, true, "", true, "", "");
    }

    @Test
    @DisplayName("名字为空直接拒绝，不写库")
    void nameIsRequired() {
        assertThatThrownBy(() -> service.create(input("  ", "Embervale", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("地点名");
        verify(repo, never()).insert(any());
    }

    @Test
    @DisplayName("地图必填 —— 没有地图就无从知道坐标属于哪张画布")
    void mapIsRequired() {
        assertThatThrownBy(() -> service.create(input("铁矿", "", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("地图");
    }

    @Test
    @DisplayName("坐标必须是有限数：NaN 会让整张地图的点位计算全变成 NaN")
    void coordsMustBeFinite() {
        MapPlaceService.Place bad = new MapPlaceService.Place(0, "Embervale", "", Double.NaN, 0,
                "铁矿", List.of(), "resource", "", "", "", false, 0, true, "", true, "", "");
        assertThatThrownBy(() -> service.create(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("坐标");
    }

    @Test
    @DisplayName("别名里的逗号会被剔掉 —— 否则写进去什么就读不出来什么")
    void aliasesAreCommaSafe() {
        MapPlaceService.Place p = new MapPlaceService.Place(0, "Embervale", "", 1, 2, "铁矿",
                List.of("铁矿,富矿", "iron", "铁矿"), "resource", "", "", "", false, 0, true,
                "", true, "", "");
        when(repo.insert(any())).thenReturn(7L);
        when(repo.byId(7L)).thenReturn(new KbMapPlaceRepository.PlaceRow(7, "Embervale", "", 1, 2,
                "铁矿", "铁矿 富矿,iron", "resource", "", "", "", false, 0, true, "", "t", "t"));

        MapPlaceService.Place saved = service.create(p);

        assertThat(saved.aliases()).containsExactly("铁矿 富矿", "iron");
    }

    @Test
    @DisplayName("修改时必须带上画布 —— 否则点位会被悄悄挪到别的地图上")
    void updateRequiresPage() {
        // 先让 id 存在 —— 否则 update 会在校验之前就返回 null（那是另一条测试）
        when(repo.byId(3L)).thenReturn(new KbMapPlaceRepository.PlaceRow(3, "Embervale", "p", 1, 2,
                "铁矿", "", "resource", "", "", "", false, 0, true, "", "", ""));
        MapPlaceService.Place p = new MapPlaceService.Place(3, "Embervale", "", 1, 2, "铁矿",
                List.of(), "resource", "", "", "", false, 0, true, "", true, "", "");
        assertThatThrownBy(() -> service.update(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("画布");
    }

    @Test
    @DisplayName("修改不存在的 id 返回 null（控制器翻成 404）")
    void updateMissingReturnsNull() {
        when(repo.byId(anyLong())).thenReturn(null);
        MapPlaceService.Place p = new MapPlaceService.Place(99, "Embervale", "Map:Embervale/Background",
                1, 2, "铁矿", List.of(), "resource", "", "", "", false, 0, true, "", true, "", "");
        assertThat(service.update(p)).isNull();
    }

    @Test
    @DisplayName("认不出的分类归到 custom，而不是把未知值写进库")
    void unknownCategoryNormalized() {
        MapPlaceService.Place p = new MapPlaceService.Place(0, "Embervale", "", 1, 2, "铁矿",
                List.of(), "OreDeposit", "", "", "", false, 0, true, "", true, "", "");
        when(repo.insert(any())).thenReturn(1L);
        when(repo.byId(1L)).thenReturn(new KbMapPlaceRepository.PlaceRow(1, "Embervale", "", 1, 2,
                "铁矿", "", "custom", "", "", "", false, 0, true, "", "", ""));
        assertThat(service.create(p).category()).isEqualTo("custom");
    }

    @Test
    @DisplayName("优先级夹在 0..100 —— 界面上手输 9999 不该把它变成超级地标")
    void priorityClamped() {
        MapPlaceService.Place p = new MapPlaceService.Place(0, "Embervale", "", 1, 2, "铁矿",
                List.of(), "resource", "", "", "", true, 9999, true, "", true, "", "");
        when(repo.insert(any())).thenReturn(1L);
        when(repo.byId(1L)).thenReturn(new KbMapPlaceRepository.PlaceRow(1, "Embervale", "", 1, 2,
                "铁矿", "", "resource", "", "", "", true, 100, true, "", "", ""));
        assertThat(service.create(p).priority()).isEqualTo(100);
    }

    @Test
    @DisplayName("别名串的分割同时认中英文逗号")
    void aliasSplitting() {
        assertThat(MapPlaceService.splitAliases("a,b，c")).containsExactly("a", "b", "c");
        assertThat(MapPlaceService.splitAliases("")).isEmpty();
        assertThat(MapPlaceService.splitAliases(null)).isEmpty();
        assertThat(MapPlaceService.joinAliases(List.of("a", "b"))).isEqualTo("a,b");
    }

    @Test
    @DisplayName("存储不可用时新增抛 IllegalStateException（控制器翻成 503，而不是 400）")
    void unavailableStorage() {
        KbMapPlaceRepository down = mock(KbMapPlaceRepository.class);
        when(down.isAvailable()).thenReturn(false);
        MapPlaceService s = new MapPlaceService(down, mapStore);
        s.init();
        assertThatThrownBy(() -> s.create(input("铁矿", "Embervale", "")))
                .isInstanceOf(IllegalStateException.class);
    }

    /* ==================== 底图变化 → 待复核 ==================== */

    @Test
    @DisplayName("★ 底图取景窗口变了：这个点位进待复核，且说明白了变的是哪一块")
    void changedViewportLandsInReview() {
        String oldFp = CanvasFingerprint.of(embervale(0, 10240, 10240, 0));
        when(repo.byMap(null, false)).thenReturn(List.of(new KbMapPlaceRepository.PlaceRow(
                7, "Embervale", "", 3413, 4821, "我家基地", "", "custom", "", "", "",
                false, 0, true, oldFp, "t", "t")));
        // wiki 重新导了图，往外扩了一圈
        when(mapStore.canvases()).thenReturn(List.of(embervale(-1024, 11264, 11264, -1024)));

        List<MapPlaceService.Review> pending = service.pendingReview();

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).name()).isEqualTo("我家基地");
        assertThat(pending.get(0).reason()).contains("取景窗口");
    }

    @Test
    @DisplayName("底图没变：不进待复核")
    void unchangedCanvasStaysQuiet() {
        String fp = CanvasFingerprint.of(embervale(0, 10240, 10240, 0));
        when(repo.byMap(null, false)).thenReturn(List.of(new KbMapPlaceRepository.PlaceRow(
                7, "Embervale", "", 3413, 4821, "我家基地", "", "custom", "", "", "",
                false, 0, true, fp, "t", "t")));

        assertThat(service.pendingReview()).isEmpty();
    }

    @Test
    @DisplayName("★ 确认无误只改指纹、不动坐标 —— 确认动作不该有能力挪动点位")
    void confirmAnchorOnlyTouchesFingerprint() {
        String oldFp = CanvasFingerprint.of(embervale(0, 10240, 10240, 0));
        when(mapStore.canvases()).thenReturn(List.of(embervale(-1024, 11264, 11264, -1024)));
        when(repo.byId(7L)).thenReturn(new KbMapPlaceRepository.PlaceRow(
                7, "Embervale", "", 3413, 4821, "我家基地", "", "custom", "", "", "",
                false, 0, true, oldFp, "t", "t"));
        when(repo.updateFingerprint(anyLong(), any())).thenReturn(true);
        String now = CanvasFingerprint.of(embervale(-1024, 11264, 11264, -1024));

        assertThat(service.confirmAnchor(7)).isTrue();

        verify(repo).updateFingerprint(eq(7L), eq(now));
        verify(repo, never()).update(any());
    }

    @Test
    @DisplayName("画布都读不到时不做判断 —— 否则会一次性报出满屏待复核")
    void noCanvasesMeansNoVerdict() {
        when(mapStore.canvases()).thenReturn(List.of());
        when(repo.byMap(null, false)).thenReturn(List.of(new KbMapPlaceRepository.PlaceRow(
                7, "Embervale", "", 1, 2, "铁矿", "", "custom", "", "", "",
                false, 0, true, "whatever", "t", "t")));

        assertThat(service.pendingReview()).isEmpty();
    }
}
