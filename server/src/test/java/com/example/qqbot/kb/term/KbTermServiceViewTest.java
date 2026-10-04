package com.example.qqbot.kb.term;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.category.CategoryService;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.KbTermRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 「无中文名」筛选器 —— 它曾经**一条都抓不到**。
 *
 * <h2>真实故障（2026-10-04 实测）</h2>
 * {@code matchesView} 里原来写的是 {@code case "unnamed" -> t.zh().isEmpty()}。
 * 但 {@link KbTermService#buildAll} 有一句回退：
 * {@code zh = name[0].isEmpty() ? b.title() : name[0]} —— **词条没中文名时退回用块标题**。
 * 自动导入的英文页（技能/机制/属性/收集品笔记）标题就是英文，
 * 于是它们的 {@code zh} 是 {@code "Strength"} 这种**非空英文** → {@code isEmpty()} 恒为 false。
 *
 * <p>结果：生产库里 651 条该被「无中文名」抓到的**一条都抓不到**，
 * 管理员在后台想补译名却找不到入口。{@code main} 视图的
 * 「挡掉既没正文又没中文名的空页面」也是同一个原因失效。
 *
 * <p>修法：按**有没有汉字**判定（{@code hasChinese}），不依赖回退行为。
 */
class KbTermServiceViewTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private KbBlockStore blocks;
    private KbTermService service;

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
        service = new KbTermService(blocks, terms, mock(CategoryService.class),
                mock(EmbeddingClient.class), new ObjectMapper(), mock(ApplicationEventPublisher.class));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void block(String id, String title) {
        blocks.upsert(new KbBlock(id, "doc", title, "正文内容", "", List.of(),
                KbBlock.SRC_DOC, false, "2026-10-04T00:00:00Z"), new float[]{0.1f, 0.2f, 0.3f});
    }

    @Test
    @DisplayName("★ 英文标题的块必须出现在「无中文名」里（原来一条都抓不到）")
    void englishTitledBlockShowsUpAsUnnamed() {
        block("wiki-strength", "Strength");
        block("wiki-intelligence", "Intelligence");
        block("灵火祭坛", "灵火祭坛（Flame Altar）");

        List<String> unnamed = service.page("", "unnamed", "", 500, 0)
                .items().stream().map(KbTermService.Term::en).toList();

        assertThat(unnamed)
                .as("英文标题的两条要在，中文标题的那条不能在")
                .containsExactlyInAnyOrder("wiki-strength", "wiki-intelligence");
        assertThat(service.page("", "unnamed", "", 500, 0).counts().unnamed()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 中文名里只要有汉字，就不算「无中文名」（哪怕还带着英文别名）")
    void anyHanCharacterCountsAsNamed() {
        block("a", "力量（Strength）");
        block("b", "Bamboo Ladder、Bamboo Ladder");   // 中文名和别名都是英文 → 仍算没名字
        block("c", "灯（Lamp）");

        List<String> unnamed = service.page("", "unnamed", "", 500, 0)
                .items().stream().map(KbTermService.Term::en).toList();

        assertThat(unnamed).containsExactly("b");
    }

    @Test
    @DisplayName("main 视图也会把「既没正文又没中文名」的挡在外面（同一个根因）")
    void mainViewAlsoUsesChineseDetection() {
        block("wiki-strength", "Strength");
        block("灵火祭坛", "灵火祭坛（Flame Altar）");

        // 两条都有正文（chunkCount>0）→ 两条都该在 main 里
        assertThat(service.page("", "main", "", 500, 0).items()).hasSize(2);
        assertThat(service.page("", "", "", 500, 0).counts().main()).isEqualTo(2);
        assertThat(service.page("", "", "", 500, 0).counts().unnamed()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 导出可以只导「还没中文名」的那批（651 条在网页上点不现实，要走 Excel）")
    void exportCanBeFilteredByView() {
        block("wiki-strength", "Strength");
        block("wiki-intelligence", "Intelligence");
        block("灵火祭坛", "灵火祭坛（Flame Altar）");

        String only = service.exportTsv("unnamed");
        assertThat(only).contains("wiki-strength").contains("wiki-intelligence");
        assertThat(only).as("有中文名的那条不该出现在 unnamed 导出里").doesNotContain("灵火祭坛");

        String all = service.exportTsv();
        assertThat(all).contains("wiki-strength").contains("灵火祭坛");
        assertThat(all.lines().count()).as("不带 view 时行为不变").isGreaterThan(only.lines().count());
    }
}
