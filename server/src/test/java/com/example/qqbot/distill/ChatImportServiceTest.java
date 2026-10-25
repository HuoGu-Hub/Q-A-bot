package com.example.qqbot.distill;

import com.example.qqbot.config.DistillProperties;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatImportServiceTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private ChatRepository repo;
    private ChatImportService service;

    @BeforeEach
    void setUp() throws Exception {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("import.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        repo = new ChatRepository(new Jdbc(db));
        repo.initSchema();
        service = new ChatImportService(repo, new DistillProperties(), new ObjectMapper());
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("JSONL：解析、写库、重复导入不重复写")
    void importsJsonlAndDedupes() {
        String content = String.join("\n",
                "{\"ts\":\"2026-10-20 21:03:11\",\"group\":1,\"sender\":\"甲\",\"text\":\"灵火祭坛在东边\"}",
                "{\"ts\":\"2026-10-20T21:04:00+08:00\",\"group\":1,\"sender\":\"乙\",\"text\":\"要带火把\"}",
                "{\"ts\":\"bad\",\"group\":1,\"sender\":\"丙\",\"text\":\"坏时间\"}",
                "not json");

        var preview = service.preview("chat.jsonl", content);
        assertThat(preview.format()).isEqualTo("jsonl");
        assertThat(preview.parsed()).isEqualTo(2);
        assertThat(preview.errors()).isEqualTo(2);

        var first = service.importFile("chat.jsonl", content, 0);
        assertThat(first.inserted()).isEqualTo(2);
        assertThat(first.duplicates()).isZero();
        assertThat(repo.countMessages()).isEqualTo(2);

        var second = service.importFile("chat.jsonl", content, 0);
        assertThat(second.inserted()).isZero();
        assertThat(second.duplicates()).isEqualTo(2);
        assertThat(repo.countMessages()).isEqualTo(2);
    }

    @Test
    @DisplayName("TXT：两种常见时间写法都能解析，坏行进错误清单")
    void importsTxt() {
        String content = String.join("\n",
                "2026-10-20 21:03:11 甲: 内容一",
                "[2026-10-20 21:04:00] 乙：内容二",
                "这一行没有时间");

        var preview = service.preview("chat.txt", content);
        assertThat(preview.format()).isEqualTo("txt");
        assertThat(preview.parsed()).isEqualTo(2);
        assertThat(preview.errors()).isEqualTo(1);

        var result = service.importFile("chat.txt", content, 100L);
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(repo.recentMessages(10)).allMatch(m -> m.groupId() == 100L);
    }

    @Test
    @DisplayName("空文件与超大文件都明确拒绝")
    void rejectsBadInput() {
        assertThatThrownBy(() -> service.preview("chat.txt", "   "))
                .isInstanceOf(IllegalArgumentException.class);

        DistillProperties small = new DistillProperties();
        small.setMaxImportChars(10);
        ChatImportService tiny = new ChatImportService(repo, small, new ObjectMapper());
        assertThatThrownBy(() -> tiny.preview("chat.txt", "2026-10-20 21:03:11 甲: 内容一"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件太大");
    }
}
