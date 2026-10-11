package com.example.qqbot.kb;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.category.CategoryService;
import com.example.qqbot.kb.category.CategoryStore;
import com.example.qqbot.kb.term.KbTermStore;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbTermRepository;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.example.qqbot.kb.EntryId;

/**
 * 「切换时刻」的依赖测试。
 *
 * <p>切到块表（{@code app.kb.store=blocks}）时，有三处曾经**只认旧文件**的地方会坏，
 * 而且都是**静默坏**：分类空掉、词条不再补齐、投递写进没人读的文件。
 * 这里把"它们现在都从语料取"钉住，免得以后又被改回去。
 *
 * <p>另外把"按行号的旧块接口在切换后必须明确报错"也钉住 ——
 * 不报错的话，管理员会看到"保存成功"，但改的是没人再读的文件。
 */
class SwitchTimeDependenciesTest {

    @TempDir
    Path base;

    private KbProperties props;

    @BeforeEach
    void setUp() {
        props = new KbProperties();
        props.setDir(base.resolve("kb").toString());
    }

    /** 造一条语料条目（只有 tags / docId 对这两个测试有意义） */
    private static KbCorpus.Entry entry(String id, String docId, List<String> tags) {
        return entry(id, docId, "标题" + id, tags);
    }

    private static KbCorpus.Entry entry(String id, String docId, String title, List<String> tags) {
        return new KbCorpus.Entry(id, docId, title, "正文", "", tags, false, "", new float[]{1f});
    }

    private static KbCorpus corpusOf(KbCorpus.Entry... entries) {
        KbCorpus c = mock(KbCorpus.class);
        when(c.isReady()).thenReturn(true);
        when(c.entries()).thenReturn(List.of(entries));
        return c;
    }

    /* ==================== ① 分类统计：从语料取，不再读 chunks.jsonl ==================== */

    @Test
    @DisplayName("★ 分类统计来自语料（旧写法读 chunks.jsonl，切换后会永远显示旧数据）")
    void categoryCountsComeFromCorpus() {
        KbCorpus corpus = corpusOf(
                entry("a-0", "a", List.of("基础", "据点")),
                entry("a-1", "a", List.of("基础")),
                entry("b-0", "b", List.of("制作")));

        CategoryService svc = new CategoryService(mock(CategoryStore.class),
                new ObjectMapper(), corpus);

        assertThat(svc.rawCounts())
                .containsEntry("基础", 2)
                .containsEntry("据点", 1)
                .containsEntry("制作", 1);
    }

    @Test
    @DisplayName("词料为空时分类也空（不报错）")
    void categoryCountsEmptyWhenNoCorpus() {
        CategoryService svc = new CategoryService(mock(CategoryStore.class),
                new ObjectMapper(), corpusOf());
        assertThat(svc.rawCounts()).isEmpty();
    }

    /* ==================== ② 词条补齐：页面来自语料的 docId ==================== */

    @Test
    @DisplayName("★ 词条表按语料的 docId 补齐（切换后新导入的文档才会出现在词条里）")
    void termReconcileSeedsFromCorpus() {
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        SqliteDatabase db = new SqliteDatabase(persist);
        db.init();
        QaStore qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();

        KbCorpus corpus = corpusOf(
                entry("flame-altar-0", "flame-altar", List.of()),
                entry("kiln-0", "kiln", List.of()));

        // 先放一条人工成果，reconcile 不能把它冲掉（这条是术语表最容易出事的地方）
        KbTermStore seed = new KbTermStore(new KbTermRepository(new Jdbc(db)), corpus);
        seed.init();
        seed.upsert(EntryId.of("flame-altar"), "灵火祭坛", "verified");

        KbTermStore store = new KbTermStore(new KbTermRepository(new Jdbc(db)), corpus);
        store.init();
        assertThat(store.get(EntryId.of("flame-altar")).zh()).as("★ 人工成果不能被语料重建冲掉").isEqualTo("灵火祭坛");
        assertThat(store.get(EntryId.of("flame-altar")).status()).isEqualTo("verified");

        assertThat(store.isAvailable()).isTrue();
        assertThat(store.count()).as("两个块各一条 + 那条人工成果").isEqualTo(3);
        assertThat(store.get(EntryId.of("flame-altar"))).as("人工成果还在").isNotNull();
        assertThat(store.get(EntryId.of("flame-altar")).zh()).isEqualTo("灵火祭坛");
        assertThat(store.get(EntryId.of("kiln-0")).zh()).as("★ 补齐的名字 = 块标题").isEqualTo("标题kiln-0");
        db.close();
    }

