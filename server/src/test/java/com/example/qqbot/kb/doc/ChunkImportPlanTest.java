package com.example.qqbot.kb.doc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入计划（"这次会改动多少条"）的验收测试。
 *
 * <p>钉的就是用户 2026-09-28 定下的那条规则：
 * **文件里的 id 存在就覆盖、不存在就新增；文件里没出现的一个都不动。**
 */
class ChunkImportPlanTest {

    private static ChunkMarkup.Block block(String key, String body) {
        return new ChunkMarkup.Block(key, key, "标题" + key, body, 1);
    }

    /** 库里 1..9 九个块 */
    private static Map<String, ChunkMarkup.Block> library1to9() {
        Map<String, ChunkMarkup.Block> lib = new LinkedHashMap<>();
        for (int i = 1; i <= 9; i++) {
            lib.put(String.valueOf(i), block(String.valueOf(i), "旧正文" + i));
        }
        return lib;
    }

    @Test
    @DisplayName("★ 用户举的例子：库 1..9，文档只给 3/5/7 → 只动这三个，其余六个一字不动")
    void onlyTouchesMentionedBlocks() {
        Map<String, ChunkMarkup.Block> lib = library1to9();
        List<ChunkMarkup.Block> incoming = List.of(
                block("3", "新正文3"),
                block("5", "旧正文5"),   // 内容没变
                block("7", "新正文7"));

        ChunkImportPlan<ChunkMarkup.Block> p = ChunkImportPlan.of(lib, incoming);

        assertThat(p.added()).isEmpty();
        assertThat(p.updated()).extracting(ChunkMarkup.Block::key).containsExactly("3", "7");
        assertThat(p.unchanged()).extracting(ChunkMarkup.Block::key).containsExactly("5");
        assertThat(p.untouchedExisting()).as("1,2,4,6,8,9 六块没被碰").isEqualTo(6);
        assertThat(p.changed()).isEqualTo(2);
    }

    @Test
    @DisplayName("id 在库里不存在 → 新增")
    void unknownIdIsAdded() {
        ChunkImportPlan<ChunkMarkup.Block> p = ChunkImportPlan.of(library1to9(),
                List.of(block("100", "全新的一块"), block("101", "另一块")));

        assertThat(p.added()).extracting(ChunkMarkup.Block::key).containsExactly("100", "101");
        assertThat(p.updated()).isEmpty();
        assertThat(p.untouchedExisting()).isEqualTo(9);
    }

    @Test
    @DisplayName("库是空的 → 全是新增")
    void emptyLibraryMeansAllNew() {
        ChunkImportPlan<ChunkMarkup.Block> p = ChunkImportPlan.of(Map.of(), List.of(block("a", "x"), block("b", "y")));

        assertThat(p.added()).hasSize(2);
        assertThat(p.untouchedExisting()).isZero();
    }

    @Test
    @DisplayName("★ 幂等：同一份文件导第二次 → 全部「无变化」，改动 0 条")
    void reimportIsIdempotent() {
        List<ChunkMarkup.Block> incoming = List.of(block("3", "新正文3"), block("7", "新正文7"));
        Map<String, ChunkMarkup.Block> lib = library1to9();

        ChunkImportPlan first = ChunkImportPlan.of(lib, incoming);
        assertThat(first.changed()).isEqualTo(2);

        // 把第一次的结果落库（这里手工模拟），再导一次同样的文件
        Map<String, ChunkMarkup.Block> afterFirst = new LinkedHashMap<>(lib);
        afterFirst.put("3", block("3", "新正文3"));
        afterFirst.put("7", block("7", "新正文7"));

        ChunkImportPlan second = ChunkImportPlan.of(afterFirst, incoming);
        assertThat(second.changed()).as("第二次应该什么都不改").isZero();
        assertThat(second.unchanged()).hasSize(2);
    }

    @Test
    @DisplayName("正文只差首尾空白 → 视为没变（不然每次导入都报「改了 N 条」）")
    void surroundingWhitespaceIsNotAChange() {
        Map<String, ChunkMarkup.Block> lib = Map.of("a", block("a", "正文"));
        ChunkImportPlan<ChunkMarkup.Block> p = ChunkImportPlan.of(lib, List.of(block("a", "  正文  ")));

        assertThat(p.changed()).isZero();
        assertThat(p.unchanged()).hasSize(1);
    }

    @Test
    @DisplayName("★ 同一块换了 id（标题相同、id 不在库里）→ 提醒，否则会静默变成两块重复的")
    void warnsWhenSameTitleDifferentId() {
        Map<String, ChunkMarkup.Block> lib = Map.of(
                "flame-altar-0", block("flame-altar-0", "祭坛是复活点"));
        List<ChunkMarkup.Block> incoming = List.of(
                new ChunkMarkup.Block("flamealtar0", "flamealtar0", "标题flame-altar-0", "祭坛是复活点", 1));

        ChunkImportPlan<ChunkMarkup.Block> p = ChunkImportPlan.of(lib, incoming);

        assertThat(p.added()).hasSize(1);
        assertThat(p.warnings()).isNotEmpty();
        assertThat(p.warnings().get(0)).contains("重复的两块");
    }

    @Test
    @DisplayName("没写 id 的块用标题当 key，同样按覆盖/新增处理")
    void titleWorksAsKeyWhenNoId() {
        Map<String, ChunkMarkup.Block> lib = Map.of(
                "熔炉", new ChunkMarkup.Block("熔炉", null, "熔炉", "旧的", 1));
        List<ChunkMarkup.Block> incoming = List.of(
                new ChunkMarkup.Block("熔炉", null, "熔炉", "新的", 1));

        ChunkImportPlan<ChunkMarkup.Block> p = ChunkImportPlan.of(lib, incoming);

        assertThat(p.updated()).hasSize(1);
        assertThat(p.added()).isEmpty();
    }
}
