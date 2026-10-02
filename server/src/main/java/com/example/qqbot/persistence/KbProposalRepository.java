package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 知识库改进提案表（{@code kb_proposal}）的**数据访问**。
 *
 * <h2>三处不能顺手改掉的东西</h2>
 * <ul>
 *   <li>{@link #insertIgnore} 是 {@code INSERT OR IGNORE} + 部分唯一索引
 *       {@code idx_prop_src}：<b>同一条问答只出一条提案</b>。
 *       换成普通 INSERT 会在重复分析时炸掉（或产出重复提案）。</li>
 *   <li>{@link #pendingGoodCount} 与 {@link #pendingGoodAnswers} 里那个
 *       {@code source_stat_id > 0} <b>不能省</b>：它是部分索引的谓词，
 *       0 是「不是从问答来的提案」的哨兵值，去掉就与索引谓词不匹配。</li>
 *   <li>{@link #list} 与 {@link #pendingGoodAnswers} 的 {@code LIMIT} 在**这里夹紧** ——
 *       调用方传的是前端参数，不夹的话一条请求就能把整表拖出来。</li>
 * </ul>
 */
@Repository
public class KbProposalRepository {

    /** 一行提案 */
    public record Row(long id, String createdAt, String status, String kind, String title,
                      String question, String currentText, String proposedText, String reason,
                      long sourceStatId, String reviewedAt, String reviewedBy) {
    }

    /** 要写入的新提案（还没有 id，状态固定 pending） */
    public record NewProposal(String kind, String title, String question, String currentText,
                              String proposedText, String reason, long sourceStatId) {
    }

    /** 一条「被人工认可、但还没分析过」的问答 */
    public record GoodAnswer(long statId, String question, String answer, String retrieved) {
    }

    /** 列表一次最多给多少条 */
    private static final int MAX_LIMIT = 500;

    /** 一次分析最多取多少条 */
    private static final int MAX_ANALYZE = 20;

    private static final String COLUMNS =
            "id, created_at, status, kind, title, question, current_text,"
                    + " proposed_text, reason, source_stat_id, reviewed_at, reviewed_by";

    private static final Jdbc.RowMapper<Row> MAP = r -> new Row(
            r.longOf("id"), r.str("created_at"), r.str("status"), r.str("kind"), r.str("title"),
            r.str("question"), r.str("current_text"), r.str("proposed_text"), r.str("reason"),
            r.longOf("source_stat_id"), r.str("reviewed_at"), r.str("reviewed_by"));

    private final Jdbc jdbc;

    public KbProposalRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_proposal ("
                + " id            INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " created_at    TEXT NOT NULL,"
                + " status        TEXT NOT NULL DEFAULT 'pending',"
                + " kind          TEXT NOT NULL,"
                + " title         TEXT NOT NULL,"
                + " question      TEXT DEFAULT '',"
                + " current_text  TEXT DEFAULT '',"
                + " proposed_text TEXT NOT NULL,"
                + " reason        TEXT DEFAULT '',"
                + " source_stat_id INTEGER NOT NULL DEFAULT 0,"
                + " reviewed_at   TEXT,"
                + " reviewed_by   TEXT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_prop_status ON kb_proposal(status)");
        // 同一条问答不重复出提案
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_prop_src"
                + " ON kb_proposal(source_stat_id) WHERE source_stat_id > 0");
    }

    /** 还没被分析过的「有帮助」回答有几条 —— 前端用它显示"还差几条到阈值" */
    public int pendingGoodCount() {
        return (int) jdbc.count("SELECT COUNT(*) FROM qa_stat s WHERE s.verdict = 'good'"
                + " AND s.id NOT IN (SELECT source_stat_id FROM kb_proposal WHERE source_stat_id > 0)");
    }

    public int countByStatus(String status) {
        return (int) jdbc.count("SELECT COUNT(*) FROM kb_proposal WHERE status = ?", status);
    }

    /** 按状态过滤的列表；{@code status} 空 = 不过滤 */
    public List<Row> list(String status, int limit) {
        boolean filtered = status != null && !status.isBlank();
        String sql = "SELECT " + COLUMNS + " FROM kb_proposal"
                + (filtered ? " WHERE status = ?" : "")
                + " ORDER BY id DESC LIMIT ?";
        int capped = Math.min(Math.max(1, limit), MAX_LIMIT);
        return filtered ? jdbc.query(sql, MAP, status, capped) : jdbc.query(sql, MAP, capped);
    }

    public Row findById(long id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM kb_proposal WHERE id = ?", MAP, id);
    }

    /**
     * 取「被人工认可、且还没出过提案」的问答，按 id 倒序。
     *
     * <p>{@code LEFT JOIN qa_raw}：问答原文可能已经被清理掉，那时 question/answer 为 null ——
     * 调用方按「没内容就跳过」处理，而不是当成错误。
     */
    public List<GoodAnswer> pendingGoodAnswers(int maxItems) {
        return jdbc.query(
                "SELECT s.id, r.question, r.answer, s.retrieved FROM qa_stat s"
                        + " LEFT JOIN qa_raw r ON r.id = s.id"
                        + " WHERE s.verdict = 'good'"
                        + " AND s.id NOT IN (SELECT source_stat_id FROM kb_proposal WHERE source_stat_id > 0)"
                        + " ORDER BY s.id DESC LIMIT ?",
                r -> new GoodAnswer(r.longOf("id"), r.str("question"), r.str("answer"), r.str("retrieved")),
                Math.min(Math.max(1, maxItems), MAX_ANALYZE));
    }

    /** 写入一条待审提案（状态固定 pending；重复的问答被唯一索引挡掉） */
    public void insertIgnore(NewProposal p, String createdAt) {
        jdbc.update("INSERT OR IGNORE INTO kb_proposal (created_at, status, kind, title, question,"
                        + " current_text, proposed_text, reason, source_stat_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                createdAt, "pending", p.kind(), p.title(), p.question(),
                p.currentText(), p.proposedText(), p.reason(), p.sourceStatId());
    }

    /** 标记已处理（approved / rejected） */
    public int markReviewed(long id, String status, String reviewedAt, String reviewedBy) {
        return jdbc.update("UPDATE kb_proposal SET status = ?, reviewed_at = ?, reviewed_by = ? WHERE id = ?",
                status, reviewedAt, reviewedBy, id);
    }
}
