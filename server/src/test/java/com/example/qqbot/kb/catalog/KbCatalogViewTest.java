package com.example.qqbot.kb.catalog;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.block.KbDocImporter;
import com.example.qqbot.kb.category.CategoryService;
import com.example.qqbot.kb.term.KbTermService;
import com.example.qqbot.kb.term.KbTermStore;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.KbTermRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.example.qqbot.kb.EntryId;

/**
 * 目录读模型的筛选与导出 —— 「无中文名」筛选器曾经**一条都抓不到**。
 *
 * <h2>真实故障（2026-10-04 实测）</h2>
 * {@code matchesView} 里原来写的是 {@code case "unnamed" -> t.zh().isEmpty()}。
 * 但 {@link KbCatalog#buildAll} 有一句回退：
 * {@code zh = name[0].isEmpty() ? b.title() : name[0]} —— **词条没中文名时退回用块标题**。
 * 自动导入的英文页（技能/机制/属性/收集品笔记）标题就是英文，
 * 于是它们的 {@code zh} 是 {@code "Strength"} 这种**非空英文** → {@code isEmpty()} 恒为 false。
 *
 * <p>结果：生产库里几百条该被「无中文名」抓到的**一条都抓不到**，
 * 管理员在后台想补译名却找不到入口。{@code main} 视图的
 * 「挡掉既没正文又没中文名的空页面」也是同一个原因失效。
 *
 * <p>修法：按**有没有汉字**判定，不依赖回退行为，见 {@link KbCatalogViews}。
 *
 * <p>本测试原先叫 {@code KbTermServiceViewTest}：那时目录读模型住在名字层服务里。
 * 它现在归组合层，测试跟着搬 —— 断言对象没变，只是归属地变了。
 */
class KbCatalogViewTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private KbBlockStore blocks;
    private KbCatalog catalog;

    @BeforeEach
    void setUp() {
        PersistenceProperties p = new PersistenceProperties();
        p.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(p);
        db.init();
        blocks = new KbBlockStore(new KbBlockRepository(new Jdbc(db)));
        blocks.init();
        KbBlockIndex index = new KbBlockIndex(blocks);
        index.reload();
        KbTermStore terms = new KbTermStore(new KbTermRepository(new Jdbc(db)), index);
        terms.init();
        // 读模型测试用不到导入编排那两半，装上真的但空转的实例就好：
        // 用 null 会掩盖"构造签名变了"，用 mock 拿不到真名的类型检查。
        KbDocImporter importer = new KbDocImporter(blocks, mock(EmbeddingClient.class));
        KbTermService termService = new KbTermService(terms, mock(ApplicationEventPublisher.class));
        catalog = new KbCatalog(blocks, terms, mock(CategoryService.class), importer, termService);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void block(String id, String title) {
        blocks.upsert(new KbBlock(EntryId.of(id), "doc", title, "正文内容", "", List.of(),
                KbBlock.SRC_DOC, false, "2026-10-04T00:00:00Z"), new float[]{0.1f, 0.2f, 0.3f});
    }

    @Test
    @DisplayName("★ 英文标题的块必须出现在「无中文名」里（原来一条都抓不到）")
    void englishTitledBlockShowsUpAsUnnamed() {
        block("wiki-strength", "Strength");
        block("wiki-intelligence", "Intelligence");
        block("灵火祭坛", "灵火祭坛（Flame Altar）");

        List<String> unnamed = catalog.page("", "unnamed", "", 500, 0)
                .items().stream().map(KbCatalog.Term::en).toList();

        assertThat(unnamed)
                .as("英文标题的两条要在，中文标题的那条不能在")
                .containsExactlyInAnyOrder("wiki-strength", "wiki-intelligence");
        assertThat(catalog.page("", "unnamed", "", 500, 0).counts().unnamed()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 中文名里只要有汉字，就不算「无中文名」（哪怕还带着英文别名）")
    void anyHanCharacterCountsAsNamed() {
        block("a", "力量（Strength）");
        block("b", "Bamboo Ladder、Bamboo Ladder");   // 中文名和别名都是英文 → 仍算没名字
        block("c", "灯（Lamp）");

        List<String> unnamed = catalog.page("", "unnamed", "", 500, 0)
                .items().stream().map(KbCatalog.Term::en).toList();

        assertThat(unnamed).containsExactly("b");
    }

    @Test
    @DisplayName("main 视图也会把「既没正文又没中文名」的挡在外面（同一个根因）")
    void mainViewAlsoUsesChineseDetection() {
        block("wiki-strength", "Strength");
        block("灵火祭坛", "灵火祭坛（Flame Altar）");

        // 两条都有正文 → 两条都该在 main 里
        assertThat(catalog.page("", "main", "", 500, 0).items()).hasSize(2);
        assertThat(catalog.page("", "", "", 500, 0).counts().main()).isEqualTo(2);
        assertThat(catalog.page("", "", "", 500, 0).counts().unnamed()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 导出可以只导「还没中文名」的那批（几百条在网页上点不现实，要走 Excel）")
    void exportCanBeFilteredByView() {
        block("wiki-strength", "Strength");
        block("wiki-intelligence", "Intelligence");
        block("灵火祭坛", "灵火祭坛（Flame Altar）");

        String only = catalog.exportTsv("", "unnamed", "");
        assertThat(only).contains("wiki-strength").contains("wiki-intelligence");
        assertThat(only).as("有中文名的那条不该出现在 unnamed 导出里").doesNotContain("灵火祭坛");

        String all = catalog.exportTsv();
        assertThat(all).contains("wiki-strength").contains("灵火祭坛");
        assertThat(all.lines().count()).as("不带筛选时行为不变").isGreaterThan(only.lines().count());
    }

    @Test
    @DisplayName("★ 导出与列表**用同一套筛选** —— 列表里看到多少条，导出就是多少条")
    void exportAndListShareTheSameFilter() {
        // 三个块给不同的 tags，让它们落进不同板块
        blocks.upsert(new KbBlock(EntryId.of("a"), "d", "Sword", "x", "", List.of("武器"), KbBlock.SRC_DOC, false, "t"),
                new float[]{0.1f, 0.2f, 0.3f});
        blocks.upsert(new KbBlock(EntryId.of("b"), "d", "Table", "x", "", List.of("家具"), KbBlock.SRC_DOC, false, "t"),
                new float[]{0.1f, 0.2f, 0.3f});
        blocks.upsert(new KbBlock(EntryId.of("c"), "d", "剑（Sword）", "x", "", List.of("武器"), KbBlock.SRC_DOC, false, "t"),
                new float[]{0.1f, 0.2f, 0.3f});

        // 列表（board=combat）与导出（同一 board）必须一致
        int listTotal = catalog.page("", "all", "combat", 500, 0).total();
        long exportRows = catalog.exportTsv("", "all", "combat").lines()
                .filter(l -> !l.isBlank() && l.charAt(0) != '#').count();
        assertThat(exportRows).as("列表 %d 条，导出 %d 条 —— 必须相等", listTotal, exportRows)
                .isEqualTo(listTotal);

        // unnamed + board 叠加也要生效
        // ⚠️ 只看**数据行**：表头里有 "rejected" 这种带字母 c 的说明文字，
        //    直接对整段做 doesNotContain("c") 会假红（本测试第一次就是这么挂的）
        String both = catalog.exportTsv("", "unnamed", "combat").lines()
                .filter(l -> !l.isBlank() && l.charAt(0) != '#')
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(both).contains("a").doesNotContain("c");
    }

    @Test
    @DisplayName("★ TSV 导出：无 BOM、含分隔符的字段被引号包住、行数与列表一致")
    void tsvExportIsWellFormed() {
        block("a", "Strength");
        // 标题里故意放**制表符**（TSV 的分隔符）—— 不转义就会被切错列，而且不报错
        blocks.upsert(new KbBlock(EntryId.of("b"), "d", "Tab\tInside", "x", "",
                List.of("武器"), KbBlock.SRC_DOC, false, "t"), new float[]{0.1f, 0.2f, 0.3f});

        String tsv = catalog.exportTsv("", "all", "");
        assertThat(tsv.charAt(0)).as("TSV 不该带 BOM").isNotEqualTo('\uFEFF');
        assertThat(tsv)
                .as("含制表符的字段必须被双引号包住，读回来才是原样")
                .contains("\"Tab\tInside\"");

        long rows = tsv.lines().filter(l -> !l.isBlank() && l.charAt(0) != '#').count();
        assertThat(rows)
                .as("数据行数必须等于列表条数")
                .isEqualTo(catalog.page("", "all", "", 500, 0).total());
        // 解析侧的往返性质由 kb.term.CsvTableQuoteTest 守（它测的是同一个 RFC4180 解析器）。
        // 这里只钉导出侧的格式：那是本层唯一会漏出去给人和 Excel 看的东西。
    }
}
