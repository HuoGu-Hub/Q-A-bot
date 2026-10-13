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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自定义点位的校验与转换。
 *
 * <p>校验放在服务层而不是控制器，所以这里能直接测规则本身，
 * 不需要起一个 Web 上下文。
 */
class MapPlaceServiceTest {

    private KbMapPlaceRepository repo;
    private MapPlaceService service;

    @BeforeEach
    void setUp() {
        repo = mock(KbMapPlaceRepository.class);
        when(repo.isAvailable()).thenReturn(true);
        service = new MapPlaceService(repo);
        service.init();
    }

    private static MapPlaceService.Place input(String name, String map, String page) {
        return new MapPlaceService.Place(0, map, page, 100, 200, name, List.of(), "resource",
                "备注", "", "", false, 0, true, "", "");
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
                "铁矿", List.of(), "resource", "", "", "", false, 0, true, "", "");
        assertThatThrownBy(() -> service.create(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("坐标");
    }

    @Test
    @DisplayName("别名里的逗号会被剔掉 —— 否则写进去什么就读不出来什么")
    void aliasesAreCommaSafe() {
        MapPlaceService.Place p = new MapPlaceService.Place(0, "Embervale", "", 1, 2, "铁矿",
                List.of("铁矿,富矿", "iron", "铁矿"), "resource", "", "", "", false, 0, true, "", "");
        when(repo.insert(any())).thenReturn(7L);
        when(repo.byId(7L)).thenReturn(new KbMapPlaceRepository.PlaceRow(7, "Embervale", "", 1, 2,
                "铁矿", "铁矿 富矿,iron", "resource", "", "", "", false, 0, true, "t", "t"));

        MapPlaceService.Place saved = service.create(p);

        assertThat(saved.aliases()).containsExactly("铁矿 富矿", "iron");
    }

    @Test
    @DisplayName("修改时必须带上画布 —— 否则点位会被悄悄挪到别的地图上")
    void updateRequiresPage() {
        // 先让 id 存在 —— 否则 update 会在校验之前就返回 null（那是另一条测试）
        when(repo.byId(3L)).thenReturn(new KbMapPlaceRepository.PlaceRow(3, "Embervale", "p", 1, 2,
                "铁矿", "", "resource", "", "", "", false, 0, true, "", ""));
        MapPlaceService.Place p = new MapPlaceService.Place(3, "Embervale", "", 1, 2, "铁矿",
                List.of(), "resource", "", "", "", false, 0, true, "", "");
        assertThatThrownBy(() -> service.update(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("画布");
    }

    @Test
    @DisplayName("修改不存在的 id 返回 null（控制器翻成 404）")
    void updateMissingReturnsNull() {
        when(repo.byId(anyLong())).thenReturn(null);
        MapPlaceService.Place p = new MapPlaceService.Place(99, "Embervale", "Map:Embervale/Background",
                1, 2, "铁矿", List.of(), "resource", "", "", "", false, 0, true, "", "");
        assertThat(service.update(p)).isNull();
    }

    @Test
    @DisplayName("认不出的分类归到 custom，而不是把未知值写进库")
    void unknownCategoryNormalized() {
        MapPlaceService.Place p = new MapPlaceService.Place(0, "Embervale", "", 1, 2, "铁矿",
                List.of(), "OreDeposit", "", "", "", false, 0, true, "", "");
        when(repo.insert(any())).thenReturn(1L);
        when(repo.byId(1L)).thenReturn(new KbMapPlaceRepository.PlaceRow(1, "Embervale", "", 1, 2,
                "铁矿", "", "custom", "", "", "", false, 0, true, "", ""));
        assertThat(service.create(p).category()).isEqualTo("custom");
    }

    @Test
    @DisplayName("优先级夹在 0..100 —— 界面上手输 9999 不该把它变成超级地标")
    void priorityClamped() {
        MapPlaceService.Place p = new MapPlaceService.Place(0, "Embervale", "", 1, 2, "铁矿",
                List.of(), "resource", "", "", "", true, 9999, true, "", "");
        when(repo.insert(any())).thenReturn(1L);
        when(repo.byId(1L)).thenReturn(new KbMapPlaceRepository.PlaceRow(1, "Embervale", "", 1, 2,
                "铁矿", "", "resource", "", "", "", true, 100, true, "", ""));
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
        MapPlaceService s = new MapPlaceService(down);
        s.init();
        assertThatThrownBy(() -> s.create(input("铁矿", "Embervale", "")))
                .isInstanceOf(IllegalStateException.class);
    }
}
