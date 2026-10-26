package com.example.qqbot.distill;

import com.example.qqbot.config.DistillProperties;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillJobRepository;
import com.example.qqbot.persistence.DistillNoteRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DistillJobServiceTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private ChatImportService imports;
    private DistillJobService jobs;

    @BeforeEach
    void setUp() throws Exception {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("jobs.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        ChatRepository chat = new ChatRepository(jdbc);
        chat.initSchema();
        DistillJobRepository repo = new DistillJobRepository(jdbc);
        repo.initSchema();
        new DistillNoteRepository(jdbc).initSchema();
        DistillProperties props = new DistillProperties();
        props.setBatchMaxMessages(2);
        props.setBatchOverlap(0);
        imports = new ChatImportService(chat, props, new ObjectMapper());
        jobs = new DistillJobService(chat, repo, props);
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("导入后能预览批次、建任务、查批次")
    void createsJobFromImport() {
        String content = String.join("\n",
                "{\"ts\":\"2026-10-20 21:00:00\",\"sender\":\"甲\",\"text\":\"一\"}",
                "{\"ts\":\"2026-10-20 21:01:00\",\"sender\":\"乙\",\"text\":\"二\"}",
                "{\"ts\":\"2026-10-20 21:02:00\",\"sender\":\"丙\",\"text\":\"三\"}",
                "{\"ts\":\"2026-10-20 21:03:00\",\"sender\":\"丁\",\"text\":\"四\"}",
                "{\"ts\":\"2026-10-20 21:04:00\",\"sender\":\"戊\",\"text\":\"五\"}");
        var imported = imports.importFile("chat.jsonl", content, 7L);

        var request = new DistillJobService.JobRequest("file", List.of(), "", "",
                imported.importId(), "count", 2, 0, 30);
        var preview = jobs.preview(request);
        assertThat(preview.messageCount()).isEqualTo(5);
        assertThat(preview.batchCount()).isEqualTo(3);

        long id = jobs.create(request);
        var detail = jobs.detail(id);
        assertThat(detail).isNotNull();
        assertThat(detail.job().status()).isEqualTo("ready");
        assertThat(detail.job().batchCount()).isEqualTo(3);
        assertThat(detail.batches()).hasSize(3);
        assertThat(detail.batches()).allMatch(b -> "pending".equals(b.status()));
        assertThat(detail.batches().get(0).messageFrom()).isPositive();
        assertThat(detail.batches().get(0).messageTo()).isPositive();
    }
}
