package com.example.qqbot.persistence;

import org.springframework.beans.factory.annotation.Qualifier;
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

    /** 用评测库自己的连接；主库连接由 @Primary 保证给其他仓储 */
    public EvalRepository(@Qualifier("evalJdbc") Jdbc jdbc) {
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
                + " case_filter TEXT,"
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

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_citation ("
                + " run_id INTEGER NOT NULL,"
                + " sentence_index INTEGER NOT NULL,"
                + " sentence TEXT NOT NULL,"
                + " block_ids TEXT NOT NULL,"
                + " chars INTEGER NOT NULL,"
                + " PRIMARY KEY (run_id, sentence_index))");
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
                + " error TEXT,"
                + " created_at TEXT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_tool_run ON eval_tool_call(run_id, call_index)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_review_task ("
                + " id INTEGER PRIMARY KEY,"
                + " batch_id INTEGER NOT NULL,"
                + " run_id INTEGER NOT NULL,"
                + " reason TEXT,"
                + " priority TEXT NOT NULL DEFAULT 'normal',"
                + " status TEXT NOT NULL DEFAULT 'pending',"
                + " assignee TEXT,"
                + " note TEXT,"
                + " created_at TEXT NOT NULL,"
                + " claimed_at TEXT,"
                + " submitted_at TEXT,"
                + " due_at TEXT)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_review_open"
                + " ON eval_review_task(run_id) WHERE status IN ('pending','claimed')");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_review_status"
                + " ON eval_review_task(status, priority, created_at)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_baseline ("
                + " id INTEGER PRIMARY KEY,"
                + " name TEXT NOT NULL,"
                + " dataset_version_id INTEGER NOT NULL,"
                + " batch_id INTEGER NOT NULL,"
                + " metrics_json TEXT NOT NULL,"
                + " status TEXT NOT NULL DEFAULT 'candidate',"
                + " created_at TEXT NOT NULL,"
                + " promoted_by TEXT,"
                + " promoted_at TEXT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_baseline_version"
                + " ON eval_baseline(dataset_version_id, status)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_price ("
                + " id INTEGER PRIMARY KEY,"
                + " provider TEXT NOT NULL DEFAULT '',"
                + " model TEXT NOT NULL,"
                + " input_per_million REAL NOT NULL DEFAULT 0,"
                + " output_per_million REAL NOT NULL DEFAULT 0,"
                + " currency TEXT NOT NULL DEFAULT 'CNY',"
                + " updated_at TEXT NOT NULL,"
                + " UNIQUE(provider, model))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_alert_rule ("
                + " id INTEGER PRIMARY KEY,"
                + " name TEXT NOT NULL,"
                + " metric TEXT NOT NULL,"
                + " comparator TEXT NOT NULL DEFAULT 'lt',"
                + " threshold REAL NOT NULL,"
                + " severity TEXT NOT NULL DEFAULT 'warn',"
                + " enabled INTEGER NOT NULL DEFAULT 1,"
                + " created_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS eval_alert_event ("
                + " id INTEGER PRIMARY KEY,"
                + " rule_id INTEGER NOT NULL,"
                + " batch_id INTEGER NOT NULL,"
                + " metric_value REAL NOT NULL,"
                + " threshold REAL NOT NULL,"
                + " severity TEXT NOT NULL,"
                + " status TEXT NOT NULL DEFAULT 'open',"
                + " created_at TEXT NOT NULL,"
                + " acked_at TEXT,"
                + " note TEXT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_alert_event_status"
                + " ON eval_alert_event(status, created_at)");

        // 老库补列：CREATE TABLE IF NOT EXISTS 对已存在的表什么都不做
        ensureColumn("eval_batch", "gate_reason", "gate_reason TEXT");
        ensureColumn("eval_run", "tokens_in", "tokens_in INTEGER DEFAULT 0");
        ensureColumn("eval_run", "tokens_out", "tokens_out INTEGER DEFAULT 0");
        ensureColumn("eval_run", "cost_micro", "cost_micro INTEGER DEFAULT 0");
        ensureColumn("eval_tool_call", "error", "error TEXT");
        ensureColumn("eval_batch", "case_filter", "case_filter TEXT");
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
                          String expected, String note, boolean enabled, String dimension) {
    }

    public void upsertCase(long datasetId, String caseKey, String category, String question,
                           String expected, String note, String dimension, String now) {
        jdbc.update("INSERT INTO eval_case (dataset_id, case_key, category, dimension, question, expected,"
                + " note, enabled, created_at, updated_at) VALUES (?,?,?,?,?,?,?,1,?,?)"
                + " ON CONFLICT(dataset_id, case_key) DO UPDATE SET"
                + " category=excluded.category, dimension=excluded.dimension, question=excluded.question,"
                + " expected=excluded.expected, note=excluded.note, updated_at=excluded.updated_at",
                datasetId, caseKey, category, dimension, question, expected, note, now, now);
    }

    public List<CaseRow> listCases(long datasetId, int limit, int offset) {
        return jdbc.query("SELECT id, case_key, category, question, expected, note, enabled, dimension"
                + " FROM eval_case WHERE dataset_id=? ORDER BY id LIMIT ? OFFSET ?",
                rs -> new CaseRow(rs.longOf("id"), rs.str("case_key"), rs.str("category"),
                        rs.str("question"), rs.str("expected"), rs.str("note"),
                        rs.bool("enabled"), rs.str("dimension")),
                datasetId, limit, offset);
    }

    /** 改一条用例；不动 case_key，避免已冻结版本与历史 run 的引用错位 */
    public boolean updateCase(long datasetId, long caseId, String question, String expected,
                              String note, boolean enabled, String now) {
        return jdbc.update("UPDATE eval_case SET question=?, expected=?, note=?, enabled=?,"
                + " updated_at=? WHERE id=? AND dataset_id=?",
                question, expected, note, enabled, now, caseId, datasetId) == 1;
    }

    public boolean deleteCase(long datasetId, long caseId) {
        return jdbc.update("DELETE FROM eval_case WHERE id=? AND dataset_id=?", caseId, datasetId) == 1;
    }

    public long countCases(long datasetId) {
        return jdbc.count("SELECT COUNT(*) FROM eval_case WHERE dataset_id=?", datasetId);
    }

    public CaseRow findCase(long datasetId, String caseKey) {
        return jdbc.queryOne("SELECT id, case_key, category, question, expected, note, enabled, dimension"
                        + " FROM eval_case WHERE dataset_id=? AND case_key=?",
                rs -> new CaseRow(rs.longOf("id"), rs.str("case_key"), rs.str("category"),
                        rs.str("question"), rs.str("expected"), rs.str("note"), rs.bool("enabled"),
                        rs.str("dimension")),
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
                           String statsJson, String createdAt, String startedAt, String finishedAt, String caseFilter) {
    }

    public long insertBatch(String name, long datasetId, long versionId, String targetJson, String mode,
                            int concurrency, int repeatTimes, double sampleRate, String caseFilter,
                            String createdBy, String now) {
        return jdbc.insert("INSERT INTO eval_batch (name, dataset_id, dataset_version_id, target_json, mode,"
                        + " concurrency, repeat_times, sample_rate, status, gate_status, item_total,"
                        + " case_filter, created_by, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,'draft','pending',0,?,?,?)",
                name, datasetId, versionId, targetJson, mode, concurrency, repeatTimes, sampleRate,
                caseFilter, createdBy, now);
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
                + " created_at, started_at, finished_at, case_filter FROM eval_batch WHERE id=?",
                rs -> new BatchRow(rs.longOf("id"), rs.str("name"), rs.longOf("dataset_id"),
                        rs.longOf("dataset_version_id"), rs.str("target_json"), rs.str("mode"),
                        rs.intOf("concurrency"), rs.intOf("repeat_times"), rs.dbl("sample_rate"),
                        rs.str("status"), rs.str("gate_status"), rs.str("gate_reason"),
                        rs.intOf("item_total"), rs.str("stats_json"), rs.str("created_at"),
                        rs.str("started_at"), rs.str("finished_at"), rs.str("case_filter")), id);
    }

    public List<BatchRow> listBatches(int limit, int offset) {
        return jdbc.query("SELECT id, name, dataset_id, dataset_version_id, target_json, mode, concurrency,"
                + " repeat_times, sample_rate, status, gate_status, gate_reason, item_total, stats_json,"
                + " created_at, started_at, finished_at, case_filter FROM eval_batch"
                + " ORDER BY id DESC LIMIT ? OFFSET ?",
                rs -> new BatchRow(rs.longOf("id"), rs.str("name"), rs.longOf("dataset_id"),
                        rs.longOf("dataset_version_id"), rs.str("target_json"), rs.str("mode"),
                        rs.intOf("concurrency"), rs.intOf("repeat_times"), rs.dbl("sample_rate"),
                        rs.str("status"), rs.str("gate_status"), rs.str("gate_reason"),
                        rs.intOf("item_total"), rs.str("stats_json"), rs.str("created_at"),
                        rs.str("started_at"), rs.str("finished_at"), rs.str("case_filter")), limit, offset);
    }

    public record PendingCase(long caseId, String caseKey, String category, String question,
                              String expected, String note, String dimension) {
    }

    /** 批次建好之后要跑的用例 —— 只取启用的，按 id 稳定排序 */
    public List<PendingCase> pendingCases(long datasetId) {
        return jdbc.query("SELECT id, case_key, category, question, expected, note, dimension"
                        + " FROM eval_case WHERE dataset_id=? AND enabled=1 ORDER BY id",
                rs -> new PendingCase(rs.longOf("id"), rs.str("case_key"), rs.str("category"),
                        rs.str("question"), rs.str("expected"), rs.str("note"),
                        rs.str("dimension")), datasetId);
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

    public record JudgeRow(String dimension, String judgeType, String verdict, double score, String reason,
                    String tag) {
    }

    public List<JudgeRow> judgesOf(long runId) {
        return jdbc.query("SELECT dimension, judge_type, verdict, score, reason, tag FROM eval_judge"
                        + " WHERE run_id=? ORDER BY id",
                rs -> new JudgeRow(rs.str("dimension"), rs.str("judge_type"), rs.str("verdict"),
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

    public record RunLite(long id, String status, long latencyMs, int hitCount,
                          long tokensIn, long tokensOut, long costMicro) {
    }

    public List<RunLite> runsOfBatch(long batchId) {
        return jdbc.query("SELECT id, status, latency_ms, hit_count, tokens_in, tokens_out,"
                        + " cost_micro FROM eval_run WHERE batch_id=?",
                rs -> new RunLite(rs.longOf("id"), rs.str("status"), rs.longOf("latency_ms"),
                        rs.intOf("hit_count"), rs.longOf("tokens_in"), rs.longOf("tokens_out"),
                        rs.longOf("cost_micro")), batchId);
    }

    /* ==================== 一次执行的整体写入 ==================== */

    /**
     * 把一条 run 的全部留痕写完，**一个事务**。
     *
     * <p>半截数据在过程页上会变成"有结论、没过程"，比完全没有更难判断 ——
     * 那一屏看起来像"模型没检索"，其实是写库失败。
     */
    public record CitationWrite(int sentenceIndex, String sentence, String blockIds, int chars) {
    }

    public record CitationRow(int sentenceIndex, String sentence, String blockIds, int chars) {
    }

    public void saveExecution(long runId, List<SpanRow> spans, RetrievalRow retrieval,
                              List<JudgeWrite> judges, List<FailureWrite> failures,
                              List<CitationWrite> citations, String now) {
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
            if (citations != null && !citations.isEmpty()) {
                List<Object[]> rows = new ArrayList<>(citations.size());
                for (CitationWrite c : citations) {
                    rows.add(new Object[]{runId, c.sentenceIndex(), c.sentence(), c.blockIds(), c.chars()});
                }
                jdbc.batch("INSERT OR REPLACE INTO eval_citation (run_id, sentence_index, sentence,"
                        + " block_ids, chars) VALUES (?,?,?,?,?)", rows);
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
                                Long latencyMs, Integer resultChars, String error) {
    }

    public void insertToolCalls(long runId, List<ToolCallWrite> calls, String now) {
        if (calls == null || calls.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(calls.size());
        for (ToolCallWrite c : calls) {
            rows.add(new Object[]{runId, c.callIndex(), c.name(), c.args(), c.status(),
                    c.latencyMs(), c.resultChars(), c.error(), now});
        }
        jdbc.batch("INSERT INTO eval_tool_call (run_id, call_index, name, args, status, latency_ms,"
                + " result_chars, error, created_at) VALUES (?,?,?,?,?,?,?,?,?)", rows);
    }

    public record ToolCallRow(int callIndex, String name, String args, String status,
                              Long latencyMs, Integer resultChars, String error) {
    }

    public List<CitationRow> citationsOf(long runId) {
        return jdbc.query("SELECT sentence_index, sentence, block_ids, chars FROM eval_citation"
                        + " WHERE run_id=? ORDER BY sentence_index",
                rs -> new CitationRow(rs.intOf("sentence_index"), rs.str("sentence"),
                        rs.str("block_ids"), rs.intOf("chars")), runId);
    }

    public List<ToolCallRow> toolCallsOf(long runId) {
        return jdbc.query("SELECT call_index, name, args, status, latency_ms, result_chars, error"
                        + " FROM eval_tool_call WHERE run_id=? ORDER BY call_index",
                rs -> new ToolCallRow(rs.intOf("call_index"), rs.str("name"), rs.str("args"),
                        rs.str("status"), nullableLong(rs, "latency_ms"),
                        rs.isNull("result_chars") ? null : rs.intOf("result_chars"),
                        rs.str("error")), runId);
    }

    /* ==================== 复核 ==================== */

    public record ReviewRow(long id, long runId, long batchId, String caseKey, String question,
                            String reason, String priority, String status, String assignee,
                            String createdAt, String dueAt) {
    }

    /** 同一条 run 只保留一个未关闭的复核任务，重复入队直接忽略 */
    public long enqueueReview(long batchId, long runId, String reason, String priority,
                              String dueAt, String now) {
        try {
            return jdbc.insert("INSERT INTO eval_review_task (batch_id, run_id, reason, priority,"
                            + " status, created_at, due_at) VALUES (?,?,?,?,'pending',?,?)",
                    batchId, runId, reason, priority, now, dueAt);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public List<ReviewRow> listReviews(String status, int limit) {
        String where = status == null || status.isBlank() ? "" : " WHERE t.status=?";
        String sql = "SELECT t.id, t.run_id, t.batch_id, t.reason, t.priority, t.status, t.assignee,"
                + " t.created_at, t.due_at, c.case_key, c.question"
                + " FROM eval_review_task t"
                + " JOIN eval_run r ON r.id=t.run_id"
                + " LEFT JOIN eval_case c ON c.id=r.case_id" + where
                + " ORDER BY CASE t.priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 ELSE 2 END,"
                + " t.created_at DESC LIMIT ?";
        Jdbc.RowMapper<ReviewRow> mapper = rs -> new ReviewRow(rs.longOf("id"), rs.longOf("run_id"),
                rs.longOf("batch_id"), rs.str("case_key"), rs.str("question"), rs.str("reason"),
                rs.str("priority"), rs.str("status"), rs.str("assignee"), rs.str("created_at"),
                rs.str("due_at"));
        return where.isEmpty() ? jdbc.query(sql, mapper, limit)
                : jdbc.query(sql, mapper, status, limit);
    }

    public boolean claimReview(long id, String assignee, String now) {
        return jdbc.update("UPDATE eval_review_task SET status='claimed', assignee=?, claimed_at=?"
                + " WHERE id=? AND status='pending'", assignee, now, id) == 1;
    }

    public boolean submitReview(long id, String note, String now) {
        return jdbc.update("UPDATE eval_review_task SET status='submitted', note=?, submitted_at=?"
                + " WHERE id=? AND status IN ('pending','claimed')", note, now, id) == 1;
    }

    public ReviewRow findReview(long id) {
        List<ReviewRow> rows = jdbc.query("SELECT t.id, t.run_id, t.batch_id, t.reason, t.priority,"
                        + " t.status, t.assignee, t.created_at, t.due_at, c.case_key, c.question"
                        + " FROM eval_review_task t JOIN eval_run r ON r.id=t.run_id"
                        + " LEFT JOIN eval_case c ON c.id=r.case_id WHERE t.id=?",
                rs -> new ReviewRow(rs.longOf("id"), rs.longOf("run_id"), rs.longOf("batch_id"),
                        rs.str("case_key"), rs.str("question"), rs.str("reason"), rs.str("priority"),
                        rs.str("status"), rs.str("assignee"), rs.str("created_at"), rs.str("due_at")),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 过期清理：明细类表按 run 的创建时间删，结论类不删 */
    public int purgeDetailBefore(String cutoff) {
        int removed = 0;
        removed += jdbc.update("DELETE FROM eval_span WHERE run_id IN"
                + " (SELECT id FROM eval_run WHERE created_at < ?)", cutoff);
        removed += jdbc.update("DELETE FROM eval_retrieval WHERE run_id IN"
                + " (SELECT id FROM eval_run WHERE created_at < ?)", cutoff);
        removed += jdbc.update("DELETE FROM eval_tool_call WHERE run_id IN"
                + " (SELECT id FROM eval_run WHERE created_at < ?)", cutoff);
        return removed;
    }

    /* ==================== 基线 ==================== */

    public record BaselineRow(long id, String name, long datasetVersionId, long batchId,
                              String metricsJson, String status, String createdAt,
                              String promotedBy, String promotedAt) {
    }

    public long insertBaseline(String name, long versionId, long batchId, String metricsJson,
                               String now) {
        return jdbc.insert("INSERT INTO eval_baseline (name, dataset_version_id, batch_id,"
                        + " metrics_json, status, created_at) VALUES (?,?,?,?,'candidate',?)",
                name, versionId, batchId, metricsJson, now);
    }

    public List<BaselineRow> listBaselines(Long versionId, String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, name, dataset_version_id, batch_id,"
                + " metrics_json, status, created_at, promoted_by, promoted_at FROM eval_baseline WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (versionId != null) {
            sql.append(" AND dataset_version_id=?");
            args.add(versionId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), EvalRepository::mapBaseline, args.toArray());
    }

    public BaselineRow findBaseline(long id) {
        return jdbc.queryOne("SELECT id, name, dataset_version_id, batch_id, metrics_json, status,"
                + " created_at, promoted_by, promoted_at FROM eval_baseline WHERE id=?",
                EvalRepository::mapBaseline, id);
    }

    /** 同一版本只有一条激活基线：先退役旧的全新晋升 */
    public void activateBaseline(long id, long versionId, String by, String now) {
        jdbc.transaction(() -> {
            jdbc.update("UPDATE eval_baseline SET status='retired' WHERE dataset_version_id=?"
                    + " AND status='active'", versionId);
            jdbc.update("UPDATE eval_baseline SET status='active', promoted_by=?, promoted_at=?"
                    + " WHERE id=?", by, now, id);
            return null;
        });
    }

    public BaselineRow activeBaseline(long versionId) {
        return jdbc.queryOne("SELECT id, name, dataset_version_id, batch_id, metrics_json, status,"
                        + " created_at, promoted_by, promoted_at FROM eval_baseline"
                        + " WHERE dataset_version_id=? AND status='active' LIMIT 1",
                EvalRepository::mapBaseline, versionId);
    }

    private static BaselineRow mapBaseline(Jdbc.Row rs) {
        return new BaselineRow(rs.longOf("id"), rs.str("name"), rs.longOf("dataset_version_id"),
                rs.longOf("batch_id"), rs.str("metrics_json"), rs.str("status"),
                rs.str("created_at"), rs.str("promoted_by"), rs.str("promoted_at"));
    }

    public record FailureCase(long runId, String caseKey, String question, String dimension,
                              String tag, String reason) {
    }

    /** 失败归因下钻：按标签取受影响的用例，不传标签就取全部 */
    public List<FailureCase> failuresOfBatch(long batchId, String tag, int limit) {
        String sql = "SELECT f.run_id, f.tag_code, f.dimension, f.reason, c.case_key, c.question"
                + " FROM eval_failure f JOIN eval_run r ON r.id=f.run_id"
                + " LEFT JOIN eval_case c ON c.id=r.case_id"
                + " WHERE r.batch_id=?" + (tag == null || tag.isBlank() ? "" : " AND f.tag_code=?")
                + " ORDER BY f.id DESC LIMIT ?";
        Jdbc.RowMapper<FailureCase> mapper = rs -> new FailureCase(rs.longOf("run_id"),
                rs.str("case_key"), rs.str("question"), rs.str("dimension"), rs.str("tag_code"),
                rs.str("reason"));
        return tag == null || tag.isBlank()
                ? jdbc.query(sql, mapper, batchId, limit)
                : jdbc.query(sql, mapper, batchId, tag, limit);
    }

    /* ==================== 价目 ==================== */

    public record PriceRow(long id, String provider, String model, double inputPerMillion,
                           double outputPerMillion, String currency, String updatedAt) {
    }

    public void upsertPrice(String provider, String model, double inputPerMillion,
                            double outputPerMillion, String currency, String now) {
        jdbc.update("INSERT INTO eval_price (provider, model, input_per_million, output_per_million,"
                        + " currency, updated_at) VALUES (?,?,?,?,?,?)"
                        + " ON CONFLICT(provider, model) DO UPDATE SET"
                        + " input_per_million=excluded.input_per_million,"
                        + " output_per_million=excluded.output_per_million,"
                        + " currency=excluded.currency, updated_at=excluded.updated_at",
                provider == null ? "" : provider, model, inputPerMillion, outputPerMillion,
                currency == null || currency.isBlank() ? "CNY" : currency, now);
    }

    public List<PriceRow> listPrices() {
        return jdbc.query("SELECT id, provider, model, input_per_million, output_per_million,"
                        + " currency, updated_at FROM eval_price ORDER BY provider, model",
                EvalRepository::mapPrice);
    }

    public PriceRow findPrice(String provider, String model) {
        return jdbc.queryOne("SELECT id, provider, model, input_per_million, output_per_million,"
                        + " currency, updated_at FROM eval_price WHERE provider=? AND model=?",
                EvalRepository::mapPrice, provider == null ? "" : provider, model);
    }

    private static PriceRow mapPrice(Jdbc.Row rs) {
        return new PriceRow(rs.longOf("id"), rs.str("provider"), rs.str("model"),
                rs.dbl("input_per_million"), rs.dbl("output_per_million"), rs.str("currency"),
                rs.str("updated_at"));
    }

    /* ==================== 趋势与对比 ==================== */

    public List<BatchRow> batchesOfDataset(long datasetId, int limit) {
        return jdbc.query("SELECT id, name, dataset_id, dataset_version_id, target_json, mode, concurrency,"
                        + " repeat_times, sample_rate, status, gate_status, gate_reason, item_total,"
                        + " stats_json, created_at, started_at, finished_at, case_filter FROM eval_batch"
                        + " WHERE dataset_id=? AND status='completed' ORDER BY id DESC LIMIT ?",
                rs -> new BatchRow(rs.longOf("id"), rs.str("name"), rs.longOf("dataset_id"),
                        rs.longOf("dataset_version_id"), rs.str("target_json"), rs.str("mode"),
                        rs.intOf("concurrency"), rs.intOf("repeat_times"), rs.dbl("sample_rate"),
                        rs.str("status"), rs.str("gate_status"), rs.str("gate_reason"),
                        rs.intOf("item_total"), rs.str("stats_json"), rs.str("created_at"),
                        rs.str("started_at"), rs.str("finished_at"), rs.str("case_filter")), datasetId, limit);
    }

    public record CaseVerdict(long caseId, String caseKey, String question, String quality,
                              String rag, String safety, String evidence, String agent, String tag,
                              long runId) {
    }

    /** 每个用例第一次执行的结论，对比用 */
    public List<CaseVerdict> caseVerdicts(long batchId) {
        return jdbc.query("SELECT c.id AS case_id, c.case_key, c.question, r.id AS run_id,"
                        + " (SELECT j.verdict FROM eval_judge j WHERE j.run_id=r.id AND j.dimension='quality'"
                        + "  ORDER BY j.id DESC LIMIT 1) AS quality,"
                        + " (SELECT j.verdict FROM eval_judge j WHERE j.run_id=r.id AND j.dimension='rag'"
                        + "  ORDER BY j.id DESC LIMIT 1) AS rag,"
                        + " (SELECT j.verdict FROM eval_judge j WHERE j.run_id=r.id AND j.dimension='safety'"
                        + "  ORDER BY j.id DESC LIMIT 1) AS safety,"
                        + " (SELECT j.verdict FROM eval_judge j WHERE j.run_id=r.id AND j.dimension='evidence'"
                        + "  ORDER BY j.id DESC LIMIT 1) AS evidence,"
                        + " (SELECT j.verdict FROM eval_judge j WHERE j.run_id=r.id AND j.dimension='agent'"
                        + "  ORDER BY j.id DESC LIMIT 1) AS agent,"
                        + " (SELECT j.tag FROM eval_judge j WHERE j.run_id=r.id AND j.dimension='rag'"
                        + "  ORDER BY j.id DESC LIMIT 1) AS tag"
                        + " FROM eval_run r JOIN eval_case c ON c.id=r.case_id"
                        + " WHERE r.batch_id=? AND r.attempt=1 ORDER BY c.case_key",
                rs -> new CaseVerdict(rs.longOf("case_id"), rs.str("case_key"), rs.str("question"),
                        rs.str("quality"),
                        rs.str("rag"), rs.str("safety"), rs.str("evidence"), rs.str("agent"),
                        rs.str("tag"), rs.longOf("run_id")), batchId);
    }

    /* ==================== 告警 ==================== */

    public record AlertRule(long id, String name, String metric, String comparator,
                            double threshold, String severity, boolean enabled, String createdAt) {
    }

    public record AlertEvent(long id, long ruleId, String ruleName, long batchId, double metricValue,
                             double threshold, String severity, String status, String createdAt,
                             String ackedAt, String note) {
    }

    public long insertAlertRule(String name, String metric, String comparator, double threshold,
                                String severity, String now) {
        return jdbc.insert("INSERT INTO eval_alert_rule (name, metric, comparator, threshold, severity,"
                + " enabled, created_at) VALUES (?,?,?,?,?,1,?)",
                name, metric, comparator, threshold, severity, now);
    }

    public List<AlertRule> listAlertRules() {
        return jdbc.query("SELECT id, name, metric, comparator, threshold, severity, enabled, created_at"
                        + " FROM eval_alert_rule ORDER BY id DESC",
                rs -> new AlertRule(rs.longOf("id"), rs.str("name"), rs.str("metric"),
                        rs.str("comparator"), rs.dbl("threshold"), rs.str("severity"),
                        rs.bool("enabled"), rs.str("created_at")));
    }

    public void setAlertRuleEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE eval_alert_rule SET enabled=? WHERE id=?", enabled, id);
    }

    public long insertAlertEvent(long ruleId, long batchId, double value, double threshold,
                                 String severity, String now) {
        return jdbc.insert("INSERT INTO eval_alert_event (rule_id, batch_id, metric_value, threshold,"
                + " severity, status, created_at) VALUES (?,?,?,?,?,'open',?)",
                ruleId, batchId, value, threshold, severity, now);
    }

    public List<AlertEvent> listAlertEvents(String status, int limit) {
        String where = status == null || status.isBlank() ? "" : " WHERE e.status=?";
        String sql = "SELECT e.id, e.rule_id, e.batch_id, e.metric_value, e.threshold, e.severity,"
                + " e.status, e.created_at, e.acked_at, e.note, r.name AS rule_name"
                + " FROM eval_alert_event e LEFT JOIN eval_alert_rule r ON r.id=e.rule_id" + where
                + " ORDER BY e.id DESC LIMIT ?";
        Jdbc.RowMapper<AlertEvent> mapper = rs -> new AlertEvent(rs.longOf("id"), rs.longOf("rule_id"),
                rs.str("rule_name"), rs.longOf("batch_id"), rs.dbl("metric_value"), rs.dbl("threshold"),
                rs.str("severity"), rs.str("status"), rs.str("created_at"), rs.str("acked_at"),
                rs.str("note"));
        return status == null || status.isBlank()
                ? jdbc.query(sql, mapper, limit)
                : jdbc.query(sql, mapper, status, limit);
    }

    public boolean ackAlertEvent(long id, String note, String now) {
        return jdbc.update("UPDATE eval_alert_event SET status='acked', acked_at=?, note=?"
                + " WHERE id=? AND status='open'", now, note, id) == 1;
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
