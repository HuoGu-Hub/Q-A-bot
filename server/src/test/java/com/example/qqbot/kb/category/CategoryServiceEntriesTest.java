package com.example.qqbot.kb.category;

import com.example.qqbot.kb.KbCorpus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分类穿透（{@link CategoryService#entries}）。
 *
 * <p>锁住三件事：
 * <ol>
 *   <li><b>口径必须和卡片上的数字一致</b> —— 面板上那个「N 条目」是
 *       {@link CategoryService#rawCounts()} 数出来的，穿透列表用的是同一份
 *       {@link KbCorpus#entries()}。两边一旦分叉，用户点进去就会看到对不上的条数。</li>
 *   <li>一个条目可以同时属于多个分类：按原始分类穿透按「包含」算，按大类穿透
 *       按大类映射算，所以大类的条数 ≥ 它下面任一原始分类的条数。</li>
 *   <li>二次搜索与分页：{@code q} 命中标题 / id / 文档 / 正文，{@code limit}
 *       只截当前页、{@code total} 仍是命中总数。</li>
 * </ol>
 */
class CategoryServiceEntriesTest {

    private static KbCorpus.Entry entry(String id, String title, String text, List<String> tags) {
        return new KbCorpus.Entry(id, title, title, text, "https://example/" + id,
                tags, false, "", new float[]{0f});
    }

    private static CategoryService service(KbCorpus corpus) {
        CategoryStore store = mock(CategoryStore.class);
        when(store.loadAll()).thenReturn(Map.of());
        return new CategoryService(store, new ObjectMapper(), corpus);
    }

    private static KbCorpus corpusOf(List<KbCorpus.Entry> list) {
        KbCorpus c = mock(KbCorpus.class);
        when(c.entries()).thenReturn(list);
        return c;
    }

    @Test
    @DisplayName("★ 按原始分类穿透：条数与 rawCounts 完全一致，且只给该分类下的条目")
    void entriesByRawMatchCounts() {
        KbCorpus corpus = corpusOf(List.of(
                entry("a-1", "Alpha", "正文 A", List.of("Weapons")),
                entry("a-2", "Beta", "正文 B", List.of("Weapons", "Bows")),
                entry("a-3", "Gamma", "正文 C", List.of("Armor"))
        ));
        CategoryService svc = service(corpus);

        assertThat(svc.rawCounts().get("Weapons")).isEqualTo(2);

        CategoryService.EntryPage page = svc.entries("Weapons", null, null, 100, 0);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(CategoryService.EntryItem::id)
                .containsExactly("a-1", "a-2");
    }

    @Test
    @DisplayName("★ 按大类穿透：含该大类下所有原始分类的条目，不会少于任一原始分类")
    void entriesByGroupCoversAllRaws() {
        // 用真实的自动规则问一次，避免把分组规则抄进测试里（规则改了测试跟着错）
        String group = KbGroups.autoGroup("Weapons");

        KbCorpus corpus = corpusOf(List.of(
                entry("a-1", "Alpha", "正文 A", List.of("Weapons")),
                entry("a-2", "Beta", "正文 B", List.of("Weapons", "Bows")),
                entry("a-3", "Gamma", "正文 C", List.of("Armor"))
        ));
        CategoryService svc = service(corpus);

        CategoryService.EntryPage page = svc.entries(null, group, null, 100, 0);
        assertThat(page.total()).isGreaterThanOrEqualTo(svc.entries("Weapons", null, null, 100, 0).total());
        assertThat(page.items()).isNotEmpty();
    }

    @Test
    @DisplayName("二次搜索命中标题/id/文档/正文；分页只截当前页，total 仍是命中总数")
    void searchAndPaging() {
        KbCorpus corpus = corpusOf(List.of(
                entry("b-1", "Alpha", "含关键词 shroud 的正文", List.of("Weapons")),
                entry("b-2", "Beta", "无关正文", List.of("Weapons")),
                entry("b-3", "Gamma shroud", "无关正文", List.of("Weapons"))
        ));
        CategoryService svc = service(corpus);

        CategoryService.EntryPage hit = svc.entries("Weapons", null, "shroud", 100, 0);
        assertThat(hit.total()).isEqualTo(2);
        assertThat(hit.items()).extracting(CategoryService.EntryItem::id)
                .containsExactly("b-1", "b-3");

        CategoryService.EntryPage page2 = svc.entries("Weapons", null, null, 1, 1);
        assertThat(page2.total()).isEqualTo(3);
        assertThat(page2.items()).hasSize(1);
    }

    @Test
    @DisplayName("摘要压平空白并截断，不把整块正文塞进列表")
    void snippetIsCompressed() {
        String longText = "第一行\n\n第二行 " + "填".repeat(300);
        KbCorpus corpus = corpusOf(List.of(entry("c-1", "C", longText, List.of("Misc"))));
        CategoryService svc = service(corpus);

        String snip = svc.entries("Misc", null, null, 10, 0).items().get(0).snippet();
        assertThat(snip).contains("第一行 第二行");
        assertThat(snip).endsWith("…");
        assertThat(snip.length()).isLessThanOrEqualTo(121);
    }

    @Test
    @DisplayName("没有范围参数时 = 全量；未知分类 = 空结果，不是报错")
    void scopeEdges() {
        KbCorpus corpus = corpusOf(List.of(
                entry("d-1", "D", "正文 D", List.of("Weapons")),
                entry("d-2", "E", "正文 E", List.of("Armor"))
        ));
        CategoryService svc = service(corpus);

        assertThat(svc.entries(null, null, null, 100, 0).total()).isEqualTo(2);
        assertThat(svc.entries("NoSuchCategory", null, null, 100, 0).total()).isZero();
    }
}
