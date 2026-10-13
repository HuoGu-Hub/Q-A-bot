package com.example.qqbot.kb.term;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.EntryId;
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
 * 词条写入必须带上「英文那一侧」—— 2026-10-13 实测到的静默失效。
 *
 * <p>检索侧的关键词路拿 {@code zh} 里那截英文名去匹配标题与正文；
 * {@code en} 是身份 id（slug，如 {@code wiki-fell-critter-queen}），
 * 永远不会出现在正文里。人手动加中文别名时只会写中文，把英文名顶掉 →
 * 关键词候选直接变 0，**不报错**，只剩向量路在干活。
 */
class KbTermEnglishSideTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private KbBlockStore blocks;
    private KbTermStore terms;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        blocks = new KbBlockStore(new KbBlockRepository(new Jdbc(db)));
        blocks.init();
        KbBlockIndex index = new KbBlockIndex(blocks);
        blocks.upsert(new KbBlock(EntryId.of("wiki-fell-critter-queen"), "wiki-fell-critter-queen",
                "Fell Critter Queen", "The Fell Critter Queen is a miniboss in Enshrouded.",
                "https://enshrouded.wiki.gg/wiki/Fell_Critter_Queen", List.of("Bosses"), "wiki", false, ""),
                new float[]{0.1f, 0.2f});
        index.reload();
        terms = new KbTermStore(new KbTermRepository(new Jdbc(db)), index);
        terms.init();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("★ 只写中文别名时，自动把块标题补成英文那一侧")
    void appendsEnglishTitle() {
        terms.upsert(EntryId.of("wiki-fell-critter-queen"), "堕落生物女王", "verified");

        String zh = terms.list().stream()
                .filter(e -> e.en().equals("wiki-fell-critter-queen"))
                .map(KbTermStore.Entry::zh).findFirst().orElse("");
        assertThat(zh).as("英文名必须补回去，否则关键词路静默失效")
                .isEqualTo("堕落生物女王、Fell Critter Queen");
    }

    @Test
    @DisplayName("已经有英文名时原样保留，不重复追加")
    void keepsExistingEnglishSide() {
        terms.upsert(EntryId.of("wiki-fell-critter-queen"), "堕落生物女王、Fell Critter Queen", "verified");
        terms.upsert(EntryId.of("wiki-fell-critter-queen"), "堕落生物女王、Fell Critter Queen、堕落女王", "verified");

        String zh = terms.list().stream()
                .filter(e -> e.en().equals("wiki-fell-critter-queen"))
                .map(KbTermStore.Entry::zh).findFirst().orElse("");
        assertThat(zh).isEqualTo("堕落生物女王、Fell Critter Queen、堕落女王");
    }

    @Test
    @DisplayName("slug 不算英文名：只给 slug 时会补标题")
    void slugIsNotAnEnglishName() {
        terms.upsert(EntryId.of("wiki-fell-critter-queen"), "堕落生物女王、wiki-fell-critter-queen", "verified");

        String zh = terms.list().stream()
                .filter(e -> e.en().equals("wiki-fell-critter-queen"))
                .map(KbTermStore.Entry::zh).findFirst().orElse("");
        assertThat(zh).isEqualTo("堕落生物女王、wiki-fell-critter-queen、Fell Critter Queen");
    }
}
