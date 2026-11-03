package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测中台的持久化 —— {@code eval_*} 十一张表。
 *
 * <h2>为什么单独一套表，而不是复用 qa_stat</h2>
 * 线上问答记录回答的是"群友问了什么、我们答得怎样"，评测回答的是"同一批用例跑了几次、
 * 每次的过程与判定是什么"。后者的批次、版本、判定都是不可变对象，生命周期也不同：
 * 线上原文 90 天清理，评测结论要长期留着做趋势。混在一张表里，两边都会被对方的清理策略牵连。
 *
 * <h2>写入形态</h2>
 * 一次执行是"一条 run + 若干 span + 一条检索快照 + 若干判定"，
 * 必须整体提交：一半写进去的 run 在过程页上就是"有结论没有过程"，
 * 比没写更难看懂。所以 {@link #saveExecution} 在一个事务里做完。
 *
 * <p>⚠️ 单连接串行写是这台机器的硬约束：评测写入走批量，且并发上限压到 2，
 * 不让评测把群问答的写路径堵住。
 */
@Repository
public class EvalRepository {

    private final Jdbc jdbc;

    public EvalRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    /* ==================== 建表 ==================== */

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_dataset ("
                + " id INTEGER PRIMARY KEY,"
                + " slug TEXT NOT NULL UNIQUE,"
                + " name TEXT NOT NULL,"
                + " description TEXT,"
                + " status TEXT NOT NULL DEFAULT 'draft',"
                + " created_by TEXT,"
                + " created_at TEXT NOT NULL)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_dataset_version ("
                + " id INTEGER PRIMARY KEY,"
                + " dataset_id INTEGER NOT NULL REFERENCES eval_dataset(id),"
                + " version INTEGER NOT NULL,"
                + " case_count INTEGER NOT NULL,"
                + " checksum TEXT,"
                + " frozen_by TEXT,"
                + " frozen_at TEXT NOT NULL,"
                + " UNIQUE(dataset_id, version))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_case ("
                + " id INTEGER PRIMARY KEY,"
                + " dataset_id INTEGER NOT NULL REFERENCES eval_dataset(id),"
                + " case_key TEXT NOT NULL,"
                + " category TEXT NOT NULL,"
                + " dimension TEXT NOT NULL DEFAULT 'rag',"
                + " question TEXT NOT NULL,"
                + " expected TEXT,"
                + " note TEXT,"
                + " enabled INTEGER NOT NULL DEFAULT 1,"
                + " created_at TEXT NOT NULL,"
                + " updated_at TEXT NOT NULL,"
                + " UNIQUE(dataset_id, case_key))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_batch ("
                + " id INTEGER PRIMARY KEY,"
                + " name TEXT NOT NULL,"
                + " dataset_id INTEGER NOT NULL,"
                + " dataset_version_id INTEGER NOT NULL,"
                + " target_json TEXT NOT NULL,"
                + " mode TEXT NOT NULL DEFAULT 'offline',"
                + " concurrency INTEGER NOT NULL DEFAULT 2,"
                + " repeat_times INTEGER NOT NULL DEFAULT 1,"
                + " sample_rate REAL NOT NULL DEFAULT 1.0,"
                + " status TEXT NOT NULL DEFAULT 'draft',"
                + " gate_status TEXT NOT NULL DEFAULT 'pending',"
                + " gate_reason TEXT,"
                + " item_total INTEGER NOT NULL DEFAULT 0,"
                + " stats_json TEXT,"
                + " created_by TEXT,"
                + " created_at TEXT NOT NULL,"
                + " started_at TEXT,"
                + " finished_at TEXT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_batch_status ON eval_batch(status, created_at)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_item ("
                + " id INTEGER PRIMARY KEY,"
                + " batch_id INTEGER NOT NULL REFERENCES eval_batch(id),"
                + " case_id INTEGER NOT NULL,"
                + " case_snapshot TEXT NOT NULL,"
                + " status TEXT NOT NULL DEFAULT 'pending',"
                + " created_at TEXT NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_item_batch ON eval_item(batch_id, status)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_run ("
                + " id INTEGER PRIMARY KEY,"
                + " item_id INTEGER NOT NULL,"
                + " batch_id INTEGER NOT NULL,"
                + " case_id INTEGER NOT NULL,"
                + " attempt INTEGER NOT NULL DEFAULT 1,"
                + " status TEXT NOT NULL,"
                + " trace_id TEXT NOT NULL,"
                + " answer TEXT,"
                + " latency_ms INTEGER,"
                + " hit_count INTEGER DEFAULT 0,"
                + " best_cosine_raw REAL DEFAULT 0,"
                + " kb_enabled INTEGER DEFAULT 1,"
                + " model TEXT,"
                + " tokens_in INTEGER DEFAULT 0,"
                + " tokens_out INTEGER DEFAULT 0,"
                + " cost_micro INTEGER DEFAULT 0,"
                + " error_code TEXT,"
                + " created_at TEXT NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_run_batch ON eval_run(batch_id, status)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_span ("
                + " id INTEGER PRIMARY KEY,"
                + " run_id INTEGER NOT NULL,"
                + " seq INTEGER NOT NULL,"
                + " parent_seq INTEGER NOT NULL DEFAULT 0,"
                + " kind TEXT NOT NULL,"
                + " name TEXT,"
                + " status TEXT NOT NULL,"
                + " start_offset_ms INTEGER NOT NULL DEFAULT 0,"
                + " duration_ms INTEGER NOT NULL DEFAULT 0,"
                + " attrs TEXT,"
                + " error_code TEXT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_span_run ON eval_span(run_id, seq)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_retrieval ("
                + " id INTEGER PRIMARY KEY,"
                + " run_id INTEGER NOT NULL UNIQUE,"
                + " query TEXT,"
                + " terms TEXT,"
                + " vector_candidates INTEGER DEFAULT 0,"
                + " keyword_candidates INTEGER DEFAULT 0,"
                + " best_cosine REAL DEFAULT 0,"
                + " best_cosine_raw REAL DEFAULT 0,"
                + " hits TEXT,"
                + " sources TEXT,"
                + " empty_reason TEXT,"
                + " latency_ms INTEGER DEFAULT 0)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_judge ("
                + " id INTEGER PRIMARY KEY,"
                + " run_id INTEGER NOT NULL,"
                + " dimension TEXT NOT NULL,"
                + " judge_type TEXT NOT NULL,"
                + " judge_key TEXT NOT NULL UNIQUE,"
                + " verdict TEXT NOT NULL,"
                + " score REAL NOT NULL DEFAULT 0,"
                + " reason TEXT,"
                + " tag TEXT,"
                + " evidence TEXT,"
                + " created_at TEXT NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_judge_run ON eval_judge(run_id, dimension)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_failure ("
                + " id INTEGER PRIMARY KEY,"
                + " run_id INTEGER NOT NULL,"
                + " dimension TEXT,"
                + " tag_code TEXT NOT NULL,"
                + " reason TEXT,"
                + " created_at TEXT NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_failure_run ON eval_failure(run_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_failure_tag ON eval_failure(tag_code)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_tool_call ("
                + " id INTEGER PRIMARY KEY,"
                + " run_id INTEGER NOT NULL,"
                + " call_index INTEGER NOT NULL,"
                + " name TEXT NOT NULL,"
                + " args TEXT,"
                + " status TEXT NOT NULL DEFAULT 'unknown',"
                + " latency_ms INTEGER,"
                + " result_chars INTEGER,"
                + " created_at TEXT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_tool_run ON eval_tool_call(run_id, call_index)");

        // 老库补列：CREATE TABLE IF NOT EXISTS 对已存在的表什么都不做
        ensureColumn("eval_batch", "gate_reason", "gate_reason TEXT");
        ensureColumn("eval_run", "tokens_in", "tokens_in INTEGER DEFAULT 0");
        ensureColumn("eval_run", "tokens_out", "tokens_out INTEGER DEFAULT 0");
        ensureColumn("eval_run", "cost_micro", "cost_micro INTEGER DEFAULT 0");
    }

    /** 幂等补列 —— 与 QaStoreRepository 同一套做法，库要长期保留不能删表重建 */
    private void ensureColumn(String table, String column, String ddl) {
        boolean exists;
        try {
            exists = jdbc.query("PRAGMA table_info(" + table + ")", rs -> rs.str("name")).contains(column);
        } catch (RuntimeException e) {
            return;
        }
        if (!exists) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + ddl);
        }
    }

    /* ==================== 用例集 ==================== */

    public record DatasetRow(long id, String slug, String name, String description, String status,
                             String createdAt, long caseCount, long versionCount) {
    }

    public long insertDataset(String slug, String name, String description, String createdBy, String now) {
        return jdbc.insert("INSERT INTO eval_dataset (slug, name, description, status, created_by, created_at)"
                + " VALUES (?,?,?,'draft',?,?)", slug, name, description, createdBy, now);
    }

    public List<DatasetRow> listDatasets() {
        return jdbc.query("SELECT d.id, d.slug, d.name, d.description, d.status, d.created_at,"
                + " (SELECT COUNT(*) FROM eval_case c WHERE c.dataset_id=d.id) AS case_count,"
                + " (SELECT COUNT(*) FROM eval_dataset_version v WHERE v.dataset_id=d.id) AS version_count"
                + " FROM eval_dataset d ORDER BY d.id DESC", rs -> new DatasetRow(
                rs.longOf("id"), rs.str("slug"), rs.str("name"),
                rs.str("description"), rs.str("status"), rs.str("created_at"),
                rs.longOf("case_count"), rs.longOf("version_count")));
    }

    public DatasetRow findDataset(long id) {
        return jdbc.queryOne("SELECT d.id, d.slug, d.name, d.description, d.status, d.created_at,"
                + " (SELECT COUNT(*) FROM eval_case c WHERE c.dataset_id=d.id) AS case_count,"
                + " (SELECT COUNT(*) FROM eval_dataset_version v WHERE v.dataset_id=d.id) AS version_count"
                + " FROM eval_dataset d WHERE d.id=?", rs -> new DatasetRow(
                rs.longOf("id"), rs.str("slug"), rs.str("name"),
                rs.str("description"), rs.str("status"), rs.str("created_at"),
                rs.longOf("case_count"), rs.longOf("version_count")), id);
    }

    public record CaseRow(long id, String caseKey, String category, String question,
                          String expected, String note, boolean enabled) {
    }

    public void upsertCase(long datasetId, String caseKey, String category, String question,
                           String expected, String note, String now) {
        jdbc.update("INSERT INTO eval_case (dataset_id, case_key, category, dimension, question, expected,"
                + " note, enabled, created_at, updated_at) VALUES (?,?,?,'rag',?,?,?,1,?,?)"
                + " ON CONFLICT(dataset_id, case_key) DO UPDATE SET"
                + " category=excluded.category, question=excluded.question,"
                + " expected=excluded.expected, note=excluded.note, updated_at=excluded.updated_at",
                datasetId, caseKey, category, question, expected, note, now, now);
    }

    public List<CaseRow> listCases(long datasetId, int limit, int offset) {
        return jdbc.query("SELECT id, case_key, category, question, expected, note, enabled"
                + " FROM eval_case WHERE dataset_id=? ORDER BY id LIMIT ? OFFSET ?",
                rs -> new CaseRow(rs.longOf("id"), rs.str("case_key"), rs.str("category"),
                        rs.str("question"), rs.str("expected"), rs.str("note"),
                        rs.bool("enabled")),
                datasetId, limit, offset);
    }

    public long countCases(long datasetId) {
        return jdbc.count("SELECT COUNT(*) FROM eval_case WHERE dataset_id=?", datasetId);
    }

    public CaseRow findCase(long datasetId, String caseKey) {
        return jdbc.queryOne("SELECT id, case_key, category, question, expected, note, enabled"
                        + " FROM eval_case WHERE dataset_id=? AND case_key=?",
                rs -> new CaseRow(rs.longOf("id"), rs.str("case_key"), rs.str("category"),
                        rs.str("question"), rs.str("expected"), rs.str("note"), rs.bool("enabled")),
                datasetId, caseKey);
    }

    public record VersionRow(long id, int version, int caseCount, String checksum, String frozenAt) {
    }

    public long nextVersion(long datasetId) {
        long v = jdbc.count("SELECT COALESCE(MAX(version),0) FROM eval_dataset_version WHERE dataset_id=?", datasetId);
        return v + 1;
    }

    public long insertVersion(long datasetId, int version, int caseCount, String checksum,
                              String frozenBy, String now) {
        return jdbc.insert("INSERT INTO eval_dataset_version (dataset_id, version, case_count, checksum,"
                        + " frozen_by, frozen_at) VALUES (?,?,?,?,?,?)",
                datasetId, version, caseCount, checksum, frozenBy, now);
    }

    public void markDatasetFrozen(long datasetId) {
        jdbc.update("UPDATE eval_dataset SET status='frozen' WHERE id=?", datasetId);
    }

    public List<VersionRow> listVersions(long datasetId) {
        return jdbc.query("SELECT id, version, case_count, checksum, frozen_at FROM eval_dataset_version"
                + " WHERE dataset_id=? ORDER BY version DESC",
                rs -> new VersionRow(rs.longOf("id"), rs.intOf("version"), rs.intOf("case_count"),
                        rs.str("checksum"), rs.str("frozen_at")), datasetId);
    }

    /* ==================== 批次与执行 ==================== */

    public record BatchRow(long id, String name, long datasetId, long datasetVersionId, String targetJson,
                           String mode, int concurrency, int repeatTimes, double sampleRate,
                           String status, String gateStatus, String gateReason, int itemTotal,
                           String statsJson, String createdAt, String startedAt, String finishedAt) {
    }

    public long insertBatch(String name, long datasetId, long versionId, String targetJson, String mode,
                            int concurrency, int repeatTimes, double sampleRate,
                            String createdBy, String now) {
        return jdbc.insert("INSERT INTO eval_batch (name, dataset_id, dataset_version_id, target_json, mode,"
                        + " concurrency, repeat_times, sample_rate, status, gate_status, item_total,"
                        + " created_by, created_at) VALUES (?,?,?,?,?,?,?,?,'draft','pending',0,?,?)",
                name, datasetId, versionId, targetJson, mode, concurrency, repeatTimes, sampleRate,
                createdBy, now);
    }

    public void markBatchQueued(long batchId, int itemTotal) {
        jdbc.update("UPDATE eval_batch SET status='queued', item_total=? WHERE id=?", itemTotal, batchId);
    }

    public void markBatchRunning(long batchId, String now) {
        jdbc.update("UPDATE eval_batch SET status='running', started_at=? WHERE id=?", now, batchId);
    }

    public void finishBatch(long batchId, String status, String statsJson, String gateStatus,
                            String gateReason, String now) {
        jdbc.update("UPDATE eval_batch SET status=?, stats_json=?, gate_status=?, gate_reason=?,"
                + " finished_at=? WHERE id=?", status, statsJson, gateStatus, gateReason, now, batchId);
    }

    public BatchRow findBatch(long id) {
        return jdbc.queryOne("SELECT id, name, dataset_id, dataset_version_id, target_json, mode, concurrency,"
                + " repeat_times, sample_rate, status, gate_status, gate_reason, item_total, stats_json,"
                + " created_at, started_at, finished_at FROM eval_batch WHERE id=?",
                rs -> new BatchRow(rs.longOf("id"), rs.str("name"), rs.longOf("dataset_id"),
                        rs.longOf("dataset_version_id"), rs.str("target_json"), rs.str("mode"),
                        rs.intOf("concurrency"), rs.intOf("repeat_times"), rs.dbl("sample_rate"),
                        rs.str("status"), rs.str("gate_status"), rs.str("gate_reason"),
                        rs.intOf("item_total"), rs.str("stats_json"), rs.str("created_at"),
                        rs.str("started_at"), rs.str("finished_at")), id);
    }

    public List<BatchRow> listBatches(int limit, int offset) {
        return jdbc.query("SELECT id, name, dataset_id, dataset_version_id, target_json, mode, concurrency,"
                + " repeat_times, sample_rate, status, gate_status, gate_reason, item_total, stats_json,"
                + " created_at, started_at, finished_at FROM eval_batch"
                + " ORDER BY id DESC LIMIT ? OFFSET ?",
                rs -> new BatchRow(rs.longOf("id"), rs.str("name"), rs.longOf("dataset_id"),
                        rs.longOf("dataset_version_id"), rs.str("target_json"), rs.str("mode"),
                        rs.intOf("concurrency"), rs.intOf("repeat_times"), rs.dbl("sample_rate"),
                        rs.str("status"), rs.str("gate_status"), rs.str("gate_reason"),
                        rs.intOf("item_total"), rs.str("stats_json"), rs.str("created_at"),
                        rs.str("started_at"), rs.str("finished_at")), limit, offset);
    }

    public record PendingCase(long caseId, String caseKey, String category, String question,
                              String expected, String note) {
    }

    /** 批次建好之后要跑的用例 —— 只取启用的，按 id 稳定排序 */
    public List<PendingCase> pendingCases(long datasetId) {
        return jdbc.query("SELECT id, case_key, category, question, expected, note FROM eval_case"
                        + " WHERE dataset_id=? AND enabled=1 ORDER BY id",
                rs -> new PendingCase(rs.longOf("id"), rs.str("case_key"), rs.str("category"),
                        rs.str("question"), rs.str("expected"), rs.str("note")), datasetId);
    }

    public long insertItem(long batchId, long caseId, String snapshot, String now) {
        return jdbc.insert("INSERT INTO eval_item (batch_id, case_id, case_snapshot, status, created_at)"
                + " VALUES (?,?,?,'pending',?)", batchId, caseId, snapshot, now);
    }

    public record ItemRow(long id, long caseId, String caseKey, String category, String question,
                          String status, Long runId, String runStatus, String verdict, String tag,
                          Long latencyMs, Integer hitCount, int attempts) {
    }

    public List<ItemRow> listItems(long batchId, int limit, int offset) {
        return jdbc.query("SELECT i.id, i.case_id, c.case_key, c.category, c.question, i.status,"
                + " r.id AS run_id, r.status AS run_status, r.latency_ms, r.hit_count,"
                + " (SELECT j.verdict FROM eval_judge j WHERE j.run_id=r.id AND j.dimension='rag') AS verdict,"
                + " (SELECT j.tag FROM eval_judge j WHERE j.run_id=r.id AND j.dimension='rag') AS tag,"
                + " (SELECT COUNT(*) FROM eval_run r2 WHERE r2.item_id=i.id) AS attempts"
                + " FROM eval_item i JOIN eval_case c ON c.id=i.case_id"
                + " LEFT JOIN eval_run r ON r.item_id=i.id AND r.attempt=1"
                + " WHERE i.batch_id=? ORDER BY i.id LIMIT ? OFFSET ?",
                rs -> new ItemRow(rs.longOf("id"), rs.longOf("case_id"), rs.str("case_key"),
                        rs.str("category"), rs.str("question"), rs.str("status"),
                        nullableLong(rs, "run_id"), rs.str("run_status"), rs.str("verdict"),
                        rs.str("tag"), nullableLong(rs, "latency_ms"),
                        rs.isNull("hit_count") ? null : rs.intOf("hit_count"), rs.intOf("attempts")),
                batchId, limit, offset);
    }

    public long countItems(long batchId) {
        return jdbc.count("SELECT COUNT(*) FROM eval_item WHERE batch_id=?", batchId);
    }

    public void updateItemStatus(long itemId, String status) {
        jdbc.update("UPDATE eval_item SET status=? WHERE id=?", status, itemId);
    }

    public record PendingItem(long id, long caseId, String snapshot) {
    }

    /** 批次里待执行的项 —— 带快照，执行期不回头看用例表 */
    public List<PendingItem> itemsOfBatch(long batchId) {
        return jdbc.query("SELECT id, case_id, case_snapshot FROM eval_item"
                        + " WHERE batch_id=? ORDER BY id",
                rs -> new PendingItem(rs.longOf("id"), rs.longOf("case_id"), rs.str("case_snapshot")),
                batchId);
    }

    public record RunRow(long id, long itemId, long batchId, long caseId, int attempt, String status,
                         String traceId, String answer, long latencyMs, int hitCount,
                         double bestCosineRaw, boolean kbEnabled, String createdAt,
                         long tokensIn, long tokensOut, long costMicro,
                         String caseKey, String category, String question) {
    }

    public long insertRun(long itemId, long batchId, long caseId, int attempt, String status,
                          String traceId, String answer, long latencyMs, int hitCount,
                          double bestCosineRaw, boolean kbEnabled, String model,
                          long tokensIn, long tokensOut, long costMicro,
                          String errorCode, String now) {
        return jdbc.insert("INSERT INTO eval_run (item_id, batch_id, case_id, attempt, status, trace_id,"
                        + " answer, latency_ms, hit_count, best_cosine_raw, kb_enabled, model,"
                        + " tokens_in, tokens_out, cost_micro, error_code, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                itemId, batchId, caseId, attempt, status, traceId, answer, latencyMs, hitCount,
                bestCosineRaw, kbEnabled, model, tokensIn, tokensOut, costMicro, errorCode, now);
    }

    public RunRow findRun(long id) {
        return jdbc.queryOne("SELECT r.id, r.item_id, r.batch_id, r.case_id, r.attempt, r.status,"
                + " r.trace_id, r.answer, r.latency_ms, r.hit_count, r.best_cosine_raw, r.kb_enabled,"
                + " r.created_at, r.tokens_in, r.tokens_out, r.cost_micro,"
                + " c.case_key, c.category, c.question"
                + " FROM eval_run r LEFT JOIN eval_case c ON c.id=r.case_id WHERE r.id=?",
                rs -> mapRun(rs), id);
    }

    private static RunRow mapRun(Jdbc.Row rs) {
        return new RunRow(rs.longOf("id"), rs.longOf("item_id"), rs.longOf("batch_id"),
                rs.longOf("case_id"), rs.intOf("attempt"), rs.str("status"),
                rs.str("trace_id"), rs.str("answer"), rs.longOf("latency_ms"),
                rs.intOf("hit_count"), rs.dbl("best_cosine_raw"),
                rs.bool("kb_enabled"), rs.str("created_at"),
                rs.longOf("tokens_in"), rs.longOf("tokens_out"), rs.longOf("cost_micro"),
                rs.str("case_key"), rs.str("category"), rs.str("question"));
    }

    public RunRow firstRunOfItem(long itemId) {
        return jdbc.queryOne("SELECT r.id, r.item_id, r.batch_id, r.case_id, r.attempt, r.status,"
                + " r.trace_id, r.answer, r.latency_ms, r.hit_count, r.best_cosine_raw, r.kb_enabled,"
                + " r.created_at, r.tokens_in, r.tokens_out, r.cost_micro,"
                + " c.case_key, c.category, c.question"
                + " FROM eval_run r LEFT JOIN eval_case c ON c.id=r.case_id"
                + " WHERE r.item_id=? ORDER BY r.attempt LIMIT 1",
                rs -> mapRun(rs), itemId);
    }

    public record SpanRow(int seq, int parentSeq, String kind, String name, String status,
                          long startOffsetMs, long durationMs, String attrs, String errorCode) {
    }

    public List<SpanRow> spansOf(long runId) {
        return jdbc.query("SELECT seq, parent_seq, kind, name, status, start_offset_ms, duration_ms, attrs,"
                + " error_code FROM eval_span WHERE run_id=? ORDER BY seq",
                rs -> new SpanRow(rs.intOf("seq"), rs.intOf("parent_seq"), rs.str("kind"),
                        rs.str("name"), rs.str("status"), rs.longOf("start_offset_ms"),
                        rs.longOf("duration_ms"), rs.str("attrs"), rs.str("error_code")), runId);
    }

    public record RetrievalRow(String query, String terms, int vectorCandidates, int keywordCandidates,
                               double bestCosine, double bestCosineRaw, String hits, String sources,
                               String emptyReason, long latencyMs) {
    }

    public RetrievalRow retrievalOf(long runId) {
        return jdbc.queryOne("SELECT query, terms, vector_candidates, keyword_candidates, best_cosine,"
                + " best_cosine_raw, hits, sources, empty_reason, latency_ms"
                + " FROM eval_retrieval WHERE run_id=?",
                rs -> new RetrievalRow(rs.str("query"), rs.str("terms"),
                        rs.intOf("vector_candidates"), rs.intOf("keyword_candidates"),
                        rs.dbl("best_cosine"), rs.dbl("best_cosine_raw"),
                        rs.str("hits"), rs.str("sources"),
                        rs.str("empty_reason"), rs.longOf("latency_ms")), runId);
    }

    public record JudgeRow(String dimension, String verdict, double score, String reason, String tag) {
    }

    public List<JudgeRow> judgesOf(long runId) {
        return jdbc.query("SELECT dimension, verdict, score, reason, tag FROM eval_judge"
                        + " WHERE run_id=? ORDER BY id",
                rs -> new JudgeRow(rs.str("dimension"), rs.str("verdict"),
                        rs.dbl("score"), rs.str("reason"), rs.str("tag")), runId);
    }

    public record JudgeLite(long runId, String dimension, String verdict, String tag) {
    }

    public List<JudgeLite> judgesOfBatch(long batchId) {
        return jdbc.query("SELECT j.run_id, j.dimension, j.verdict, j.tag FROM eval_judge j"
                        + " JOIN eval_run r ON r.id=j.run_id WHERE r.batch_id=?",
                rs -> new JudgeLite(rs.longOf("run_id"), rs.str("dimension"),
                        rs.str("verdict"), rs.str("tag")), batchId);
    }

    public record RunLite(long id, String status, long latencyMs, int hitCount) {
    }

    public List<RunLite> runsOfBatch(long batchId) {
        return jdbc.query("SELECT id, status, latency_ms, hit_count FROM eval_run WHERE batch_id=?",
                rs -> new RunLite(rs.longOf("id"), rs.str("status"),
                        rs.longOf("latency_ms"), rs.intOf("hit_count")), batchId);
    }

    /* ==================== 一次执行的整体写入 ==================== */

    /**
     * 把一条 run 的全部留痕写完，**一个事务**。
     *
     * <p>半截数据在过程页上会变成"有结论、没过程"，比完全没有更难判断 ——
     * 那一屏看起来像"模型没检索"，其实是写库失败。
     */
    public void saveExecution(long runId, List<SpanRow> spans, RetrievalRow retrieval,
                              List<JudgeWrite> judges, List<FailureWrite> failures, String now) {
        jdbc.transaction(() -> {
            if (spans != null && !spans.isEmpty()) {
                List<Object[]> rows = new ArrayList<>(spans.size());
                for (SpanRow s : spans) {
                    rows.add(new Object[]{runId, s.seq(), s.parentSeq(), s.kind(), s.name(), s.status(),
                            s.startOffsetMs(), s.durationMs(), s.attrs(), s.errorCode()});
                }
                jdbc.batch("INSERT INTO eval_span (run_id, seq, parent_seq, kind, name, status,"
                        + " start_offset_ms, duration_ms, attrs, error_code) VALUES (?,?,?,?,?,?,?,?,?,?)", rows);
            }
            if (retrieval != null) {
                jdbc.update("INSERT OR REPLACE INTO eval_retrieval (run_id, query, terms, vector_candidates,"
                                + " keyword_candidates, best_cosine, best_cosine_raw, hits, sources,"
                                + " empty_reason, latency_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        runId, retrieval.query(), retrieval.terms(), retrieval.vectorCandidates(),
                        retrieval.keywordCandidates(), retrieval.bestCosine(), retrieval.bestCosineRaw(),
                        retrieval.hits(), retrieval.sources(), retrieval.emptyReason(),
                        retrieval.latencyMs());
            }
            if (judges != null) {
                for (JudgeWrite j : judges) {
                    jdbc.update("INSERT INTO eval_judge (run_id, dimension, judge_type, judge_key, verdict,"
                                    + " score, reason, tag, evidence, created_at)"
                                    + " VALUES (?,?,?,?,?,?,?,?,?,?)"
                                    + " ON CONFLICT(judge_key) DO UPDATE SET verdict=excluded.verdict,"
                                    + " score=excluded.score, reason=excluded.reason, tag=excluded.tag,"
                                    + " evidence=excluded.evidence, created_at=excluded.created_at",
                            runId, j.dimension(), j.judgeType(), j.judgeKey(), j.verdict(), j.score(),
                            j.reason(), j.tag(), j.evidence(), now);
                }
            }
            if (failures != null && !failures.isEmpty()) {
                List<Object[]> rows = new ArrayList<>(failures.size());
                for (FailureWrite f : failures) {
                    rows.add(new Object[]{runId, f.dimension(), f.tagCode(), f.reason(), now});
                }
                jdbc.batch("INSERT INTO eval_failure (run_id, dimension, tag_code, reason, created_at)"
                        + " VALUES (?,?,?,?,?)", rows);
            }
            return null;
        });
    }

    public record JudgeWrite(String dimension, String judgeType, String judgeKey, String verdict,
                             double score, String reason, String tag, String evidence) {
    }

    public record FailureWrite(String dimension, String tagCode, String reason) {
    }

    /** 单条判定写入 —— 重复运行后补一致性结论时用 */
    public void upsertJudge(long runId, JudgeWrite j, String now) {
        jdbc.update("INSERT INTO eval_judge (run_id, dimension, judge_type, judge_key, verdict,"
                        + " score, reason, tag, evidence, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT(judge_key) DO UPDATE SET verdict=excluded.verdict,"
                        + " score=excluded.score, reason=excluded.reason, tag=excluded.tag,"
                        + " evidence=excluded.evidence, created_at=excluded.created_at",
                runId, j.dimension(), j.judgeType(), j.judgeKey(), j.verdict(), j.score(),
                j.reason(), j.tag(), j.evidence(), now);
    }

    public record ToolCallWrite(int callIndex, String name, String args, String status,
                                Long latencyMs, Integer resultChars) {
    }

    public void insertToolCalls(long runId, List<ToolCallWrite> calls, String now) {
        if (calls == null || calls.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(calls.size());
        for (ToolCallWrite c : calls) {
            rows.add(new Object[]{runId, c.callIndex(), c.name(), c.args(), c.status(),
                    c.latencyMs(), c.resultChars(), now});
        }
        jdbc.batch("INSERT INTO eval_tool_call (run_id, call_index, name, args, status, latency_ms,"
                + " result_chars, created_at) VALUES (?,?,?,?,?,?,?,?)", rows);
    }

    public record ToolCallRow(int callIndex, String name, String args, String status,
                              Long latencyMs, Integer resultChars) {
    }

    public List<ToolCallRow> toolCallsOf(long runId) {
        return jdbc.query("SELECT call_index, name, args, status, latency_ms, result_chars"
                        + " FROM eval_tool_call WHERE run_id=? ORDER BY call_index",
                rs -> new ToolCallRow(rs.intOf("call_index"), rs.str("name"), rs.str("args"),
                        rs.str("status"), nullableLong(rs, "latency_ms"),
                        rs.isNull("result_chars") ? null : rs.intOf("result_chars")), runId);
    }

    /* ==================== 统计 ==================== */

    public record TagCount(String tag, long count) {
    }

    public List<TagCount> topTags(long batchId, int limit) {
        return jdbc.query("SELECT f.tag_code AS tag, COUNT(*) AS n FROM eval_failure f"
                        + " JOIN eval_run r ON r.id=f.run_id WHERE r.batch_id=?"
                        + " GROUP BY f.tag_code ORDER BY n DESC LIMIT ?",
                rs -> new TagCount(rs.str("tag"), rs.longOf("n")), batchId, limit);
    }

    public long countRuns(long batchId) {
        return jdbc.count("SELECT COUNT(*) FROM eval_run WHERE batch_id=?", batchId);
    }

    private static Long nullableLong(Jdbc.Row rs, String column) {
        return rs.isNull(column) ? null : rs.longOf(column);
    }
}
