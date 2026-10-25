package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 蒸馏任务与批次的数据访问。
 *
 * <p>状态落库，后台 worker 重启后可以接着跑；进程重启不会把进度丢掉。
 */
@Repository
public class DistillJobRepository {

    /** 一条任务 */
    public record Job(long id, String source, String groupIds, String timeFrom, String timeTo,
                      long importId, String batchMode, int batchSize, int overlap, int maxGapMinutes,
                      String status, int messageCount, int batchCount, String error,
                      String createdAt, String startedAt, String finishedAt) {
    }

    /** 一个批次 */
    public record Batch(long id, long jobId, int seq, long messageFrom, long messageTo,
                        int messageCount, int charCount, String status, int attempts, String error,
                        String model, long tokensIn, long tokensOut,
                        String createdAt, String startedAt, String finishedAt) {
    }

    /** 待写入的任务 */
    public record NewJob(String source, String groupIds, String timeFrom, String timeTo,
                         long importId, String batchMode, int batchSize, int overlap,
                         int maxGapMinutes, int messageCount, int batchCount, String createdAt) {
    }

    /** 待写入的批次 */
    public record NewBatch(int seq, long messageFrom, long messageTo,
                           int messageCount, int charCount) {
    }

    private static final String JOB_COLUMNS =
            "id, source, group_ids, time_from, time_to, import_id, batch_mode, batch_size,"
                    + " overlap, max_gap_minutes, status, message_count, batch_count, error,"
                    + " created_at, started_at, finished_at";

    private static final Jdbc.RowMapper<Job> JOB = r -> new Job(
            r.longOf("id"), r.str("source"), r.str("group_ids"), r.str("time_from"),
            r.str("time_to"), r.longOf("import_id"), r.str("batch_mode"), r.intOf("batch_size"),
            r.intOf("overlap"), r.intOf("max_gap_minutes"), r.str("status"),
            r.intOf("message_count"), r.intOf("batch_count"), r.str("error"),
            r.str("created_at"), r.str("started_at"), r.str("finished_at"));

    private static final Jdbc.RowMapper<Batch> BATCH = r -> new Batch(
            r.longOf("id"), r.longOf("job_id"), r.intOf("seq"), r.longOf("message_from"),
            r.longOf("message_to"), r.intOf("message_count"), r.intOf("char_count"),
            r.str("status"), r.intOf("attempts"), r.str("error"), r.str("model"),
            r.longOf("tokens_in"), r.longOf("tokens_out"),
            r.str("created_at"), r.str("started_at"), r.str("finished_at"));

    private final Jdbc jdbc;

