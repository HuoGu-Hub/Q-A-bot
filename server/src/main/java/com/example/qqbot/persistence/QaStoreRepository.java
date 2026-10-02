package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 问答记录三张表（{@code qa_stat} / {@code qa_raw} / {@code qa_keyword}）
 * 与后台访问表（{@code admin_visit}）的**数据访问**。
 *
 * <h2>两层结构</h2>
 * <pre>
 *   qa_stat     派生层，永久保留 —— 时间/群/用户/检索指标/耗时/标注
 *   qa_raw      原始层，到期删除 —— 问题原文/引用原文/回答原文
 *   qa_keyword  派生层，永久保留 —— 从问题里抽出的游戏名词
 * </pre>
 * 删原文不需要先做聚合：统计需要的字段本来就在 {@code qa_stat} 里。
 *
 * <h2>三处不能顺手改掉的东西</h2>
 * <ul>
 *   <li>{@link #insertBatch} 的三层写入必须**一个事务** —— 一半写进去的问答记录比没写更糟：
 *       统计里算了一次，却查不到原文；而 {@code qa_raw.id} 是外键指向 {@code qa_stat.id}，
 *       先写 raw 会直接违反约束。</li>
 *   <li>{@link #initSchema} 的**幂等补列**：{@code CREATE TABLE IF NOT EXISTS} 对已存在的表
 *       什么都不做，而这张库要长期保留统计数据、不能删库重建。少了补列，
 *       老库上广场「生成新答案」会一直报 {@code no such column: source}。</li>
 *   <li>{@link #markFollowUp} 的 {@code verdict IS NULL OR verdict = 'unknown'} ——
 *       只给"还没被人工标过"的行打追问标记，**不能覆盖人工结论**。</li>
 * </ul>
 */
@Repository
public class QaStoreRepository {

    /** {@code qa_stat} 的一行（插入用） */
    public record StatRow(String ts, long groupId, long userId, long messageId, int imageCount,
                          boolean kbEnabled, int hitCount, double topScore, double bestCosine,
                          double bestCosineRaw, String sources, String retrievedJson,
                          int answerLen, boolean answerEmpty, String guardAction,
                          long retrieveMs, long llmMs, long totalMs, String model) {
    }

    /** {@code qa_raw} 的一行 */
    public record RawRow(String question, String quoteText, String answer) {
    }

    /** {@code qa_keyword} 的一行 */
    public record KeywordRow(String keyword, String termEn, boolean inKb) {
    }

    /** 一条待写入的问答 —— 三层一起，必须原子 */
    public record NewRecord(StatRow stat, RawRow raw, List<KeywordRow> keywords) {
    }

    private static final String INSERT_STAT =
            "INSERT INTO qa_stat (ts, group_id, user_id, message_id, image_count,"
                    + " kb_enabled, hit_count, top_score, best_cosine, best_cosine_raw,"
                    + " sources, retrieved,"
                    + " answer_len, answer_empty, guard_action,"
                    + " retrieve_ms, llm_ms, total_ms, model)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String INSERT_RAW =
            "INSERT INTO qa_raw (id, question, quote_text, answer) VALUES (?,?,?,?)";

    private static final String INSERT_KEYWORD =
            "INSERT INTO qa_keyword (stat_id, keyword, term_en, in_kb) VALUES (?,?,?,?)";

    private final Jdbc jdbc;

    public QaStoreRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    /**
     * 建表 + **幂等补列** + 写 {@code user_version}。
     *
     * <p>顺序：建表（全新库直接建最新结构）之后逐列检查 {@code PRAGMA table_info}，
     * 缺哪列补哪列。幂等，重复执行无副作用。
     */
    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS qa_stat ("
                + " id           INTEGER PRIMARY KEY,"
                + " ts           TEXT NOT NULL,"
                + " group_id     INTEGER,"
                + " user_id      INTEGER,"
                + " message_id   INTEGER,"
                + " image_count  INTEGER DEFAULT 0,"
                + " kb_enabled   INTEGER,"
                + " hit_count    INTEGER,"
                + " top_score    REAL,"
                + " best_cosine  REAL,"
                + " best_cosine_raw REAL,"
                + " sources      TEXT,"
                + " retrieved    TEXT,"
                + " answer_len   INTEGER,"
                + " answer_empty INTEGER,"
                + " guard_action TEXT,"
                + " retrieve_ms  INTEGER,"
                + " llm_ms       INTEGER,"
                + " total_ms     INTEGER,"
                + " model        TEXT,"
                + " verdict      TEXT DEFAULT 'unknown',"
                + " verdict_note TEXT,"
                + " verdict_by   TEXT,"
                + " verdict_at   TEXT"
                + " )");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_st_ts      ON qa_stat(ts)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_st_group   ON qa_stat(group_id, ts)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_st_verdict ON qa_stat(verdict)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_st_hit     ON qa_stat(hit_count)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS qa_raw ("
                + " id         INTEGER PRIMARY KEY REFERENCES qa_stat(id),"
                + " question   TEXT NOT NULL,"
                + " quote_text TEXT,"
                + " answer     TEXT"
                + " )");

        jdbc.execute("CREATE TABLE IF NOT EXISTS qa_keyword ("
                + " id       INTEGER PRIMARY KEY,"
                + " stat_id  INTEGER NOT NULL REFERENCES qa_stat(id),"
                + " keyword  TEXT NOT NULL,"
                + " term_en  TEXT,"
                + " in_kb    INTEGER"
                + " )");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_kw_keyword ON qa_keyword(keyword)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_kw_stat    ON qa_keyword(stat_id)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS admin_visit ("
                + " id       INTEGER PRIMARY KEY,"
                + " ts       TEXT NOT NULL,"
                + " path     TEXT,"
                + " ip_hash  TEXT,"
                + " ua       TEXT,"
                + " action   TEXT"
                + " )");

        addColumnIfMissing("qa_stat", "best_cosine", "REAL");
        // 2026-09-26：卡阈值【之前】的最高余弦。老库靠这一行自动补列，不用删库重建
        addColumnIfMissing("qa_stat", "best_cosine_raw", "REAL");
        // 2026-09-28：行来源（'chat' 群聊 / 'plaza' 广场生成）。PlazaStore 与
        // PlazaAdminController 早就在读写它，但建表语句里一直没有这一列 ——
        // 也就是说**全新库**上广场「生成新答案」必然报 no such column。这里补上。
        addColumnIfMissing("qa_stat", "source", "TEXT DEFAULT 'chat'");

        jdbc.execute("PRAGMA user_version = 1");
    }

    /** 缺列才补 —— SQLite 的 ALTER TABLE ADD COLUMN 没有 IF NOT EXISTS */
    private void addColumnIfMissing(String table, String column, String type) {
        boolean exists = jdbc.query("PRAGMA table_info(" + table + ")", r -> r.str("name")).stream()
                .anyMatch(n -> column.equalsIgnoreCase(n));
        if (exists) {
            return;
        }
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    /**
     * 批量落盘，**一个事务**（见类注释）。调用方是单线程的 {@code QaRecorder}。
     *
     * <p>关键词攒到最后一次性 batch：每条记录单独 batch 一次会让 50 条记录的写入变成 50 个事务。
     */
    public void insertBatch(List<NewRecord> records) {
        jdbc.transaction(() -> {
            List<Object[]> keywords = new ArrayList<>();
            for (NewRecord rec : records) {
                StatRow s = rec.stat();
                long id = jdbc.insert(INSERT_STAT,
                        s.ts(), s.groupId(), s.userId(), s.messageId(), s.imageCount(),
                        s.kbEnabled(), s.hitCount(), s.topScore(), s.bestCosine(), s.bestCosineRaw(),
                        s.sources(), s.retrievedJson(), s.answerLen(), s.answerEmpty(),
                        s.guardAction(), s.retrieveMs(), s.llmMs(), s.totalMs(), s.model());

                RawRow raw = rec.raw();
                jdbc.update(INSERT_RAW, id, raw.question(), raw.quoteText(), raw.answer());

                for (KeywordRow k : rec.keywords()) {
                    keywords.add(new Object[]{id, k.keyword(), k.termEn(), k.inKb()});
                }
            }
            if (!keywords.isEmpty()) {
                jdbc.batch(INSERT_KEYWORD, keywords);
            }
            return null;
        });
    }

    // ==================== 清理 / 标注 ====================

    /** 有多少条原文已经过期（{@code qa_stat.ts} 早于 cutoff） */
    public long countRawOlderThan(String cutoff) {
        return jdbc.count("SELECT COUNT(*) FROM qa_raw WHERE id IN"
                + " (SELECT id FROM qa_stat WHERE ts < ?)", cutoff);
    }

    /** 删掉过期原文。**只删 qa_raw，qa_stat 与 qa_keyword 一行不动** */
    public int deleteRawOlderThan(String cutoff) {
        return jdbc.update("DELETE FROM qa_raw WHERE id IN"
                + " (SELECT id FROM qa_stat WHERE ts < ?)", cutoff);
    }

    /** 给一条记录打标 */
    public boolean annotate(long id, String verdict, String note, String by, String at) {
        return jdbc.update("UPDATE qa_stat SET verdict = ?, verdict_note = ?, verdict_by = ?,"
                + " verdict_at = ? WHERE id = ?", verdict, note, by, at, id) > 0;
    }

    /** 标记"这条是追问" —— 见类注释（不覆盖人工结论） */
    public boolean markFollowUp(long id, String at) {
        return jdbc.update("UPDATE qa_stat SET verdict = 'follow_up', verdict_by = 'auto',"
                + " verdict_at = ? WHERE id = ? AND (verdict IS NULL OR verdict = 'unknown')",
                at, id) > 0;
    }

    /** 记一次后台访问（IP 已哈希） */
    public void recordVisit(String ts, String path, String ipHash, String userAgent, String action) {
        jdbc.update("INSERT INTO admin_visit (ts, path, ip_hash, ua, action) VALUES (?,?,?,?,?)",
                ts, path, ipHash, userAgent, action);
    }

    // ==================== 自检 ====================

    public long countStat() {
        return jdbc.count("SELECT COUNT(*) FROM qa_stat");
    }

    public long countRaw() {
        return jdbc.count("SELECT COUNT(*) FROM qa_raw");
    }
}
