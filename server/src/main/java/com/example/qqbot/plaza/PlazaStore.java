package com.example.qqbot.plaza;

import com.example.qqbot.config.PlazaProperties;
import com.example.qqbot.persistence.SqliteConnectionProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答广场的数据层：投票 + 求助记录。
 *
 * <p>复用**共用**的 SQLite 连接（和 CommandStore 一样）——
 * 多个连接同时开同一个 SQLite 文件会触发 WAL 共享内存冲突。
 */
/**
 * 问答广场的数据层：投票 + 求助记录。
 *
 * <p>复用**共用**的 SQLite 连接（和 CommandStore 一样）—— 多个连接同时开同一个
 * SQLite 文件会触发 WAL 共享内存冲突。
 *
 * <p>⚠️ 这里原先有 {@code @DependsOn("qaStore")} 来保证"连上库"先于建表。
 * 连接所有权移交到 {@code persistence.SqliteDatabase} 之后**它不再需要了** ——
 * 构造器注入本身就保证了依赖先初始化完（含 {@code @PostConstruct}）。
 * 留着它才是风险：字符串形式的运行期耦合，改个类名就静默失效。
 */
@Component
public class PlazaStore {

    private static final Logger log = LoggerFactory.getLogger(PlazaStore.class);

    private final SqliteConnectionProvider db;
    private final PlazaProperties plazaProps;

    private volatile boolean available;

    public PlazaStore(SqliteConnectionProvider db, PlazaProperties plazaProps) {
        this.db = db;
        this.plazaProps = plazaProps;
    }

    private Connection conn() {
        return db.connection();
    }

    public boolean isAvailable() {
        return available;
    }