    public DistillJobRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS distill_job ("
                + " id              INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " source          TEXT NOT NULL DEFAULT 'live',"
                + " group_ids       TEXT NOT NULL DEFAULT '',"
                + " time_from       TEXT NOT NULL DEFAULT '',"
                + " time_to         TEXT NOT NULL DEFAULT '',"
                + " import_id       INTEGER NOT NULL DEFAULT 0,"
                + " batch_mode      TEXT NOT NULL DEFAULT 'count',"
                + " batch_size      INTEGER NOT NULL DEFAULT 80,"
                + " overlap         INTEGER NOT NULL DEFAULT 0,"
                + " max_gap_minutes INTEGER NOT NULL DEFAULT 30,"
                + " status          TEXT NOT NULL DEFAULT 'ready',"
                + " message_count   INTEGER NOT NULL DEFAULT 0,"
                + " batch_count     INTEGER NOT NULL DEFAULT 0,"
                + " error           TEXT NOT NULL DEFAULT '',"
                + " created_at      TEXT NOT NULL,"
                + " started_at      TEXT NOT NULL DEFAULT '',"
                + " finished_at     TEXT NOT NULL DEFAULT '')");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_distill_job_status ON distill_job(status)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS distill_batch ("
                + " id           INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " job_id       INTEGER NOT NULL,"
                + " seq          INTEGER NOT NULL,"
                + " message_from INTEGER NOT NULL DEFAULT 0,"
                + " message_to   INTEGER NOT NULL DEFAULT 0,"
                + " message_count INTEGER NOT NULL DEFAULT 0,"
                + " char_count   INTEGER NOT NULL DEFAULT 0,"
                + " status       TEXT NOT NULL DEFAULT 'pending',"
                + " attempts     INTEGER NOT NULL DEFAULT 0,"
                + " error        TEXT NOT NULL DEFAULT '',"
                + " model        TEXT NOT NULL DEFAULT '',"
                + " tokens_in    INTEGER NOT NULL DEFAULT 0,"
                + " tokens_out   INTEGER NOT NULL DEFAULT 0,"
                + " created_at   TEXT NOT NULL,"
                + " started_at   TEXT NOT NULL DEFAULT '',"
                + " finished_at  TEXT NOT NULL DEFAULT '')");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_distill_batch_job_seq"
                + " ON distill_batch(job_id, seq)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_distill_batch_status"
                + " ON distill_batch(status)");
    }

    /** 插入任务并写入全部批次 —— 一个事务，避免"任务在、批次缺" */
    public long createJob(NewJob job, List<NewBatch> batches) {
        return jdbc.transaction(() -> {
            long id = jdbc.insert("INSERT INTO distill_job"
                            + " (source, group_ids, time_from, time_to, import_id, batch_mode,"
                            + " batch_size, overlap, max_gap_minutes, status, message_count,"
                            + " batch_count, error, created_at, started_at, finished_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    job.source(), job.groupIds(), job.timeFrom(), job.timeTo(), job.importId(),
                    job.batchMode(), job.batchSize(), job.overlap(), job.maxGapMinutes(),
                    "ready", job.messageCount(), job.batchCount(), "", job.createdAt(), "", "");
            if (id <= 0) {
                throw new IllegalStateException("写入蒸馏任务失败");
            }
            List<Object[]> rows = new ArrayList<>();
            for (NewBatch b : batches) {
                rows.add(new Object[]{id, b.seq(), b.messageFrom(), b.messageTo(),
                        b.messageCount(), b.charCount(), "pending", 0, "", "", 0, 0,
                        job.createdAt(), "", ""});
            }
            jdbc.batch("INSERT INTO distill_batch"
                            + " (job_id, seq, message_from, message_to, message_count, char_count,"
                            + " status, attempts, error, model, tokens_in, tokens_out,"
                            + " created_at, started_at, finished_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows);
            return id;
        });
    }

    public Job findJob(long id) {
        return jdbc.queryOne("SELECT " + JOB_COLUMNS + " FROM distill_job WHERE id = ?", JOB, id);
    }

    public List<Job> recentJobs(int limit) {
        int capped = Math.min(Math.max(1, limit), 200);
        return jdbc.query("SELECT " + JOB_COLUMNS
                + " FROM distill_job ORDER BY id DESC LIMIT ?", JOB, capped);
    }

    public List<Batch> batchesOf(long jobId) {
        return jdbc.query("SELECT * FROM distill_batch WHERE job_id = ? ORDER BY seq", BATCH, jobId);
    }

    /** 下一个待处理批次；没有返回 null */
    public Batch nextPending(long jobId) {
        return jdbc.queryOne("SELECT * FROM distill_batch WHERE job_id = ? AND status = 'pending'"
                + " ORDER BY seq LIMIT 1", BATCH, jobId);
    }

    public Batch findBatch(long id) {
        return jdbc.queryOne("SELECT * FROM distill_batch WHERE id = ?", BATCH, id);
    }

    public void markBatchRunning(long id, String startedAt) {
        jdbc.update("UPDATE distill_batch SET status='running', attempts=attempts+1,"
                + " started_at=?, error='' WHERE id=?", startedAt, id);
    }

    public void markBatchDone(long id, String model, long tokensIn, long tokensOut) {
        jdbc.update("UPDATE distill_batch SET status='done', finished_at=?, model=?,"
                        + " tokens_in=?, tokens_out=?, error='' WHERE id=?",
                java.time.Instant.now().toString(), model, tokensIn, tokensOut, id);
    }

    public void markBatchFailed(long id, String error) {
        jdbc.update("UPDATE distill_batch SET status='failed', finished_at=?, error=? WHERE id=?",
                java.time.Instant.now().toString(), truncate(error), id);
    }

    /** 自动重试：放回 pending，保留 attempts */
    public void requeueBatch(long id, String error) {
        jdbc.update("UPDATE distill_batch SET status='pending', error=? WHERE id=?",
                truncate(error), id);
    }

    /** 进程重启后把卡在 running 的批次放回 pending，避免永远转圈 */
    public int resetRunningBatches(long jobId) {
        return jdbc.update("UPDATE distill_batch SET status='pending', error='上次中断，已重新排队'"
                + " WHERE job_id = ? AND status = 'running'", jobId);
    }

    /** 重试：把失败的批次放回 pending */
    public int resetFailedBatches(long jobId) {
        return jdbc.update("UPDATE distill_batch SET status='pending', error=''"
                + " WHERE job_id = ? AND status = 'failed'", jobId);
    }

    public void updateJobStatus(long id, String status, String error) {
        jdbc.update("UPDATE distill_job SET status=?, error=?, started_at=CASE"
                        + " WHEN ?='running' AND (started_at IS NULL OR started_at='') THEN ?"
                        + " ELSE started_at END WHERE id=?",
                status, truncate(error), status, java.time.Instant.now().toString(), id);
    }

    public void finishJob(long id, String status, String error) {
        jdbc.update("UPDATE distill_job SET status=?, error=?, finished_at=? WHERE id=?",
                status, truncate(error), java.time.Instant.now().toString(), id);
    }

    public long countByStatus(long jobId, String status) {
        return jdbc.count("SELECT COUNT(*) FROM distill_batch WHERE job_id = ? AND status = ?",
                jobId, status);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    public long countJobs() {
        return jdbc.count("SELECT COUNT(*) FROM distill_job");
    }

    public long countBatches() {
        return jdbc.count("SELECT COUNT(*) FROM distill_batch");
    }
}
