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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 块表的外部写入必须被感知 —— 与词条表同一套机制。
 *
 * <p>走管理端改块本来就不用重启（写方法会 {@code version++}，索引自己比对版本）。
 * 但**离线工具 / 直接 SQL** 改库绕过了内存计数器，索引会一直用旧语料且不报错，
 * 所以 {@link KbBlockStore#version()} 会低频比对库里的指纹。
 *
 * <p>注意：指纹只能让**索引**刷新。谁改了正文，谁就得自己把向量也写对
 * （管理端与导入器都强制重算向量；直接 SQL 改正文不更新 kb_block_vec 的话，
 * 索引会加载到"新正文 + 旧向量"的组合）。
 */
class KbBlockExternalWriteTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private SqliteDatabase db2;
    private KbBlockStore blocks;
    private KbBlockIndex index;
    private Jdbc external;

    @BeforeEach
    void setUp() {
        PersistenceProperties p1 = new PersistenceProperties();
        p1.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(p1);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        blocks = new KbBlockStore(new KbBlockRepository(jdbc));
        blocks.init();
        index = new KbBlockIndex(blocks);
        index.reload();

        // 第二个连接 —— 模拟离线工具
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
    @DisplayName("★ 别的进程直接写块表，版本号与索引都要跟着动")
    void externalWriteIsDetected() {
        long v0 = blocks.version();
        int n0 = index.size();

        external.update("INSERT INTO kb_block (id, doc_id, title, body, url, tags, source, retired, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                "wiki-x", "wiki-x", "X", "外部写入的正文", "", "", "wiki", 0, Instant.now().toString());

        blocks.forceExternalCheck();

        assertThat(blocks.version()).as("外部写入必须被发现，否则索引永远停在旧语料").isGreaterThan(v0);
        assertThat(index.size()).as("索引要跟着重载").isGreaterThan(n0);
    }

    @Test
    @DisplayName("本进程自己写入照旧生效（不依赖轮询）")
    void ownWriteStillWorks() {
        long v0 = blocks.version();
        blocks.upsert(new KbBlock(com.example.qqbot.kb.EntryId.of("own-1"), "own-1", "自己写的",
                "正文", "", java.util.List.of(), "doc", false, ""), new float[]{0.1f, 0.2f});
        assertThat(blocks.version()).isGreaterThan(v0);
        assertThat(index.size()).isEqualTo(1);
    }
}
