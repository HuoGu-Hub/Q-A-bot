package com.example.qqbot.kb.term;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.KbTermRepository;
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
 * 词条表的**自动补齐**验收（2026-10-08 第四步）。
 *
 * <p>钉住的性质：写完块，**不需要任何调用方记得调 reconcile()** ——
 * 下一次有人读词条（B 路检索 / 管理端）时它会自己发现「语料版本变了」并补齐。
 *
 * <p>旧做法是让导入器 / 地图派生器写完之后显式调 reconcile()（迁移前共 4 处）。
 * 那和块索引的 {@code reload()} 是同一条教训：新增一个写入口就漏一个，
 * 而漏了的症状是**新页面没有中文名的位置** —— B 路关键词搜不到它，也不报错。
 */
class KbTermStoreLazyReconcileTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private KbTermRepository repo;
    private KbBlockStore blocks;
    private KbTermStore terms;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        repo = new KbTermRepository(new Jdbc(db));
        blocks = new KbBlockStore(new KbBlockRepository(new Jdbc(db)));
        blocks.init();
        terms = new KbTermStore(repo, new KbBlockIndex(blocks));
        terms.init();
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static KbBlock block(String id, String title) {
        return new KbBlock(id, "flame-altar", title, "正文", "https://w/" + id,
                List.of("地点"), KbBlock.SRC_DOC, false, "t");
    }

    @Test
    @DisplayName("★ 写完块不调 reconcile()：第一次读词条时自己补齐")
    void firstReadReconciles() {
        assertThat(terms.list()).as("一开始语料是空的").isEmpty();

        blocks.upsert(block("flame-altar-0", "焰火祭坛（Flame Altar）"), new float[]{1f, 0f, 0f});

        // ⚠️ 关键：到这里为止**没有任何人调过 reconcile()** —— 补齐是懒的，写块不该顺手写词条
        assertThat(repo.count()).as("写块不该顺手写词条").isZero();

        // 有人来读了：这时才发现"语料版本变了"并补齐
        assertThat(terms.list())
                .extracting(KbTermStore.Entry::en)
                .containsExactly("flame-altar-0");
        assertThat(repo.count()).isEqualTo(1);

        // 幂等：再读一次不会重复插（快路径只比一个 long）
        assertThat(terms.list()).hasSize(1);
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("补齐只补缺的：人工起过的中文名不许被覆盖")
    void keepsManualNames() {
        terms.upsert("flame-altar-0", "焰火祭坛", "verified");
        blocks.upsert(block("flame-altar-0", "焰火祭坛（Flame Altar）"), new float[]{1f, 0f, 0f});
        blocks.upsert(block("flame-altar-1", "第二块"), new float[]{0f, 1f, 0f});

        assertThat(terms.list())
                .hasSize(2)
                .filteredOn(e -> "flame-altar-0".equals(e.en()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.zh()).as("人工名字优先").isEqualTo("焰火祭坛");
                    assertThat(e.status()).as("人工状态优先").isEqualTo("verified");
                });
    }

    @Test
    @DisplayName("没写任何块时不插空行（空语料不产生垃圾词条）")
    void emptyCorpusAddsNothing() {
        assertThat(terms.list()).isEmpty();
        assertThat(repo.count()).isZero();
    }
}