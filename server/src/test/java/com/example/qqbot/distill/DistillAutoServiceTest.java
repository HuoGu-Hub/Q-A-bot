package com.example.qqbot.distill;

import com.example.qqbot.config.DistillProperties;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillJobRepository;
import com.example.qqbot.persistence.DistillSettingRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistillAutoServiceTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private ChatRepository chat;
    private DistillJobRepository jobRepo;
    private DistillSettingRepository settings;
    private DistillAutoService auto;

    @BeforeEach
    void setUp() throws Exception {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("auto.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        chat = new ChatRepository(jdbc);
        chat.initSchema();
        jobRepo = new DistillJobRepository(jdbc);
        jobRepo.initSchema();
        new com.example.qqbot.persistence.DistillNoteRepository(jdbc).initSchema();
        DistillProperties props = new DistillProperties();
        props.setCaptureGroups(List.of(209127588L));
        props.setBatchMaxMessages(2);
        props.setBatchOverlap(0);
        settings = new DistillSettingRepository(jdbc, props);
        settings.initSchema();
        DistillWorker worker = mock(DistillWorker.class);
        when(worker.start(anyLong())).thenReturn(true);
        auto = new DistillAutoService(settings, jobRepo, chat,
                new DistillJobService(chat, jobRepo, props), worker, props);

        // 先开启，避免开启动作把游标推到最新；再导入消息
        auto.save(new DistillSettingRepository.Settings(true, 5, 0, 5, 10000, 0, 30,
                false, 0.75, "", "", 0, ""));
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("游标保留重叠条数，但每轮至少向前推进一条")
    void nextCursorKeepsOverlap() {
        assertThat(DistillAutoService.nextCursor(0, 5, 2)).isEqualTo(3);
        assertThat(DistillAutoService.nextCursor(3, 4, 5)).isEqualTo(4);
        assertThat(DistillAutoService.nextCursor(0, 5, 0)).isEqualTo(5);
    }

    @Test
    @DisplayName("累计到 5 条消息自动建任务并推进游标")
    void triggersAtMessageThreshold() {
        String content = String.join("\n",
                "{\"ts\":\"2026-10-20 21:00:00\",\"group\":209127588,\"sender\":\"甲\",\"text\":\"一\"}",
                "{\"ts\":\"2026-10-20 21:01:00\",\"group\":209127588,\"sender\":\"乙\",\"text\":\"二\"}",
                "{\"ts\":\"2026-10-20 21:02:00\",\"group\":209127588,\"sender\":\"丙\",\"text\":\"三\"}",
                "{\"ts\":\"2026-10-20 21:03:00\",\"group\":209127588,\"sender\":\"丁\",\"text\":\"四\"}",
                "{\"ts\":\"2026-10-20 21:04:00\",\"group\":209127588,\"sender\":\"戊\",\"text\":\"五\"}");
        new ChatImportService(chat, new DistillProperties(), new ObjectMapper())
                .importFile("auto.jsonl", content, 0);

        auto.tick();

        assertThat(jobRepo.countJobs()).isEqualTo(1);
        var job = jobRepo.findJob(1);
        assertThat(job.source()).isEqualTo("live");
        assertThat(job.messageCount()).isEqualTo(5);
        assertThat(job.batchCount()).isEqualTo(1);
        assertThat(settings.getOrCreate().lastMessageId()).isEqualTo(5);
    }

    @Test
    @DisplayName("累计到 3 张图自动触发，截断在凑够图片的那条消息")
    void triggersAtImageThreshold() {
        auto.save(new DistillSettingRepository.Settings(true, 500, 3, 500, 10000, 0, 30,
                false, 0.75, "", "", 0, ""));
        String content = String.join("\n",
                "{\"ts\":\"2026-10-20 21:00:00\",\"group\":209127588,\"sender\":\"甲\",\"text\":\"一\"}",
                "{\"ts\":\"2026-10-20 21:01:00\",\"group\":209127588,\"sender\":\"乙\",\"text\":\"二\"}",
                "{\"ts\":\"2026-10-20 21:02:00\",\"group\":209127588,\"sender\":\"丙\",\"text\":\"三\"}",
                "{\"ts\":\"2026-10-20 21:03:00\",\"group\":209127588,\"sender\":\"丁\",\"text\":\"四\"}");
        new ChatImportService(chat, new DistillProperties(), new ObjectMapper())
                .importFile("images.jsonl", content, 0);
        var messages = chat.recentMessages(10);
        long first = messages.get(messages.size() - 1).id();
        long second = messages.get(messages.size() - 2).id();
        chat.insertImage(new ChatRepository.NewImage(first, "m1", "a.png", "image/png", 10, "", "2026-10-20T21:00:00Z"));
        chat.insertImage(new ChatRepository.NewImage(first, "m2", "b.png", "image/png", 10, "", "2026-10-20T21:00:00Z"));
        chat.insertImage(new ChatRepository.NewImage(second, "m3", "c.png", "image/png", 10, "", "2026-10-20T21:01:00Z"));

        auto.tick();

        assertThat(jobRepo.countJobs()).isEqualTo(1);
        assertThat(jobRepo.findJob(1).messageCount()).isEqualTo(2);
        assertThat(settings.getOrCreate().lastMessageId()).isEqualTo(second);
    }
}
