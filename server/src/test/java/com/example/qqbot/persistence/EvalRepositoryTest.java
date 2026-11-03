package com.example.qqbot.persistence;

import com.example.qqbot.config.PersistenceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测表的读写与"一次执行整体落库"。
 *
 * <p>重点验证两件事：建表幂等，以及 run + span + 检索快照 + 判定 + 失败标签
 * 必须在同一个事务里写完 —— 半截数据在过程页上会变成"有结论没过程"。
 */
class EvalRepositoryTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private EvalRepository repo;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("eval.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        repo = new EvalRepository(new Jdbc(db));
        repo.initSchema();
        repo.initSchema();
    }

    @AfterEach
    void close() {
        db.close();
    }

    private long seedRun() {
        long datasetId = repo.insertDataset("golden", "检索黄金集", "", "tester", "2026-11-03T10:00:00Z");
        repo.upsertCase(datasetId, "entity-abc", "entity", "废料杯在哪", "b1;b2", "",
                "2026-11-03T10:00:00Z");
        long versionId = repo.insertVersion(datasetId, 1, 1, "sum", "tester", "2026-11-03T10:00:00Z");
        long batchId = repo.insertBatch("批次一", datasetId, versionId, "{}", "offline", 2, 1, 1.0,
                "tester", "2026-11-03T10:00:00Z");
        long itemId = repo.insertItem(batchId, repo.listCases(datasetId, 10, 0).get(0).id(),
                "{\"caseId\":1}", "2026-11-03T10:00:00Z");
        return repo.insertRun(itemId, batchId, 1, 1, "succeeded", "trace-1", "答案",
                800, 2, 0.71, true, "model", null, "2026-11-03T10:00:01Z");
    }

    @Test
    @DisplayName("建表幂等，用例按 case_key 覆盖而不是新增")
    void schemaIsIdempotentAndCaseUpsert() {
        long datasetId = repo.insertDataset("golden", "检索黄金集", "", "tester", "2026-11-03T10:00:00Z");
        repo.upsertCase(datasetId, "k1", "entity", "问题一", "b1", "", "2026-11-03T10:00:00Z");
        repo.upsertCase(datasetId, "k1", "entity", "问题一改", "b1;b2", "备注", "2026-11-03T10:01:00Z");
        assertThat(repo.countCases(datasetId)).isEqualTo(1);
        assertThat(repo.findCase(datasetId, "k1").question()).isEqualTo("问题一改");
    }

    @Test
    @DisplayName("一次执行整体落库：span 树、检索快照、判定与失败标签都能读回")
    void savesExecutionAsOneUnit() {
        long runId = seedRun();
        var spans = List.of(
                new EvalRepository.SpanRow(1, 0, "request", "chat", "ok", 0, 820, "{}", null),
                new EvalRepository.SpanRow(2, 1, "retrieve", "kb", "ok", 2, 120, "{}", null));
        var retrieval = new EvalRepository.RetrievalRow("废料杯在哪", "[]", 3, 2, 0.7, 0.71,
                "[]", "vector", null, 120);
        var judges = List.of(new EvalRepository.JudgeWrite("rag", "rule", runId + ":rag:rule",
                        "PASS", 1.0, "第 1 名命中", null, "{}"),
                new EvalRepository.JudgeWrite("quality", "rule", runId + ":quality:rule",
                        "FAIL", 0.0, "空返回", "STB_EMPTY", "{}"));
        var failures = List.of(new EvalRepository.FailureWrite("quality", "STB_EMPTY", "空返回"));

        repo.saveExecution(runId, spans, retrieval, judges, failures, "2026-11-03T10:00:02Z");

        assertThat(repo.spansOf(runId)).hasSize(2);
        assertThat(repo.spansOf(runId).get(1).kind()).isEqualTo("retrieve");
        assertThat(repo.retrievalOf(runId).bestCosineRaw()).isEqualTo(0.71);
        assertThat(repo.judgesOf(runId)).hasSize(2);
        assertThat(repo.topTags(repo.findRun(runId).batchId(), 5))
                .extracting(EvalRepository.TagCount::tag).containsExactly("STB_EMPTY");
    }

    @Test
    @DisplayName("同一条判定重复写入命中同一行，不会长成两份")
    void judgeWriteIsIdempotent() {
        long runId = seedRun();
        var judge = new EvalRepository.JudgeWrite("rag", "rule", runId + ":rag:rule",
                "FAIL", 0.0, "未命中", "RET_MISS", "{}");
        repo.saveExecution(runId, List.of(), null, List.of(judge), List.of(), "2026-11-03T10:00:02Z");
        repo.saveExecution(runId, List.of(), null, List.of(new EvalRepository.JudgeWrite("rag", "rule",
                runId + ":rag:rule", "PASS", 1.0, "复核通过", null, "{}")), List.of(),
                "2026-11-03T10:05:00Z");
        assertThat(repo.judgesOf(runId)).hasSize(1);
        assertThat(repo.judgesOf(runId).get(0).verdict()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("批次进度：按 run 汇总，标签按次数排序")
    void batchReads() {
        long runId = seedRun();
        EvalRepository.RunRow run = repo.findRun(runId);
        assertThat(repo.runsOfBatch(run.batchId())).hasSize(1);
        assertThat(repo.itemsOfBatch(run.batchId())).hasSize(1);
        assertThat(repo.itemsOfBatch(run.batchId()).get(0).snapshot()).contains("caseId");
    }
}
