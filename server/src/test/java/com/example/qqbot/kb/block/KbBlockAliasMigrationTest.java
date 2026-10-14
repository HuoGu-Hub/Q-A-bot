package com.example.qqbot.kb.block;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧库一次性搬迁：旧的 {@code kb_term} 名字表 → 块的 {@code alias} 列。
 *
 * <p>旧库里有几千条人工填的中文名（B 路关键词检索的燃料）。词条并入分块之后，
 * 这些名字不能丢 —— 丢了中文提问就重新够不到那些英文页，而且**不报错**。
 *
 * <p>只搬「英文标题 + 旧中文名含非 ASCII」那批；中文标题的块旧 zh 只是标题副本，
 * 搬过来只会制造重复别名。
 */
class KbBlockAliasMigrationTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private Jdbc jdbc;

    @BeforeEach
    void setUp() {
        PersistenceProperties p = new PersistenceProperties();
        p.setDb(base.resolve("legacy.sqlite").toString());
        db = new SqliteDatabase(p);
        db.init();
        jdbc = new Jdbc(db);

        // ① 旧版 kb_block（**没有 alias 列**）
        jdbc.execute("CREATE TABLE kb_block ("
                + " id TEXT PRIMARY KEY, doc_id TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '',"
                + " body TEXT NOT NULL DEFAULT '', url TEXT NOT NULL DEFAULT '', tags TEXT NOT NULL DEFAULT '',"
                + " source TEXT NOT NULL DEFAULT 'doc', retired INTEGER NOT NULL DEFAULT 0,"
                + " updated_at TEXT NOT NULL DEFAULT '')");
        // ② 旧版 kb_term（人工中文名就存在这里）
        jdbc.execute("CREATE TABLE kb_term ("
                + " en TEXT PRIMARY KEY, zh TEXT NOT NULL DEFAULT '',"
                + " status TEXT NOT NULL DEFAULT 'draft', updated_at TEXT NOT NULL DEFAULT '')");

        jdbc.update("INSERT INTO kb_block (id, title, body) VALUES (?,?,?)", "intelligence", "Intelligence", "正文");
        jdbc.update("INSERT INTO kb_block (id, title, body) VALUES (?,?,?)", "flame-altar", "灵火祭坛", "正文");
        jdbc.update("INSERT INTO kb_block (id, title, body) VALUES (?,?,?)", "abyssal", "Abyssal Wing Axe", "正文");

        jdbc.update("INSERT INTO kb_term (en, zh) VALUES (?,?)", "intelligence", "智力");
        jdbc.update("INSERT INTO kb_term (en, zh) VALUES (?,?)", "flame-altar", "灵火祭坛");
        jdbc.update("INSERT INTO kb_term (en, zh) VALUES (?,?)", "abyssal", "Abyssal Wing Axe");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    @DisplayName("★ 加 alias 列时把人工中文名搬过来；中文标题与纯英文的旧名不搬")
    void migratesManualChineseNamesOnce() {
        KbBlockRepository repo = new KbBlockRepository(jdbc);
        repo.initSchema("doc");

        assertThat(repo.find("intelligence").alias()).as("★ 英文页的人工中文名 = 别名").isEqualTo("智力");
        assertThat(repo.find("flame-altar").alias()).as("中文标题不用搬").isEmpty();
        assertThat(repo.find("abyssal").alias()).as("旧名是纯英文，不搬").isEmpty();
    }

    @Test
    @DisplayName("已存在的别名不会被搬迁覆盖")
    void doesNotOverwriteExistingAlias() {
        KbBlockRepository repo = new KbBlockRepository(jdbc);
        // 先手工把 alias 列补上并写一个值，再跑 initSchema：不应覆盖
        jdbc.execute("ALTER TABLE kb_block ADD COLUMN alias TEXT NOT NULL DEFAULT ''");
        jdbc.update("UPDATE kb_block SET alias = ? WHERE id = ?", "已有人工别名", "intelligence");

        repo.initSchema("doc");

        assertThat(repo.find("intelligence").alias()).isEqualTo("已有人工别名");
    }
}
