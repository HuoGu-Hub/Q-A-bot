package com.example.qqbot.kb.term;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.Glossary;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部写入必须被感知 —— 2026-10-14 实测踩到的坑。
 *
 * <p>内存里的版本计数器只能感知**本进程**的写入。离线工具、直接 SQL 改库时它一动不动，
 * 于是检索侧一直用旧词表：补完几百条别名，机器人还是"怎么问都查不到"，
 * 而且不报错。所以 {@link KbTermStore#version()} 会按秒级轮询库里的指纹。
 *
 * <p>测试把轮询间隔设成 0（{@code -Dqqbot.term.external-check-millis=0}），
 * 这样不必等 5 秒。
 */
class KbTermExternalWriteTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private SqliteDatabase db2;
    private KbTermStore terms;
    private Glossary glossary;
    private Jdbc external;

    @BeforeEach
    void setUp() {
        PersistenceProperties p1 = new PersistenceProperties();
        p1.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(p1);
        db.init();
        Jdbc jdbc = new Jdbc(db);

        KbBlockStore blocks = new KbBlockStore(new KbBlockRepository(jdbc));
        blocks.init();
        KbBlockIndex index = new KbBlockIndex(blocks);
        terms = new KbTermStore(new KbTermRepository(jdbc), index);
        terms.init();
        glossary = new Glossary(terms);

        // 第二个连接 —— 模拟"离线工具"：完全不经过本进程的写方法
        PersistenceProperties p2 = new PersistenceProperties();
        p2.setDb(base.resolve("qa.sqlite").toString());
        db2 = new SqliteDatabase(p2);
        db2.init();
        external = new Jdbc(db2);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
        if (db2 != null) {
            db2.close();
        }
    }

    @Test
    @DisplayName("★ 别的进程直接写库，本进程的版本号与词表都要跟着动")
    void externalWriteIsDetected() {
        long v0 = terms.version();
        int n0 = glossary.size();

        external.update("INSERT INTO kb_term (en, zh, status, updated_at) VALUES (?,?,?,?)",
                "wiki-fell-sicklescythe", "堕落镰刃兽、Fell Sicklescythe", "draft", Instant.now().toString());

        // 不等轮询间隔（默认 5 秒）：显式要求下一次比对立刻发生
        terms.forceExternalCheck();

        assertThat(terms.version()).as("外部写入必须被发现，否则词表永远停在旧版").isGreaterThan(v0);
        assertThat(glossary.size()).as("词表要跟着重载，下一问就用新词").isGreaterThan(n0);
    }

    @Test
    @DisplayName("本进程自己写入照旧生效（不依赖轮询）")
    void ownWriteStillWorks() {
        long v0 = terms.version();
        terms.upsert(com.example.qqbot.kb.EntryId.of("wiki-fell-critter-queen"), "堕落生物女王", "verified");
        assertThat(terms.version()).isGreaterThan(v0);
        assertThat(glossary.size()).isGreaterThan(0);
    }
}