    @PostConstruct
    void init() {
        try {
            if (!db.isAvailable()) {
                log.warn("[PLAZA] 问答库不可用，广场功能一并关闭");
                return;
            }
            createTables();
            available = true;
            log.info("[PLAZA] 广场就绪：投票 {} 条，求助 {} 条", voteCount(), helpCount());
        } catch (Exception e) {
            log.warn("[PLAZA] 初始化失败（不影响正常问答）：{}", e.getMessage());
            available = false;
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS answer_vote (
                      id         INTEGER PRIMARY KEY,
                      stat_id    INTEGER NOT NULL,
                      voter_hash TEXT NOT NULL,
                      vote       TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      UNIQUE(stat_id, voter_hash)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_vote_stat  ON answer_vote(stat_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_vote_voter ON answer_vote(voter_hash)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS help_request (
                      id         INTEGER PRIMARY KEY,
                      ts         TEXT NOT NULL,
                      keyword    TEXT,
                      question   TEXT NOT NULL,
                      group_id   INTEGER NOT NULL,
                      user_id    INTEGER NOT NULL,
                      status     TEXT DEFAULT 'sent',
                      answer_id  INTEGER
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_help_ts    ON help_request(ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_help_group ON help_request(group_id, ts)");

            // 给 qa_stat 加一列：区分来源（群内问答 / 广场生成）
            addColumnIfMissing("qa_stat", "source", "TEXT DEFAULT 'chat'");
        }
    }

    /** 幂等补列 */
    private void addColumnIfMissing(String table, String column, String type) throws SQLException {
        boolean exists = false;
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement st = conn().createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
                log.info("[PLAZA] 数据库迁移：{} 表补上 {} 列", table, column);
            }
        }
    }

    // ==================== 投票者哈希 ====================

    /**
     * 把 QQ 号转成投票者标识。
     *
     * <p>为什么哈希：公开站可能被爬，投票记录不该关联到具体的人。
     * 但同一个 QQ 号每次得到同样的 hash —— 所以能防重复投票。
     *
     * <p>注意：这个 hash 不是匿名（能访问服务器的人可以枚举 QQ 号比对），
     * 但它防的是「公开站被爬」这个场景。
     */
    public String voterHash(long qq) {
        String salt = plazaProps.getVoteSalt();
        if (salt == null || salt.isBlank()) {
            salt = "qqbot-plaza-default-salt";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((qq + ":" + salt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 16);
        } catch (Exception e) {
            return String.valueOf(qq);
        }
    }

    // ==================== 投票 ====================

    /** 一条回答的投票统计 */
    public record VoteSummary(long statId, long up, long down, long outdated) {

        public long score() {
            return up - down;
        }

        /** 是否「全被踩」——踩数超过赞数且达到阈值 */
        public boolean isBadlyDownvoted(int threshold) {
            return down >= threshold && down > up;
        }
    }

    /**
     * 投票（幂等）。
     *
     * <p>同一人同一答案重复投 = 改票（UNIQUE + UPSERT），不是新增。
     *
     * @param vote up / down / outdated
     */
    public boolean vote(long statId, long voterQq, String vote) {
        if (!available || !isValidVote(vote)) {
            return false;
        }
        String hash = voterHash(voterQq);
        String now = Instant.now().toString();
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement("""
                    INSERT INTO answer_vote (stat_id, voter_hash, vote, created_at, updated_at)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT(stat_id, voter_hash)
                    DO UPDATE SET vote=excluded.vote, updated_at=excluded.updated_at""")) {
                ps.setLong(1, statId);
                ps.setString(2, hash);
                ps.setString(3, vote);
                ps.setString(4, now);
                ps.setString(5, now);
                ps.executeUpdate();
                return true;
            } catch (Exception e) {
                log.warn("[PLAZA] 投票失败：{}", e.getMessage());
                return false;
            }
        }
    }

    /** 查看某人投过什么（前端高亮已投） */
    public String myVote(long statId, long voterQq) {
        if (!available) {
            return null;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT vote FROM answer_vote WHERE stat_id=? AND voter_hash=?")) {
                ps.setLong(1, statId);
                ps.setString(2, voterHash(voterQq));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** 批量取投票统计（避免 N+1 查询） */
    public Map<Long, VoteSummary> voteSummaries(List<Long> statIds) {
        Map<Long, VoteSummary> out = new LinkedHashMap<>();
        if (!available || statIds == null || statIds.isEmpty()) {
            return out;
        }
        synchronized (this) {
            String qs = String.join(",", java.util.Collections.nCopies(statIds.size(), "?"));
            String sql = "SELECT stat_id,"
                    + " SUM(CASE WHEN vote='up' THEN 1 ELSE 0 END) up,"
                    + " SUM(CASE WHEN vote='down' THEN 1 ELSE 0 END) down,"
                    + " SUM(CASE WHEN vote='outdated' THEN 1 ELSE 0 END) outdated"
                    + " FROM answer_vote WHERE stat_id IN (" + qs + ") GROUP BY stat_id";
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                for (int i = 0; i < statIds.size(); i++) {
                    ps.setLong(i + 1, statIds.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getLong(1), new VoteSummary(rs.getLong(1),
                                rs.getLong(2), rs.getLong(3), rs.getLong(4)));
                    }
                }
            } catch (Exception e) {
                log.warn("[PLAZA] 取投票统计失败：{}", e.getMessage());
            }
        }
        return out;
    }

    /** 投票明细（管理后台） */
    public List<Map<String, Object>> voteDetails(long statId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT voter_hash, vote, created_at, updated_at FROM answer_vote"
                            + " WHERE stat_id=? ORDER BY updated_at DESC")) {
                ps.setLong(1, statId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("voterHash", rs.getString(1));
                        m.put("vote", rs.getString(2));
                        m.put("createdAt", rs.getString(3));
                        m.put("updatedAt", rs.getString(4));
                        out.add(m);
                    }
                }
            } catch (Exception e) {
                log.warn("[PLAZA] 取投票明细失败：{}", e.getMessage());
            }
        }
        return out;
    }

    public static boolean isValidVote(String vote) {
        return "up".equals(vote) || "down".equals(vote) || "outdated".equals(vote);
    }

    public long voteCount() {
        return scalar("SELECT COUNT(*) FROM answer_vote");
    }

    /**
     * 下架一条内容。
     *
     * <p>做法不是删除，而是清空投票 —— 清空后它就达不到「被点赞」门槛，
     * 自然从公开站消失。这样原文仍保留便于复查，误操作了重新点赞也能恢复。
     */
    public boolean takedown(long statId) {
        if (!available) {
            return false;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "DELETE FROM answer_vote WHERE stat_id = ?")) {
                ps.setLong(1, statId);
                int n = ps.executeUpdate();
                log.info("[PLAZA] 已下架 statId={}，清空 {} 条投票", statId, n);
                return true;
            } catch (Exception e) {
                log.warn("[PLAZA] 下架失败：{}", e.getMessage());
                return false;
            }
        }
    }

    // ==================== 求助记录 ====================

    /**
     * 创建一条「待确认」的求助。
     *
     * <p>为什么要有这个中间态：网页访客无法证明自己属于某个群，
     * 所以网页只能"申请"，真正发出去要等群内确认（带真实群号）。
     *
     * <p>返回一个短码，用户把它发到群里就能触发。
     */
    public String createHelpRequest(String keyword, String question) {
        if (!available) {
            return null;
        }
        String code = Long.toHexString(System.nanoTime()).substring(0, 6);
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "INSERT INTO help_request (ts, keyword, question, group_id, user_id, status)"
                            + " VALUES (?,?,?,0,0,'pending')")) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, keyword);
                ps.setString(3, code + "|" + question);
                ps.executeUpdate();
                return code;
            } catch (Exception e) {
                log.warn("[PLAZA] 创建求助失败：{}", e.getMessage());
                return null;
            }
        }
    }

    /** 按短码找出待确认的求助 */
    public Map<String, Object> findPendingHelp(String code) {
        Map<String, Object> out = null;
        if (!available || code == null) {
            return null;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT id, keyword, question FROM help_request"
                            + " WHERE status='pending' AND question LIKE ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, code + "|%");
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        out = new LinkedHashMap<>();
                        out.put("id", rs.getLong("id"));
                        out.put("keyword", rs.getString("keyword"));
                        String q = rs.getString("question");
                        out.put("question", q != null && q.contains("|")
                                ? q.substring(q.indexOf('|') + 1) : q);
                    }
                }
            } catch (Exception e) {
                log.warn("[PLAZA] 查求助失败：{}", e.getMessage());
            }
        }
        return out;
    }

    /** 确认发出（补上真实群号和用户） */
    public void confirmHelp(long id, long groupId, long userId) {
        if (!available) {
            return;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE help_request SET group_id=?, user_id=?, status='sent' WHERE id=?")) {
                ps.setLong(1, groupId);
                ps.setLong(2, userId);
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (Exception e) {
                log.warn("[PLAZA] 确认求助失败：{}", e.getMessage());
            }
        }
    }

    public void logHelp(long groupId, long userId, String keyword, String question) {
        if (!available) {
            return;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "INSERT INTO help_request (ts, keyword, question, group_id, user_id, status)"
                            + " VALUES (?,?,?,?,?,'sent')")) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, keyword);
                ps.setString(3, question);
                ps.setLong(4, groupId);
                ps.setLong(5, userId);
                ps.executeUpdate();
            } catch (Exception e) {
                log.warn("[PLAZA] 记录求助失败：{}", e.getMessage());
            }
        }
    }

    /** 某关键词在某群今天求助过几次（限流用） */
    public long helpCountToday(String keyword, long groupId) {
        return scalar("SELECT COUNT(*) FROM help_request"
                + " WHERE keyword=? AND group_id=?"
                + " AND ts >= datetime('now', '-1 day')",
                keyword, groupId);
    }

    /** 某人今天求助过几次 */
    public long helpCountTodayByUser(long userId) {
        return scalar("SELECT COUNT(*) FROM help_request"
                + " WHERE user_id=? AND ts >= datetime('now', '-1 day')", userId);
    }

    /** 某群最近一小时有几条求助（防刷屏） */
    public long helpCountLastHour(long groupId) {
        return scalar("SELECT COUNT(*) FROM help_request"
                + " WHERE group_id=? AND ts >= datetime('now', '-1 hour')", groupId);
    }

    public long helpCount() {
        return scalar("SELECT COUNT(*) FROM help_request");
    }

    /** 最近的求助记录（管理后台） */
    public List<Map<String, Object>> recentHelp(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT ts, keyword, question, group_id, user_id, status"
                            + " FROM help_request ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("ts", rs.getString(1));
                        m.put("keyword", rs.getString(2));
                        m.put("question", rs.getString(3));
                        m.put("groupId", rs.getLong(4));
                        m.put("userId", rs.getLong(5));
                        m.put("status", rs.getString(6));
                        out.add(m);
                    }
                }
            } catch (Exception e) {
                log.warn("[PLAZA] 取求助记录失败：{}", e.getMessage());
            }
        }
        return out;
    }

    /**
     * 保存广场生成的新答案。
     *
     * <p>写两处：qa_stat（统计行，字段尽量给全，免得脏数据）+ qa_raw（原文）。
     * 标记 source='plaza' 以便和群内问答区分。
     *
     * <p>⚠️ group_id / user_id 都给 0 —— 这条不是群聊产生的，不该伪造归属。
     */
    public long saveGeneratedAnswer(String keyword, String question, String answer) throws Exception {
        if (!available) {
            throw new IllegalStateException("广场数据库不可用");
        }
        synchronized (this) {
            String now = Instant.now().toString();
            long id;
            // ⚠️ 这里原来还写着 has_quote / text_len 两列，而 qa_stat 里根本没有它们
            //    （建表语句和迁移都没加过）—— 于是这段 INSERT 每次都会抛
            //    "table qa_stat has no column named has_quote"，广场生成答案 100% 失败。
            //    没人读这两列，所以直接不写，而不是补两列没人用的东西。
            try (PreparedStatement ps = conn().prepareStatement("""
                    INSERT INTO qa_stat (ts, group_id, user_id, message_id, image_count,
                      hit_count, best_cosine, answer_len, answer_empty,
                      llm_ms, total_ms, verdict, source)
                    VALUES (?,0,0,0,0,0,0,?,0,0,0,'unknown','plaza')""",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, now);
                ps.setInt(2, answer.length());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = conn().prepareStatement(
                    "INSERT INTO qa_raw (id, question, answer) VALUES (?,?,?)")) {
                ps.setLong(1, id);
                ps.setString(2, question);
                ps.setString(3, answer);
                ps.executeUpdate();
            }
            // 关联关键词（这样它才会出现在广场上）
            if (keyword != null && !keyword.isBlank()) {
                try (PreparedStatement ps = conn().prepareStatement(
                        "INSERT INTO qa_keyword (stat_id, keyword, term_en, in_kb) VALUES (?,?,?,1)")) {
                    ps.setLong(1, id);
                    ps.setString(2, keyword);
                    ps.setString(3, null);
                    ps.executeUpdate();
                }
            }
            return id;
        }
    }

    // ==================== 内部 ====================

    private long scalar(String sql, Object... params) {
        if (!available) {
            return 0;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    if (params[i] instanceof Number n) {
                        ps.setLong(i + 1, n.longValue());
                    } else {
                        ps.setString(i + 1, String.valueOf(params[i]));
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            } catch (Exception e) {
                log.debug("[PLAZA] 查询失败：{}", e.getMessage());
                return 0;
            }
        }
    }
}
