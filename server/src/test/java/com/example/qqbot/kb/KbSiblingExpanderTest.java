package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同文档兄弟块补全 —— 长资料被切成多块时，命中一块要把整份资料一起给模型。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li><b>补出来的块追加在最后</b> —— 前 k 名的排名一个都不动（黄金集口径靠这个）；</li>
 *   <li>顺序按「（续 N）」/ id 自然序（{@code -2} 必须排在 {@code -10} 前面）；</li>
 *   <li>有预算，且**没被切块的资料一点不动**。</li>
 * </ol>
 */
class KbSiblingExpanderTest {

    private static KbCorpus.Entry entry(String id, String docId, String title, String text) {
        return new KbCorpus.Entry(id, docId, title, text, "https://w/" + id,
                List.of(), false, "", new float[]{1f, 0f});
    }

    private static KbRetriever.Hit hit(KbCorpus.Entry e) {
        return new KbRetriever.Hit(e, 0.9, "vector");
    }

    private static Sibling cfg(int maxBlocks, int maxChars) {
        Sibling s = new Sibling();
        s.setMaxBlocks(maxBlocks);
        s.setMaxChars(maxChars);
        return s;
    }

    private static List<String> ids(List<KbRetriever.Hit> hits) {
        return hits.stream().map(h -> h.entry().id().value()).toList();
    }

    @Test
    @DisplayName("★ 命中第一块 → 把同一份资料的其它块按顺序补在后面")
    void appendsSiblingsInOrder() {
        KbCorpus.Entry first = entry("flame-altar", "flame-altar", "灵火祭坛", "祭坛是复活点。");
        KbCorpus.Entry second = entry("flame-altar-2", "flame-altar", "灵火祭坛（续 2）", "升级需要树脂。");
        KbCorpus.Entry third = entry("flame-altar-3", "flame-altar", "灵火祭坛（续 3）", "可以改位置。");
        List<KbRetriever.Hit> hits = List.of(hit(first));

        List<KbRetriever.Hit> out = KbSiblingExpander.expand(hits,
                List.of(first, second, third), cfg(6, 5000));

        assertThat(ids(out)).containsExactly("flame-altar", "flame-altar-2", "flame-altar-3");
        assertThat(out.get(0).source()).as("原本命中的那条来源不动").isEqualTo("vector");
        assertThat(out.get(1).source()).isEqualTo("sibling");
        assertThat(out.get(1).score()).as("兄弟块沿用命中块的分数，不制造一个假分数").isEqualTo(0.9);
    }

    @Test
    @DisplayName("★ 命中第二块 → 第一块也要补上（不能只给后半段）")
    void fillsTheMissingFirstChunk() {
        KbCorpus.Entry first = entry("kiln", "kiln", "熔炉", "熔炉用来烧制。");
        KbCorpus.Entry second = entry("kiln-2", "kiln", "熔炉（续 2）", "升级材料是石头。");
        List<KbRetriever.Hit> hits = List.of(hit(second));

        List<KbRetriever.Hit> out = KbSiblingExpander.expand(hits, List.of(first, second), cfg(6, 5000));

        assertThat(ids(out)).containsExactly("kiln-2", "kiln");
    }

    @Test
    @DisplayName("id 自然序：-2 排在 -10 前面（字典序会排反）")
    void naturalOrderForUndecoratedChunks() {
        KbCorpus.Entry b1 = entry("page", "page", "页面", "一");
        KbCorpus.Entry b2 = entry("page-2", "page", "页面", "二");
        KbCorpus.Entry b10 = entry("page-10", "page", "页面", "十");
        List<KbRetriever.Hit> hits = List.of(hit(b1));

        List<KbRetriever.Hit> out = KbSiblingExpander.expand(hits,
                List.of(b1, b2, b10), cfg(6, 5000));

        assertThat(ids(out)).containsExactly("page", "page-2", "page-10");
    }

