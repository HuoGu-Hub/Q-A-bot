package com.example.qqbot.kb.wiki;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.MapSync;
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
 * 三种触发模式解析正确、分类菜单带缓存（页数走批量接口，示例页名只给内容类取）。
 */
class KbWikiControllerTest {

    private KbWikiSourceStore store;
    private WikiApiClient client;
    private WikiImportService service;
    private com.example.qqbot.kb.block.KbBlockStore blockStore;
    private KbWikiPageStore pageStore;
    private KbWikiController controller;

    @BeforeEach
    void setUp() {
        store = mock(KbWikiSourceStore.class);
        client = mock(WikiApiClient.class);
        service = mock(WikiImportService.class);
        blockStore = mock(com.example.qqbot.kb.block.KbBlockStore.class);
        pageStore = mock(KbWikiPageStore.class);

        WikiImport cfg = new WikiImport();
        cfg.setPrefixes(List.of("Quests/"));
        cfg.setCategories(List.of("Gameplay", "Lore"));
        // 显式给一个上限，验"接口把配置原样回显"（默认是 0 = 不限，见 WikiImport）
        cfg.setMaxPagesPerSource(500);
        KbPolicy props = mock(KbPolicy.class);
        when(props.getWikiImport()).thenReturn(cfg);
        // 页面上的"点开看"链接要用站点地址（api-base 去掉 /api.php）
        MapSync mapSync = new MapSync();
        mapSync.setApiBase("https://enshrouded.wiki.gg/api.php");
        when(props.getMapSync()).thenReturn(mapSync);

        WikiImportService.Status idle = new WikiImportService.Status(
                "idle", "", 0, 0, "", "", Map.of(), List.of());
        when(service.status()).thenReturn(idle);
        when(service.start(any())).thenReturn(idle);

        controller = new KbWikiController(store, client, service, props, blockStore, pageStore);
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
        assertThat(out.get("maxPagesPerSource")).as("原样回显配置里的上限").isEqualTo(500);
        assertThat(out.get("wikiBase")).as("页面用它拼'点开看'的链接").isEqualTo("https://enshrouded.wiki.gg");
    }

    @Test
    @DisplayName("★ 来源要能看出入库了没有：按 docId 数生效块，再用同步时间区分'没拉过'")
    void reportsCoveragePerSource() {
        when(store.all()).thenReturn(List.of(
                new KbWikiSourceRepository.Source("Melee Armor", "category", true, "")));
        when(blockStore.activeCountByDoc(WikiArticleImporter.SRC_WIKI)).thenReturn(Map.of(
                "wiki·Melee Armor", 112,
                "wiki·Quests/", 149,
                "other·X", 5));
        when(pageStore.lastSyncBySource()).thenReturn(Map.of("Lore", "2026-10-17T02:00:00Z"));

        @SuppressWarnings("unchecked")
        Map<String, Object> cov = (Map<String, Object>) controller.sources().get("coverage");
        @SuppressWarnings("unchecked")
        Map<String, Object> melee = (Map<String, Object>) cov.get("Melee Armor");
        assertThat(melee.get("blocks")).as("docId 去掉 wiki· 前缀后的块数").isEqualTo(112);
        @SuppressWarnings("unchecked")
        Map<String, Object> lore = (Map<String, Object>) cov.get("Lore");
        assertThat(lore.get("blocks")).as("没入库的来源算 0，不是缺失").isEqualTo(0);
        assertThat(lore.get("lastSync")).as("拉过但清洗后没正文 → 靠同步时间区分").isEqualTo("2026-10-17T02:00:00Z");
        assertThat(cov).as("别的 source 的 docId 不算进来").doesNotContainKey("other·X");
    }

