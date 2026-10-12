package com.example.qqbot.kb.wiki;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.WikiImport;
import com.example.qqbot.persistence.KbWikiSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端「抓取」页的接口契约 —— 页面点了什么，后端就该做什么。
 *
 * <p>这里盯四件事：没配过时回显 yml 默认值、保存是整表替换、
 * 三种触发模式解析正确、分类菜单带缓存（不能每次打开页面都去数 40 个分类）。
 */
class KbWikiControllerTest {

    private KbWikiSourceStore store;
    private WikiApiClient client;
    private WikiImportService service;
    private KbWikiController controller;

    @BeforeEach
    void setUp() {
        store = mock(KbWikiSourceStore.class);
        client = mock(WikiApiClient.class);
        service = mock(WikiImportService.class);

        WikiImport cfg = new WikiImport();
        cfg.setPrefixes(List.of("Quests/"));
        cfg.setCategories(List.of("Gameplay", "Lore"));
        KbPolicy props = mock(KbPolicy.class);
        when(props.getWikiImport()).thenReturn(cfg);

        WikiImportService.Status idle = new WikiImportService.Status(
                "idle", "", 0, 0, "", "", Map.of(), List.of());
        when(service.status()).thenReturn(idle);
        when(service.start(any())).thenReturn(idle);

        controller = new KbWikiController(store, client, service, props);
    }

    @Test
    @DisplayName("★ 没保存过时回显 yml 默认值：页面一打开就能看见现在实际在拉什么")
    void fallsBackToYmlDefaults() {
        when(store.all()).thenReturn(List.of());

        Map<String, Object> out = controller.sources();

        assertThat(out.get("saved")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> defaults = (List<Map<String, Object>>) out.get("defaults");
        assertThat(defaults).extracting(m -> m.get("name"))
                .containsExactly("Quests/", "Gameplay", "Lore");
        assertThat(out.get("maxPagesPerSource")).isEqualTo(300);
    }

    @Test
    @DisplayName("★ 保存是整表替换：页面上勾选的结果就是完整范围")
    void saveReplacesWholeScope() {
        controller.saveSources(Map.of("items", List.of(
                Map.of("name", "Crafting Materials", "kind", "category", "enabled", true),
                Map.of("name", "Music", "kind", "category", "enabled", false))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KbWikiSourceRepository.Source>> cap = ArgumentCaptor.forClass(List.class);
        verify(store).replace(cap.capture());
        assertThat(cap.getValue()).hasSize(2);
        assertThat(cap.getValue().get(0).name()).isEqualTo("Crafting Materials");
        assertThat(cap.getValue().get(1).enabled()).isFalse();
    }

    @Test
    @DisplayName("★ 三种触发模式解析正确，purge 也走同一条路")
    void startParsesModes() {
        controller.start(Map.of("mode", "dry-run"));
        verify(service).start(WikiImportService.Mode.DRY_RUN);

        controller.start(Map.of("mode", "force"));
        verify(service).start(WikiImportService.Mode.FORCE);

        controller.start(Map.of("mode", "purge"));
        verify(service).start(WikiImportService.Mode.PURGE);

        controller.start(Map.of());
        verify(service).start(WikiImportService.Mode.INCREMENTAL);
    }

    @Test
    @DisplayName("★ 分类菜单带缓存：页面反复打开不能把 wiki 打疼")
    void catalogIsCached() {
        when(client.listAllCategories()).thenReturn(List.of(
                new WikiApiClient.CategoryInfo("Lore", 264),
                new WikiApiClient.CategoryInfo("Music", 95)));
        when(client.listCategoryMembers(anyString(), anyInt()))
                .thenReturn(new WikiApiClient.Collected(List.of("A", "B"), false, 1));

        Map<String, Object> first = controller.catalog();
        Map<String, Object> second = controller.catalog();

        assertThat(first.get("total")).isEqualTo(2);
        assertThat(second).isSameAs(first);
        verify(client, times(1)).listAllCategories();
        verify(client, times(2)).listCategoryMembers(anyString(), anyInt());
    }
}
