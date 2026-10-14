package com.example.qqbot.kb.category;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.EntryId;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockStore;
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

/**
 * 分类 = **人工维护的平铺清单**，词条的分块符号里带什么标签就归什么分类。
 *
 * <p>这里钉住三件事：
 * <ul>
 *   <li>只统计**清单里认得的**标签 —— 认不出的、或没标签的，一律算未分类；</li>
 *   <li>新增 / 改名 / 删除只动清单，不会连累词条内容；删除后词条回到未分类；</li>
 *   <li>{@link CategoryService#ensure} 是 wiki 导入专用的"自动登记"，文档导入不会调它。</li>
 * </ul>
 */
class CategoryServiceTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private KbBlockStore blocks;
    private CategoryService service;

    @BeforeEach
    void setUp() {
        PersistenceProperties p = new PersistenceProperties();
        p.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(p);
        db.init();
        blocks = new KbBlockStore(new KbBlockRepository(new Jdbc(db)));
        blocks.init();

        CategoryStore store = new CategoryStore(new KbCategoryRepository(new Jdbc(db)));
        store.init();
        service = new CategoryService(store, blocks);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void block(String id, String... tags) {
        blocks.upsert(new KbBlock(EntryId.of(id), "doc", id, "正文", "",
                List.of(tags), KbBlock.SRC_DOC, false, "t"), null);
    }

    @Test
    @DisplayName("★ 只统计清单里认得的标签；认不出的算未分类")
    void onlyKnownTagsCount() {
        service.add("武器", "武器");
        block("a", "武器");
        block("b", "武器", "别的");
        block("c", "别的");
        block("d");

        assertThat(service.stats())
                .extracting(CategoryService.Stat::name, CategoryService.Stat::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("武器", 2));
        assertThat(service.uncategorizedCount()).as("c 的标签没登记，d 没标签").isEqualTo(2);
    }

    @Test
    @DisplayName("新增的分类即使还没有词条也要出现在清单里")
    void newCategoryShowsWithZero() {
        service.add("空分类", "空分类");

        assertThat(service.stats())
                .extracting(CategoryService.Stat::name, CategoryService.Stat::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("空分类", 0));
    }

    @Test
    @DisplayName("★ 删除分类只动清单：词条还在，只是回到未分类")
    void removeCategoryDoesNotTouchEntries() {
        service.add("武器", "武器");
        block("a", "武器");

        assertThat(service.stats()).extracting(CategoryService.Stat::count).containsExactly(1);
        service.remove("武器");

        assertThat(service.stats()).isEmpty();
        assertThat(service.uncategorizedCount()).isEqualTo(1);
        assertThat(blocks.get(EntryId.of("a"))).as("词条本身没被删").isNotNull();
    }

    @Test
    @DisplayName("改显示名不影响分类名（分类名就是标签值）")
    void renameOnlyChangesLabel() {
        service.add("武器", "武器");
        service.rename("武器", "战斗武器");
        block("a", "武器");

        assertThat(service.labelOf("武器")).isEqualTo("战斗武器");
        assertThat(service.stats()).extracting(CategoryService.Stat::name).containsExactly("武器");
    }

    @Test
    @DisplayName("「未分类」是保留名，不能新增")
    void uncategorizedIsReserved() {
        assertThat(service.add(CategoryService.UNCATEGORIZED, "")).isFalse();
        assertThat(service.remove(CategoryService.UNCATEGORIZED)).isFalse();
    }

    @Test
    @DisplayName("★ ensure：wiki 导入自动登记分类（已存在的跳过、未分类跳过）")
    void ensureRegistersWikiCategories() {
        int added = service.ensure(List.of("Quests", "Gameplay", CategoryService.UNCATEGORIZED, ""));

        assertThat(added).isEqualTo(2);
        assertThat(service.catalogNames()).containsExactly("Quests", "Gameplay");
        assertThat(service.ensure(List.of("Quests"))).as("已存在的跳过").isZero();
    }

    @Test
    @DisplayName("一个词条可以有多个分类；重复标签只算一次")
    void multipleCategoriesPerEntry() {
        service.add("武器", "武器");
        service.add("基础", "基础");
        block("a", "武器", "基础", "武器");

        assertThat(service.recognized(List.of("武器", "基础", "武器"))).containsExactly("武器", "基础");
        assertThat(service.stats()).extracting(CategoryService.Stat::count).containsExactly(1, 1);
    }
}