    @Test
    @DisplayName("★ 保存是整表替换：页面上勾选的结果就是完整范围")
    void saveReplacesWholeScope() {
        controller.saveSources(new KbWikiController.SourcesRequest(List.of(
                new KbWikiController.SourceItem("Crafting Materials", "category", true),
                new KbWikiController.SourceItem("Music", "category", false))));

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
        controller.start(new KbWikiController.StartImportRequest("dry-run"));
        verify(service).start(WikiImportService.Mode.DRY_RUN);

        controller.start(new KbWikiController.StartImportRequest("force"));
        verify(service).start(WikiImportService.Mode.FORCE);

        controller.start(new KbWikiController.StartImportRequest("purge"));
        verify(service).start(WikiImportService.Mode.PURGE);

        controller.start(new KbWikiController.StartImportRequest(null));
        verify(service).start(WikiImportService.Mode.INCREMENTAL);
    }

    @Test
    @DisplayName("★ 垃圾分类必须被点名：英文名看不出性质，页面靠中文建议拦住它们")
    void advisesAgainstJunkCategories() {
        when(client.listAllCategories()).thenReturn(List.of(
                new WikiApiClient.CategoryInfo("Pages with navboxes", 1860),
                new WikiApiClient.CategoryInfo("Wake Of Water Update", 446),
                new WikiApiClient.CategoryInfo("Item catalogs", 71),
                new WikiApiClient.CategoryInfo("Music", 95)));
        when(client.listCategoryMembers(anyString(), anyInt()))
                .thenReturn(new WikiApiClient.Collected(List.of("X"), false, 1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) controller.catalog().get("items");
        Map<String, String> klass = new java.util.HashMap<>();
        for (Map<String, Object> m : items) {
            klass.put(String.valueOf(m.get("name")), String.valueOf(m.get("klass")));
        }
        assertThat(klass.get("Pages with navboxes")).as("维护清单").isEqualTo("maintenance");
        assertThat(klass.get("Wake Of Water Update")).as("更新日期类").isEqualTo("update");
        assertThat(klass.get("Item catalogs")).as("索引类").isEqualTo("index");
        assertThat(klass.get("Music")).as("空类").isEqualTo("index");
    }

    @Test
    @DisplayName("★ 分类菜单带缓存；页数走批量接口，示例页名只给内容类取")
    void catalogIsCached() {
        when(client.listAllCategories()).thenReturn(List.of(
                new WikiApiClient.CategoryInfo("Lore", 264),
                new WikiApiClient.CategoryInfo("Music", 95)));
        // ★ 页数来自批量接口（一次问 50 个分类）—— 不再"只数前 40 个、其余留空"
        when(client.fetchCategoryPages(any())).thenReturn(Map.of("Lore", 264, "Music", 95));
        when(client.listCategoryMembers(anyString(), anyInt()))
                .thenReturn(new WikiApiClient.Collected(List.of("A", "B"), false, 1));

        Map<String, Object> first = controller.catalog();
        Map<String, Object> second = controller.catalog();

        assertThat(first.get("total")).isEqualTo(2);
        assertThat(second).isSameAs(first);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) first.get("items");
        assertThat(items.get(0).get("name")).as("按正文页数降序").isEqualTo("Lore");
        assertThat(items.get(0).get("pages")).as("★ 每个分类都要有准确页数").isEqualTo(264);
        assertThat(items.get(1).get("pages")).as("非内容类也有页数").isEqualTo(95);
        assertThat(items.get(0).get("klass")).isEqualTo("content");
        assertThat(items.get(0).get("advice")).isEqualTo("内容类：可以选");
        @SuppressWarnings("unchecked")
        List<String> samples = (List<String>) items.get(0).get("samples");
        assertThat(samples).as("示例页名，让英文分类名能看懂").containsExactly("A", "B");
        verify(client, times(1)).listAllCategories();
        verify(client, times(1)).fetchCategoryPages(any());
        // 示例页名只给内容类取：Music 是索引类，页面上没有「选它」，取来没人看
        verify(client, times(1)).listCategoryMembers(anyString(), anyInt());
    }
}
