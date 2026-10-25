package com.example.qqbot.distill;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.DistillNoteRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DistillNoteRepositoryTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private DistillNoteRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("notes.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        repo = new DistillNoteRepository(new Jdbc(db));
        repo.initSchema();
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static DistillNoteRepository.NewNote note(String title, String body) {
        return new DistillNoteRepository.NewNote(1, 2, title,
                DistillResponseParser.normTitle(title), "别名", body, "大佬攻略", 0.8, "draft",
                "test-model", "v1", DistillResponseParser.hash(body), "2026-10-25T10:00:00Z");
    }

    @Test
    @DisplayName("写笔记与来源、图片；标题或正文重复时返回已有 id")
    void insertsAndDedupes() {
        var sources = List.of(
                new DistillNoteRepository.Source(0, 11, "2026-10-25T10:00:00Z", "甲", "原文一"),
                new DistillNoteRepository.Source(0, 12, "2026-10-25T10:01:00Z", "乙", "原文二"));
        var first = repo.insert(note("灵火祭坛位置", "足够长的正文内容，用来测试写入与去重，至少三十个字符。"),
                sources, List.of(3L));
        assertThat(first.created()).isTrue();
        assertThat(first.id()).isPositive();
        assertThat(repo.sourcesOf(first.id())).hasSize(2);
        assertThat(repo.imageIdsOf(first.id())).containsExactly(3L);

        var duplicate = repo.insert(note("灵火祭坛位置", "足够长的正文内容，用来测试写入与去重，至少三十个字符。"),
                sources, List.of(3L));
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("更新内容、状态与列表筛选、真删除")
    void updatesAndDeletes() {
        var id = repo.insert(note("标题一", "这是第一条足够长的正文内容，用来测试更新与删除，至少三十个字符。"),
                List.of(), List.of()).id();
        assertThat(repo.updateContent(id, "标题二", DistillResponseParser.normTitle("标题二"),
                "新别名", "这是修改后的足够长正文内容，用来测试更新，至少三十个字符。",
                DistillResponseParser.hash("body2"), "地点", 0.9)).isTrue();
        assertThat(repo.updateStatus(id, "published", "distill-" + id)).isTrue();

        var note = repo.find(id);
        assertThat(note.title()).isEqualTo("标题二");
        assertThat(note.status()).isEqualTo("published");
        assertThat(note.blockId()).isEqualTo("distill-" + id);
        assertThat(repo.list("published", 1, 10)).hasSize(1);

        assertThat(repo.delete(id)).isTrue();
        assertThat(repo.find(id)).isNull();
        assertThat(repo.count()).isZero();
    }
}
