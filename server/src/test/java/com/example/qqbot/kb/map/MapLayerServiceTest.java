package com.example.qqbot.kb.map;

import com.example.qqbot.persistence.KbMapLayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 默认图层的校验与"未配置 / 全关"的区分。
 *
 * <p>这一层最容易写错的地方不是读写，而是**语义**：
 * "这张地图没配置过"和"配置过但一个都不开"在前端走的是两条完全不同的路，
 * 前者要用内置经验值兜底，后者必须真的什么都不显示。所以这里重点盯这一对。
 */
class MapLayerServiceTest {

    private KbMapLayerRepository repo;
    private KbMapStore store;
    private MapLayerService service;

    @BeforeEach
    void setUp() {
        repo = mock(KbMapLayerRepository.class);
        store = mock(KbMapStore.class);
        when(repo.isAvailable()).thenReturn(true);
        when(store.groups("Embervale")).thenReturn(List.of(
                group("Enemy_Camp"), group("Flame_Shrine"), group("Fossil")));
        service = new MapLayerService(repo, store);
        service.init();
    }

    private static KbMapStore.Group group(String key) {
        return new KbMapStore.Group("Embervale", key, key, "", false);
    }

    @Test
    @DisplayName("没配置过的地图不出现在结果里，前端据此走经验值")
    void unconfiguredIsAbsent() {
        when(repo.all()).thenReturn(Map.of());
        assertThat(service.configured()).doesNotContainKey("Embervale");
    }

    @Test
    @DisplayName("配置过但全关：键在，值是空集合 —— 不能和未配置混为一谈")
    void configuredButEmptyIsPresent() {
        when(repo.all()).thenReturn(Map.of("Embervale", Set.of()));
        assertThat(service.configured()).containsKey("Embervale");
        assertThat(service.configured().get("Embervale")).isEmpty();
    }

    @Test
    @DisplayName("保存时按当前目录过滤掉已经不存在的分组键")
    void unknownKeysAreDropped() {
        service.save("Embervale", List.of("Enemy_Camp", "Gone_Group"));

        verify(repo).replace(eq("Embervale"), eq(List.of("Enemy_Camp")));
        verify(repo, never()).clear(any());
    }

    @Test
    @DisplayName("传 null 是恢复未配置，走 clear 而不是写空集合")
    void nullMeansReset() {
        service.save("Embervale", null);

        verify(repo).clear("Embervale");
        verify(repo, never()).replace(any(), any());
    }

    @Test
    @DisplayName("库不可用时读出来是空的，写操作也不会被放行到仓储")
    void unavailableStaysEmpty() {
        when(repo.isAvailable()).thenReturn(false);
        MapLayerService off = new MapLayerService(repo, store);
        off.init();

        assertThat(off.isAvailable()).isFalse();
        assertThat(off.configured()).isEmpty();
    }
}
