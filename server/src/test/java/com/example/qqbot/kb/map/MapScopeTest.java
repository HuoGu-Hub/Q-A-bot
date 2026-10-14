package com.example.qqbot.kb.map;

import com.example.qqbot.persistence.KbMapActiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "当前用哪几张地图"。
 *
 * <p>这套机制存在的理由是：这个判断**会变**（游戏更新后 wiki 另起一页，名字也变），
 * 所以它不能固化进配置文件 —— 固化之后过期时没有任何东西会告诉你。
 * 这里要守住的是三件事：
 * <ol>
 *   <li>没人在后台选过时能自动兜底（选点数最多的那张），而且兜底**不落库** ——
 *       它是启发式，不该假装成人类决定；</li>
 *   <li>有人选过就完全听人的，包括把当前世界停用；</li>
 *   <li>候选列表要给出判断材料（点数、wiki 最后改动时间），而不是让系统替人裁决。</li>
 * </ol>
 */
class MapScopeTest {

    private KbMapStore store;
    private KbMapActiveRepository repo;
    private MapScope scope;

    @BeforeEach
    void setUp() {
        store = mock(KbMapStore.class);
        repo = mock(KbMapActiveRepository.class);
        when(repo.isAvailable()).thenReturn(true);
        scope = new MapScope(store, repo);
        scope.init();
    }

    @Test
    @DisplayName("★ 后台还没选过：兜底选点数最多的那张，而且**不写库**")
    void autoPicksTheRichestMapWithoutPersisting() {
        when(repo.active()).thenReturn(List.of());
        when(store.markerCounts()).thenReturn(Map.of("Embervale", 1064, "EarlyAccess", 585));

        assertThat(scope.activeMaps()).containsExactly("Embervale");
        assertThat(scope.isActive("Embervale")).isTrue();
        assertThat(scope.isActive("EarlyAccess")).isFalse();
        verify(repo, never()).set(any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("★ 有人选过就完全听人的 —— 连「把当前世界停用」也照做")
    void explicitChoiceWins() {
        when(repo.active()).thenReturn(List.of("Blackmire"));
        when(store.markerCounts()).thenReturn(Map.of("Embervale", 1064, "Blackmire", 24));

        assertThat(scope.activeMaps()).containsExactly("Blackmire");
        assertThat(scope.isActive("Embervale")).isFalse();
        assertThat(scope.isActive("Blackmire")).isTrue();
    }

    @Test
    @DisplayName("★ 候选列表要把判断材料摆全：点数、wiki 最后改动时间、是不是自动兜底的")
    void candidatesExposeDecisionMaterial() {
        when(repo.active()).thenReturn(List.of());
        when(repo.all()).thenReturn(Map.of());
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("EarlyAccess", 585);
        counts.put("Embervale", 1064);
        when(store.markerCounts()).thenReturn(counts);
        when(store.latestPageUpdate()).thenReturn(Map.of("EarlyAccess", "2026-01-01T00:00:00Z"));

        List<MapScope.Candidate> list = scope.candidates();

        // 按点数从多到少 —— 通常当前世界就是第一张
        assertThat(list).extracting(MapScope.Candidate::map).containsExactly("Embervale", "EarlyAccess");
        assertThat(list.get(0).markers()).isEqualTo(1064);
        assertThat(list.get(0).active()).as("自动兜底选中的那张应当显示为启用").isTrue();
        assertThat(list.get(0).auto()).as("但它标着 auto —— 不是人选的").isTrue();
        assertThat(list.get(1).active()).isFalse();
        assertThat(list.get(1).lastChanged()).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("人选过之后不再标 auto")
    void explicitChoiceIsNotAuto() {
        when(repo.active()).thenReturn(List.of("Blackmire"));
        when(repo.all()).thenReturn(Map.of("Blackmire", true));
        when(store.markerCounts()).thenReturn(Map.of("Embervale", 1064, "Blackmire", 24));

        List<MapScope.Candidate> list = scope.candidates();

        assertThat(list.get(0).map()).isEqualTo("Embervale");
        assertThat(list.get(0).active()).isFalse();
        assertThat(list.get(1).map()).isEqualTo("Blackmire");
        assertThat(list.get(1).auto()).as("这是有人选过的").isFalse();
    }

    @Test
    @DisplayName("切换写进存储")
    void setActiveDelegatesToStore() {
        when(repo.active()).thenReturn(List.of("Embervale"));
        when(repo.set(any(), anyBoolean(), any())).thenReturn(true);

        assertThat(scope.setActive("Blackmire", true)).isTrue();

        verify(repo).set(eq("Blackmire"), eq(true), any());
    }

    @Test
    @DisplayName("存储读挂了也别把地图页拖垮：退回自动兜底")
    void storageFailureFallsBackToAuto() {
        when(repo.active()).thenThrow(new RuntimeException("boom"));
        when(store.markerCounts()).thenReturn(Map.of("Embervale", 10));

        assertThat(scope.activeMaps()).containsExactly("Embervale");
    }
}