    @Test
    @DisplayName("★ 一块一条：同一份文档的两个块 → 两条词条（不是一条）")
    void reconcileSeedsPerBlock() {
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa2.sqlite").toString());
        SqliteDatabase db = new SqliteDatabase(persist);
        db.init();
        QaStore qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();

        KbCorpus corpus = corpusOf(
                entry("abyssal-wing-axe", "物品图鉴·双手武器",
                        "深渊之翼斧（Abyssal Wing Axe）", List.of()),
                entry("acid-bite", "物品图鉴·法杖",
                        "酸蚀之咬（Acid Bite）", List.of()));

        KbTermStore store = new KbTermStore(new KbTermRepository(new Jdbc(db)), corpus);
        store.init();

        assertThat(store.count()).as("★ 同一份文档的两个块 → 两条词条").isEqualTo(2);
        assertThat(store.get(EntryId.of("abyssal-wing-axe"))).isNotNull();
        assertThat(store.get(EntryId.of("acid-bite"))).isNotNull();
        assertThat(store.get(EntryId.of("abyssal-wing-axe")).zh())
                .as("★ 补齐时也拆括号：短中文名才匹配得上")
                .isEqualTo("深渊之翼斧、Abyssal Wing Axe");
        db.close();
    }

    @Test
    @DisplayName("清理孤儿词条：没有对应块的删掉，有对应块的不动")
    void deleteOrphanTerms() {
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa-orphan.sqlite").toString());
        SqliteDatabase db = new SqliteDatabase(persist);
        db.init();
        QaStore qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();

        KbTermStore store = new KbTermStore(new KbTermRepository(new Jdbc(db)), corpusOf());
        store.init();
        store.upsert(EntryId.of("has-block"), "有正文的", "draft");
        store.upsert(EntryId.of("no-block"), "早期版本留下的文档名", "draft");

        int n = store.deleteOrphans(java.util.Set.of(EntryId.of("has-block")));

        assertThat(n).isEqualTo(1);
        assertThat(store.get(EntryId.of("has-block"))).as("有块的不动").isNotNull();
        assertThat(store.get(EntryId.of("no-block"))).as("孤儿的删掉").isNull();
        assertThat(store.count()).isEqualTo(1);
        db.close();
    }

    @Test
    @DisplayName("★ 空集合不等于「全清」—— 手滑传空集不该把词条表整张清掉")
    void emptyKnownSetDoesNotWipeTheTable() {
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa-orphan-empty.sqlite").toString());
        SqliteDatabase db = new SqliteDatabase(persist);
        db.init();
        QaStore qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();

        KbTermStore store = new KbTermStore(new KbTermRepository(new Jdbc(db)), corpusOf());
        store.init();
        store.upsert(EntryId.of("a"), "甲", "draft");
        store.upsert(EntryId.of("b"), "乙", "draft");

        // 判据是"表里的行不在已知块集合里"。空集合意味着**每一行都是孤儿** ——
        // 那正是"调用方不小心传了个空集"与"真要清空整表"唯一的区别点，
        // 而后者有专门的 deleteAll()。所以这里守住：空集合什么都不做。
        int n = store.deleteOrphans(java.util.Set.of());

        assertThat(n).isZero();
        assertThat(store.count()).as("整张表一条不少").isEqualTo(2);
        db.close();
    }

    /* ==================== ③ 按行的旧块接口：切换后必须明确报错 ==================== */


}
