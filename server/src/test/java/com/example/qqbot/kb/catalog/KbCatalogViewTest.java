package com.example.qqbot.kb.catalog;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.EntryId;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.block.KbDocImporter;
import com.example.qqbot.kb.category.CategoryService;
import com.example.qqbot.kb.category.CategoryStore;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.KbCategoryRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 词条读模型的筛选、分类与导出。
 *
 * <h2>词条 = 分块</h2>
 * 列表就是块表本身：{@code id / 标题 / 别名 / 分类}。视图预设：
 * <ul>
 *   <li>{@code all} 全部</li>
 *   <li>{@code aliased} 有别名 / {@code unaliased} 没别名</li>
 *   <li>{@code uncategorized} 未分类（标签认不出，或压根没标签）</li>
 * </ul>
 * 分类只认**清单里已有的名字** —— 标签不认识就归未分类，不做任何智能猜测。
 */
class KbCatalogViewTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private KbBlockStore blocks;
    private CategoryService categories;
    private KbCatalog catalog;

    @BeforeEach
    void setUp() {
        PersistenceProperties p = new PersistenceProperties();
        p.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(p);
        db.init();
        blocks = new KbBlockStore(new KbBlockRepository(new Jdbc(db)));
        blocks.init();

        CategoryStore catStore = new CategoryStore(new KbCategoryRepository(new Jdbc(db)));
        catStore.init();
        categories = new CategoryService(catStore, blocks);
        categories.add("武器", "武器");
        categories.add("家具", "家具");

        KbDocImporter importer = new KbDocImporter(blocks, mock(com.example.qqbot.kb.EmbeddingClient.class));
        catalog = new KbCatalog(blocks, categories, importer);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void block(String id, String title, String alias, String... tags) {
        blocks.upsert(new KbBlock(EntryId.of(id), "doc", title, "正文内容", "",
                List.of(tags), KbBlock.SRC_DOC, false, "2026-10-04T00:00:00Z", alias), null);
    }

    @Test
    @DisplayName("★ 「无别名」视图只抓没有别名的那些（标题括号派生出的别名算有别名）")
    void unaliasedView() {
        block("wiki-strength", "Strength", "");
        block("wiki-intelligence", "Intelligence", "");
        block("flame-altar", "灵火祭坛（Flame Altar）", "");

        List<String> unaliased = catalog.page("", "unaliased", "", 500, 0)
                .items().stream().map(KbCatalog.Entry::id).toList();

        assertThat(unaliased)
                .as("标题里带（English）的那条自动有了别名，不算无别名")
                .containsExactlyInAnyOrder("wiki-strength", "wiki-intelligence");
        assertThat(catalog.counts().unaliased()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 有别名视图只抓真的写了别名的那些")
    void aliasedView() {
        block("a", "灵火祭坛", "灵火圣坛");
        block("b", "熔炉", "");

        List<String> aliased = catalog.page("", "aliased", "", 500, 0)
                .items().stream().map(KbCatalog.Entry::id).toList();

        assertThat(aliased).containsExactly("a");
        assertThat(catalog.counts().aliased()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 未分类 = 标签认不出（未登记）或压根没标签")
    void uncategorizedView() {
        block("a", "灵火祭坛", "", "武器");
        block("b", "熔炉", "", "没登记过的标签");
        block("c", "无名块", "");

        List<String> uncategorized = catalog.page("", "uncategorized", "", 500, 0)
                .items().stream().map(KbCatalog.Entry::id).toList();

        assertThat(uncategorized).containsExactlyInAnyOrder("b", "c");
        assertThat(catalog.counts().uncategorized()).isEqualTo(2);
    }

    @Test
    @DisplayName("按分类筛选：只认清单里已有的分类名")
    void filterByCategory() {
        block("a", "剑", "", "武器");
        block("b", "桌子", "", "家具");
        block("c", "未知", "", "别的");

        assertThat(catalog.page("", "all", "武器", 500, 0).items())
                .extracting(KbCatalog.Entry::id).containsExactly("a");
        assertThat(catalog.page("", "all", CategoryService.UNCATEGORIZED, 500, 0).items())
                .extracting(KbCatalog.Entry::id).containsExactly("c");
        assertThat(categories.stats()).as("两个分类各自只有一条")
                .extracting(CategoryService.Stat::count).containsExactly(1, 1);
    }

    @Test
    @DisplayName("★ 导出与列表**用同一套筛选** —— 列表里看到多少条，导出就是多少条")
    void exportAndListShareTheSameFilter() {
        block("a", "Sword", "", "武器");
        block("b", "Table", "", "家具");
        block("c", "Shield", "", "武器");

        int listTotal = catalog.page("", "all", "武器", 500, 0).total();
        long exportRows = catalog.exportTsv("", "all", "武器").lines()
                .filter(l -> !l.isBlank() && l.charAt(0) != '#').count();
        assertThat(exportRows).as("列表 %d 条，导出 %d 条 —— 必须相等", listTotal, exportRows)
                .isEqualTo(listTotal);
    }

    @Test
    @DisplayName("★ TSV 导出：无 BOM、含分隔符的字段被引号包住、行数与列表一致")
    void tsvExportIsWellFormed() {
        block("a", "Strength", "");
        // 标题里故意放**制表符**（TSV 的分隔符）—— 不转义就会被切错列，而且不报错
        block("b", "Tab\tInside", "", "武器");

        String tsv = catalog.exportTsv("", "all", "");
        assertThat(tsv.charAt(0)).as("TSV 不该带 BOM").isNotEqualTo('\uFEFF');
        assertThat(tsv)
                .as("含制表符的字段必须被双引号包住，读回来才是原样")
                .contains("\"Tab\tInside\"");

        long rows = tsv.lines().filter(l -> !l.isBlank() && l.charAt(0) != '#').count();
        assertThat(rows)
                .as("数据行数必须等于列表条数")
                .isEqualTo(catalog.page("", "all", "", 500, 0).total());
    }

    @Test
    @DisplayName("别名表导入：只按 id 覆盖别名，找不到的 id 计入 skipped")
    void importAliasTable() {
        block("a", "灵火祭坛", "");

        KbCatalog.ImportResult r = catalog.importTable(String.join("\n",
                "id\t标题\t别名\t分类",
                "a\t灵火祭坛\t灵火圣坛, 火焰祭坛\t武器",
                "missing\t不存在\tx\t武器"));

        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(catalog.get("a").alias()).as("逗号统一成顿号").isEqualTo("灵火圣坛、火焰祭坛");
    }
}
