package com.example.qqbot.qa;

import com.example.qqbot.config.QaProperties;
import com.example.qqbot.persistence.SqliteConnectionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 问答记录的 SQLite 存储。
 *
 * <p><b>两层结构</b>（见设计文档第四节）：
 * <pre>
 *   qa_stat     派生层，永久保留 —— 时间/群/用户/检索指标/耗时/标注
 *   qa_raw      原始层，到期删除 —— 问题原文/引用原文/回答原文
 *   qa_keyword  派生层，永久保留 —— 从问题里抽出的游戏名词
 * </pre>
 *
 * <p>删原文不需要先做聚合：统计需要的字段本来就在 qa_stat 里。
 *
 * <p><b>本类绝不让机器人挂掉</b>：初始化失败就置为不可用，
 * 之后所有写入变成空操作，只记一条警告。
 */
@Component
public class QaStore implements SqliteConnectionProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QaStore.class);

    private final QaProperties props;
    private final ObjectMapper mapper;

    /** 所有数据库访问都串行化 —— SQLite 单写者，而且写操作本来就走同一个队列 */
    private final Object lock = new Object();

    private Connection conn;
    private volatile boolean available;

    public QaStore(QaProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** 初始化（public 以便测试显式调用） */
    @PostConstruct
    public void init() {
        if (!props.isEnabled()) {
            log.info("[QA] 问答记录已关闭（app.qa.enabled=false）");
            return;
        }
        try {
            Path file = Paths.get(props.getDb()).toAbsolutePath().normalize();
            Files.createDirectories(file.getParent());
            conn = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA synchronous=NORMAL");
            }
            migrate();
            available = true;
            log.info("[QA] 记录库就绪：{}（原文保留 {} 天，0=永不删）",
                    file, props.getRetentionDays());
        } catch (Exception e) {
            log.warn("[QA] 初始化记录库失败，本次运行将不记录问答（不影响正常回答）：{}", e.getMessage());
            available = false;
        }
    }

    @PreDestroy

    @Override
    public void close() {
        synchronized (lock) {
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException ignored) {
                // 关不掉就算了
            }
        }
    }

    /**
     * 建表 + **幂等补列**。
     *
     * <p>为什么需要它：{@code CREATE TABLE IF NOT EXISTS} 对已存在的表**什么都不做**，
     * 所以给表加一列时老库不会自动跟上 —— 而这张库是要长期保留统计数据的，不能靠"删库重建"。
     *
     * <p>做法：建表（全新库直接建最新结构）之后，逐列检查 {@code PRAGMA table_info}，
     * 缺哪列补哪列。幂等，重复执行无副作用。
     */
    private void migrate() throws SQLException {
        createTables();
        addColumnIfMissing("qa_stat", "best_cosine", "REAL");
        // 2026-09-26：卡阈值【之前】的最高余弦。老库靠这一行自动补列，不用删库重建
        addColumnIfMissing("qa_stat", "best_cosine_raw", "REAL");
        // 2026-09-28：行来源（'chat' 群聊 / 'plaza' 广场生成）。PlazaStore 与
        // PlazaAdminController 早就在读写它，但建表语句里一直没有这一列 ——
        // 也就是说**全新库**上广场「生成新答案」必然报 no such column。这里补上。
        addColumnIfMissing("qa_stat", "source", "TEXT DEFAULT 'chat'");
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA user_version = 1");
        }
    }

    /** 缺列才补 —— SQLite 的 ALTER TABLE ADD COLUMN 没有 IF NOT EXISTS */
    private void addColumnIfMissing(String table, String column, String type) throws SQLException {
        boolean exists = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (exists) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("[QA] 数据库迁移：{} 表补上 {} 列", table, column);
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS qa_stat (
                      id           INTEGER PRIMARY KEY,
                      ts           TEXT NOT NULL,
                      group_id     INTEGER,
                      user_id      INTEGER,
                      message_id   INTEGER,
                      image_count  INTEGER DEFAULT 0,
                      kb_enabled   INTEGER,
                      hit_count    INTEGER,
                      top_score    REAL,
                      best_cosine  REAL,
                      best_cosine_raw REAL,
                      sources      TEXT,
                      retrieved    TEXT,
                      answer_len   INTEGER,
                      answer_empty INTEGER,
                      guard_action TEXT,
                      retrieve_ms  INTEGER,
                      llm_ms       INTEGER,
                      total_ms     INTEGER,
                      model        TEXT,
                      verdict      TEXT DEFAULT 'unknown',
                      verdict_note TEXT,
                      verdict_by   TEXT,
                      verdict_at   TEXT
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_st_ts      ON qa_stat(ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_st_group   ON qa_stat(group_id, ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_st_verdict ON qa_stat(verdict)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_st_hit     ON qa_stat(hit_count)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS qa_raw (
                      id         INTEGER PRIMARY KEY REFERENCES qa_stat(id),
                      question   TEXT NOT NULL,
                      quote_text TEXT,
                      answer     TEXT
                    )""");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS qa_keyword (
                      id       INTEGER PRIMARY KEY,
                      stat_id  INTEGER NOT NULL REFERENCES qa_stat(id),
                      keyword  TEXT NOT NULL,
                      term_en  TEXT,
                      in_kb    INTEGER
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_kw_keyword ON qa_keyword(keyword)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_kw_stat    ON qa_keyword(stat_id)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS admin_visit (
                      id       INTEGER PRIMARY KEY,
                      ts       TEXT NOT NULL,
                      path     TEXT,
                      ip_hash  TEXT,
                      ua       TEXT,
                      action   TEXT
                    )""");
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    /** 批量落盘，一个事务。调用方是单线程的 QaRecorder */
    public void insertBatch(List<QaRecord> records) {
        if (!available || records == null || records.isEmpty()) {
            return;
        }
        synchronized (lock) {
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement stat = conn.prepareStatement("""
                             INSERT INTO qa_stat (ts, group_id, user_id, message_id, image_count,
                               kb_enabled, hit_count, top_score, best_cosine, best_cosine_raw,
                               sources, retrieved,
                               answer_len, answer_empty, guard_action,
                               retrieve_ms, llm_ms, total_ms, model)
                             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                        Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement raw = conn.prepareStatement(
                             "INSERT INTO qa_raw (id, question, quote_text, answer) VALUES (?,?,?,?)");
                     PreparedStatement kw = conn.prepareStatement(
                             "INSERT INTO qa_keyword (stat_id, keyword, term_en, in_kb) VALUES (?,?,?,?)")) {

                    for (QaRecord r : records) {
                        int i = 1;
                        stat.setString(i++, r.ts());
                        stat.setLong(i++, r.groupId());
                        stat.setLong(i++, r.userId());
                        stat.setLong(i++, r.messageId());
                        stat.setInt(i++, r.imageCount());
                        stat.setInt(i++, r.kbEnabled() ? 1 : 0);
                        stat.setInt(i++, r.hitCount());
                        stat.setDouble(i++, r.topScore());
                        stat.setDouble(i++, r.bestCosine());
                        stat.setDouble(i++, r.bestCosineRaw());
                        stat.setString(i++, r.sources());
                        stat.setString(i++, r.retrievedJson());
                        stat.setInt(i++, r.answer() == null ? 0 : r.answer().length());
                        stat.setInt(i++, r.answer() == null || r.answer().isBlank() ? 1 : 0);
                        stat.setString(i++, r.guardAction());
                        stat.setLong(i++, r.retrieveMs());
                        stat.setLong(i++, r.llmMs());
                        stat.setLong(i++, r.totalMs());
                        stat.setString(i, r.model());
                        stat.executeUpdate();

                        long id;
                        try (ResultSet keys = stat.getGeneratedKeys()) {
                            keys.next();
                            id = keys.getLong(1);
                        }

                        raw.setLong(1, id);
                        raw.setString(2, r.question());
                        raw.setString(3, r.quoteText());
                        raw.setString(4, r.answer());
                        raw.executeUpdate();

                        for (QaRecord.Keyword k : r.keywords()) {
                            kw.setLong(1, id);
                            kw.setString(2, k.zh());
                            kw.setString(3, k.en());
                            kw.setInt(4, k.inKb() ? 1 : 0);
                            kw.addBatch();
                        }
                    }
                    kw.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (Exception e) {
                log.warn("[QA] 写入记录失败（丢弃本批 {} 条，不影响回答）：{}", records.size(), e.getMessage());
            }
        }
    }

    /**
     * 删除过期的原文。**只删 qa_raw，qa_stat 和 qa_keyword 一行不动。**
     *
     * @param retentionDays 保留天数；{@code <= 0} 表示永不删除
     * @param dryRun        true 时只统计不删除
     * @return 受影响（或将要影响）的行数
     */
    public int purgeRaw(int retentionDays, boolean dryRun) {
        if (!available || retentionDays <= 0) {
            return 0;
        }
        String cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toString();
        synchronized (lock) {
            try {
                int count;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM qa_raw WHERE id IN (SELECT id FROM qa_stat WHERE ts < ?)")) {
                    ps.setString(1, cutoff);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        count = rs.getInt(1);
                    }
                }
                if (dryRun || count == 0) {
                    return count;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM qa_raw WHERE id IN (SELECT id FROM qa_stat WHERE ts < ?)")) {
                    ps.setString(1, cutoff);
                    int deleted = ps.executeUpdate();
                    log.info("[QA] 已清理 {} 条过期原文（截止 {}，保留 {} 天）；统计表未受影响",
                            deleted, cutoff, retentionDays);
                    return deleted;
                }
            } catch (Exception e) {
                log.warn("[QA] 清理过期原文失败：{}", e.getMessage());
                return 0;
            }
        }
    }

    /**
     * 给一条记录打标。
     *
     * <p>这是 S4 闭环的起点：只有知道"哪条答错了"，才能去补术语或调阈值。
     *
     * @param verdict good / bad / no_source / hallucination / unknown
     */
    public boolean annotate(long id, String verdict, String note, String by) {
        if (!available) {
            return false;
        }
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE qa_stat SET verdict = ?, verdict_note = ?, verdict_by = ?, verdict_at = ?"
                            + " WHERE id = ?")) {
                ps.setString(1, verdict);
                ps.setString(2, note);
                ps.setString(3, by);
                ps.setString(4, Instant.now().toString());
                ps.setLong(5, id);
                return ps.executeUpdate() > 0;
            } catch (Exception e) {
                log.warn("[QA] 标注失败：{}", e.getMessage());
                return false;
            }
        }
    }

    /**
     * 标记"这条是追问"（同一个人短时间内又问了）。
     *
     * <p>追问是**零成本的准确率信号**：上一条要是答好了，通常不会马上再问一次。
     * 由记录时自动判定，不需要人工介入。
     */
    public boolean markFollowUp(long id) {
        if (!available) {
            return false;
        }
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE qa_stat SET verdict = 'follow_up', verdict_by = 'auto', verdict_at = ?"
                            + " WHERE id = ? AND (verdict IS NULL OR verdict = 'unknown')")) {
                ps.setString(1, Instant.now().toString());
                ps.setLong(2, id);
                return ps.executeUpdate() > 0;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** 记一次后台访问（IP 已哈希） */
    public void recordVisit(String path, String ipHash, String userAgent, String action) {
        if (!available) {
            return;
        }
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO admin_visit (ts, path, ip_hash, ua, action) VALUES (?,?,?,?,?)")) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, path);
                ps.setString(3, ipHash);
                ps.setString(4, userAgent == null ? "" : userAgent.substring(0, Math.min(200, userAgent.length())));
                ps.setString(5, action);
                ps.executeUpdate();
            } catch (Exception e) {
                log.debug("[QA] 记录后台访问失败：{}", e.getMessage());
            }
        }
    }

    /** 统计表行数（自检用） */
    public long countStat() {
        if (!available) {
            return 0;
        }
        synchronized (lock) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM qa_stat")) {
                rs.next();
                return rs.getLong(1);
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    /** 原文表行数（自检用） */
    public long countRaw() {
        if (!available) {
            return 0;
        }
        synchronized (lock) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM qa_raw")) {
                rs.next();
                return rs.getLong(1);
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    /** 仅供后续阶段（S2 报表 / S3 后台）使用 */
    @Override
    public Connection connection() {
        return conn;
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