    @Test
    @DisplayName("预算：最多补几块")
    void respectsMaxBlocks() {
        KbCorpus.Entry b1 = entry("p", "p", "页面", "一");
        KbCorpus.Entry b2 = entry("p-2", "p", "页面（续 2）", "二");
        KbCorpus.Entry b3 = entry("p-3", "p", "页面（续 3）", "三");
        List<KbRetriever.Hit> hits = List.of(hit(b1));

        List<KbRetriever.Hit> out = KbSiblingExpander.expand(hits, List.of(b1, b2, b3), cfg(1, 5000));

        assertThat(ids(out)).containsExactly("p", "p-2");
    }

    @Test
    @DisplayName("预算：最多补多少字（至少补一块，否则补全等于没做）")
    void respectsMaxCharsButStillAddsOne() {
        KbCorpus.Entry b1 = entry("p", "p", "页面", "x".repeat(100));
        KbCorpus.Entry b2 = entry("p-2", "p", "页面（续 2）", "y".repeat(100));
        KbCorpus.Entry b3 = entry("p-3", "p", "页面（续 3）", "z".repeat(100));
        List<KbRetriever.Hit> hits = List.of(hit(b1));

        List<KbRetriever.Hit> out = KbSiblingExpander.expand(hits, List.of(b1, b2, b3), cfg(10, 150));

        assertThat(ids(out)).containsExactly("p", "p-2");
    }

    @Test
    @DisplayName("没被切块的资料一点不动")
    void singleBlockDocsUntouched() {
        KbCorpus.Entry a = entry("a", "doc-a", "甲", "正文");
        KbCorpus.Entry b = entry("b", "doc-b", "乙", "正文");
        List<KbRetriever.Hit> hits = List.of(hit(a), hit(b));

        assertThat(KbSiblingExpander.expand(hits, List.of(a, b), cfg(6, 5000))).isSameAs(hits);
    }

    @Test
    @DisplayName("已经命中的兄弟块不重复补")
    void doesNotDuplicateAlreadyHit() {
        KbCorpus.Entry b1 = entry("p", "p", "页面", "一");
        KbCorpus.Entry b2 = entry("p-2", "p", "页面（续 2）", "二");
        KbCorpus.Entry b3 = entry("p-3", "p", "页面（续 3）", "三");
        List<KbRetriever.Hit> hits = List.of(hit(b1), hit(b2));

        List<KbRetriever.Hit> out = KbSiblingExpander.expand(hits, List.of(b1, b2, b3), cfg(6, 5000));

        assertThat(ids(out)).containsExactly("p", "p-2", "p-3");
    }

    @Test
    @DisplayName("关掉开关 → 原样返回")
    void disabledReturnsSame() {
        KbCorpus.Entry b1 = entry("p", "p", "页面", "一");
        KbCorpus.Entry b2 = entry("p-2", "p", "页面（续 2）", "二");
        List<KbRetriever.Hit> hits = List.of(hit(b1));
        Sibling off = cfg(6, 5000);
        off.setEnabled(false);

        assertThat(KbSiblingExpander.expand(hits, List.of(b1, b2), off)).isSameAs(hits);
    }

    @Test
    @DisplayName("切块序号的解析")
    void chunkOrdinalParsing() {
        assertThat(KbSiblingExpander.chunkOrdinal("灵火祭坛")).isZero();
        assertThat(KbSiblingExpander.chunkOrdinal("灵火祭坛（续 2）")).isEqualTo(2);
        assertThat(KbSiblingExpander.chunkOrdinal("Page (续 10)")).isEqualTo(10);
    }

    @Test
    @DisplayName("自然序比较")
    void naturalCompareBasics() {
        assertThat(KbSiblingExpander.naturalCompare("page-2", "page-10")).isNegative();
        assertThat(KbSiblingExpander.naturalCompare("page-10", "page-2")).isPositive();
        assertThat(KbSiblingExpander.naturalCompare("page", "page-2")).isNegative();
        assertThat(KbSiblingExpander.naturalCompare("a", "a")).isZero();
    }
}
