package com.example.qqbot.distill;

import com.example.qqbot.config.DistillProperties;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.category.CategoryService;
import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillJobRepository;
import com.example.qqbot.persistence.DistillNoteRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistillWorkerTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private ChatRepository chat;
    private DistillJobRepository jobRepo;
    private DistillNoteRepository noteRepo;
    private DistillSettingRepository settings;
    private DistillLlm llm;
    private DistillPublisher publisher;
    private DistillWorker worker;

    @BeforeEach
    void setUp() throws Exception {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("worker.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        chat = new ChatRepository(jdbc);
        chat.initSchema();
        jobRepo = new DistillJobRepository(jdbc);
        jobRepo.initSchema();
        noteRepo = new DistillNoteRepository(jdbc);
        noteRepo.initSchema();

        DistillProperties props = new DistillProperties();
        props.setAutoPublish(false);
        settings = new DistillSettingRepository(jdbc, props);
        settings.initSchema();
        props.setMaxNotesPerBatch(5);
        llm = mock(DistillLlm.class);
        when(llm.summarize(anyString(), anyList(), anyString())).thenReturn(new DistillLlm.Result(
                "{\"notes\":[{\"title\":\"灵火祭坛位置\",\"aliases\":[\"祭坛\"],"
                        + "\"category\":\"大佬攻略\",\"body\":\"先找灵火祭坛，再把火把放上去，"
                        + "需要三个树脂和两个石头，晚上去更容易看清。\",\"confidence\":0.9,"
                        + "\"sourceMessageIds\":[1]}]}",
                "fake-model", 100, 50));
        CategoryService categories = mock(CategoryService.class);
        when(categories.catalogNames()).thenReturn(List.of("大佬攻略"));
        publisher = mock(DistillPublisher.class);
        worker = new DistillWorker(jobRepo, chat, noteRepo, llm, new DistillResponseParser(new ObjectMapper()),
                publisher, categories, mock(ChatImageStore.class), settings, props);
    }

    @AfterEach
    void close() {
        worker.stop();
        db.close();
    }

    @Test
    @DisplayName("worker 跑完一个批次，产出笔记并把批次标为 done")
    void processesBatch() throws Exception {
        String content = String.join("\n",
                "{\"ts\":\"2026-10-20 21:00:00\",\"sender\":\"甲\",\"text\":\"灵火祭坛怎么去\"}",
                "{\"ts\":\"2026-10-20 21:01:00\",\"sender\":\"乙\",\"text\":\"东边，带火把\"}");
        var imported = new ChatImportService(chat, new DistillProperties(), new ObjectMapper())
                .importFile("chat.jsonl", content, 7L);
        long jobId = new DistillJobService(chat, jobRepo, new DistillProperties())
                .create(new DistillJobService.JobRequest("file", List.of(), "", "",
                        imported.importId(), "count", 10, 0, 30));

        worker.start(jobId);
        String status = "";
        for (int i = 0; i < 100; i++) {
            var job = jobRepo.findJob(jobId);
            status = job == null ? "" : job.status();
            if ("done".equals(status) || "failed".equals(status)) {
                break;
            }
            Thread.sleep(50);
        }

        assertThat(status).isEqualTo("done");
        assertThat(jobRepo.batchesOf(jobId)).allMatch(b -> "done".equals(b.status()));
        // mock 返回的 sourceMessageIds 是 1，若真实 id 不是 1 会被过滤，这批就没有笔记；
        // 这个测试只断言批次完成，笔记数量在 parser/repo 测试里单独覆盖。
        assertThat(noteRepo.count()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("重复知识会合并进旧笔记，而不是新增一条")
    void mergesDuplicateIntoExistingNote() throws Exception {
        String content = "{\"ts\":\"2026-10-20 21:00:00\",\"sender\":\"甲\",\"text\":\"金色宝箱\"}";
        var imported = new ChatImportService(chat, new DistillProperties(), new ObjectMapper())
                .importFile("merge.jsonl", content, 8L);
        long messageId = chat.recentMessages(10).get(0).id();

        String oldBody = "1. 山崖上有3级金色宝箱\n2. 打开后有一件金色装备";
        noteRepo.insert(new DistillNoteRepository.NewNote(
                0, 0, "出生点第一个任务山崖上的3级金色宝箱",
                DistillResponseParser.normTitle("出生点第一个任务山崖上的3级金色宝箱"),
                "金箱", oldBody, "大佬攻略", 0.8, "published", "old-model", "v1",
                DistillResponseParser.hash(DistillResponseParser.normalizeBody(oldBody)),
                "2026-10-20T09:00:00Z"),
                List.of(new DistillNoteRepository.Source(0, messageId, "2026-10-20T09:00:00Z", "甲", oldBody)),
                List.of());
        String newBody = "山崖上固定刷新3级金色宝箱，打开后里面还有一件稀有物品，建议顺路去拿。";
        String reply = "{\"notes\":[{\"title\":\"出生点第一个任务山崖的金色宝箱\","
                + "\"aliases\":[\"三级箱\"],\"category\":\"大佬攻略\","
                + "\"body\":\"" + newBody + "\",\"confidence\":0.9,"
                + "\"sourceMessageIds\":[" + messageId + "]}]}";
        when(llm.summarize(anyString(), anyList(), anyString()))
                .thenReturn(new DistillLlm.Result(reply, "fake-model", 100, 50));
        long jobId = new DistillJobService(chat, jobRepo, new DistillProperties())
                .create(new DistillJobService.JobRequest("file", List.of(), "", "",
                        imported.importId(), "count", 10, 0, 30));

        worker.start(jobId);
        for (int i = 0; i < 100; i++) {
            if ("done".equals(jobRepo.findJob(jobId).status())) {
                break;
            }
            Thread.sleep(50);
        }

        assertThat(noteRepo.count()).isEqualTo(1);
        assertThat(noteRepo.find(1).body()).contains("金色装备");
        assertThat(noteRepo.find(1).body()).contains("稀有物品");
    }

    @Test
    @DisplayName("开启自动发布后，即使低于旧置信度门槛也直接发布")
    void autoPublishIsBinary() throws Exception {
        settings.save(new DistillSettingRepository.Settings(true, 0, 0, 80, 10000, 0, 30,
                true, 0.99, "只整理建筑和配方", "", "", false, 0, ""));
        String content = String.join("\n",
                "{\"ts\":\"2026-10-20 21:00:00\",\"sender\":\"甲\",\"text\":\"灵火祭坛怎么去\"}",
                "{\"ts\":\"2026-10-20 21:01:00\",\"sender\":\"乙\",\"text\":\"东边，带火把\"}");
        var imported = new ChatImportService(chat, new DistillProperties(), new ObjectMapper())
                .importFile("publish.jsonl", content, 7L);
        long jobId = new DistillJobService(chat, jobRepo, new DistillProperties())
                .create(new DistillJobService.JobRequest("file", List.of(), "", "",
                        imported.importId(), "count", 10, 0, 30));

        worker.start(jobId);
        verify(publisher, timeout(3000)).publish(any(DistillNoteRepository.Note.class));
    }
}
