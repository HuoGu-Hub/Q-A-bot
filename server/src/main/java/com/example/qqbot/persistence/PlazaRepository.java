package com.example.qqbot.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 问答广场**自己那两张表**（{@code answer_vote} 投票 / {@code help_request} 求助）的数据访问。
 *
 * <h2>和 {@link PlazaQueryRepository} 的分工（别合并）</h2>
 * <ul>
 *   <li>{@link PlazaQueryRepository} —— <b>跨表报表式读取</b>：热词榜、已投票答案列表。
 *       那些查询横跨 {@code qa_stat} / {@code qa_raw} / {@code qa_keyword} / {@code answer_vote}，
 *       不属于任何单个 Store。</li>
 *   <li>本类 —— <b>广场自己的写入与按表读取</b>：投票、下架、求助记录的生命周期。</li>
 * </ul>
 * 两者都碰 {@code answer_vote}，但视角不同：一个是"报表"，一个是"这一行归我管"。
 *
 * <h2>三处不能顺手改掉的东西</h2>
 * <ul>
 *   <li>{@link #vote} 是 {@code ON CONFLICT(stat_id, voter_hash) DO UPDATE}：
 *       <b>同一人重复投是改票，不是新增</b>。改成普通 INSERT 会撞唯一约束，
 *       或者（若去掉唯一约束）让一个人投出 100 票。</li>
 *   <li>{@link #takedown} 是 {@code DELETE FROM answer_vote}，**不是删内容**：
 *       清空投票后它就达不到"被点赞"门槛，自然从公开站消失，而原文还在、误操作可恢复。</li>
 *   <li>{@link #addColumnIfMissing}：SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}，
 *       少了它，之前建过库的机器升级后会一直报 {@code no such column: source}。</li>
 * </ul>
 *
 * <h2>顺手修掉的一个真实缺陷</h2>
 * {@link #saveGeneratedAnswer} 要写三张表（{@code qa_stat} + {@code qa_raw} + {@code qa_keyword}），
 * 原来**没有事务**：第二步失败就留下一条没有原文的 {@code qa_stat} 统计行 ——
 * 它在统计里算一次问答，在广场上却没有内容。现在整段在一个事务里。
 */
@Repository
public class PlazaRepository {

    private static final Logger log = LoggerFactory.getLogger(PlazaRepository.class);

    /** 一条投票明细（后台看"谁投的什么"） */
    public record VoteDetail(String voterHash, String vote, String createdAt, String updatedAt) {
    }

    /** 一条投票统计 */
    public record Tally(long statId, long up, long down, long outdated) {
    }

    /** 一条求助记录 */
    public record HelpRow(long id, String ts, String keyword, String question,
                          long groupId, long userId, String status) {
    }

    /**
     * 一条待确认的求助。
     *
     * <p>{@code question} 是**库里的原文**（含网页生成的短码前缀，形如 {@code a1b2c3|问题}）——
     * 剥前缀是业务规则，在 {@code PlazaStore} 那一侧做。
     */
    public record PendingHelp(long id, String keyword, String question) {
    }

    private final Jdbc jdbc;

    public PlazaRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS answer_vote ("
                + " id         INTEGER PRIMARY KEY,"
                + " stat_id    INTEGER NOT NULL,"
                + " voter_hash TEXT NOT NULL,"
                + " vote       TEXT NOT NULL,"
                + " created_at TEXT NOT NULL,"
                + " updated_at TEXT NOT NULL,"
                + " UNIQUE(stat_id, voter_hash)"
                + " )");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vote_stat  ON answer_vote(stat_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vote_voter ON answer_vote(voter_hash)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS help_request ("
                + " id         INTEGER PRIMARY KEY,"
                + " ts         TEXT NOT NULL,"
                + " keyword    TEXT,"
                + " question   TEXT NOT NULL,"
                + " group_id   INTEGER NOT NULL,"
                + " user_id    INTEGER NOT NULL,"
                + " status     TEXT DEFAULT 'sent',"
                + " answer_id  INTEGER"
                + " )");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_help_ts    ON help_request(ts)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_help_group ON help_request(group_id, ts)");

        // 给 qa_stat 加一列：区分来源（群内问答 / 广场生成）
        addColumnIfMissing("qa_stat", "source", "TEXT DEFAULT 'chat'");
    }

    /** 幂等补列 —— 见类注释 */
    private void addColumnIfMissing(String table, String column, String type) {
        boolean exists = jdbc.query("PRAGMA table_info(" + table + ")", r -> r.str("name")).stream()
                .anyMatch(n -> column.equalsIgnoreCase(n));
        if (exists) {
            return;
        }
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        log.info("[PLAZA] 数据库迁移：{} 表补上 {} 列", table, column);
    }

    // ==================== 投票 ====================

    /** 投票（幂等：同一人同一答案重复投 = 改票） */
    public void vote(long statId, String voterHash, String vote, String now) {
        jdbc.update("INSERT INTO answer_vote (stat_id, voter_hash, vote, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?)"
                        + " ON CONFLICT(stat_id, voter_hash)"
                        + " DO UPDATE SET vote=excluded.vote, updated_at=excluded.updated_at",
                statId, voterHash, vote, now, now);
    }

    /** 某人给某条投过什么；没投过返回 {@code null} */
    public String findVote(long statId, String voterHash) {
        return jdbc.queryOne("SELECT vote FROM answer_vote WHERE stat_id=? AND voter_hash=?",
                r -> r.str("vote"), statId, voterHash);
    }

    /** 批量取投票统计（避免 N+1 查询）—— 只返回**有投票的**那些 stat_id */
    public List<Tally> voteTallies(List<Long> statIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(statIds.size(), "?"));
        String sql = "SELECT stat_id,"
                + " SUM(CASE WHEN vote='up' THEN 1 ELSE 0 END) up,"
                + " SUM(CASE WHEN vote='down' THEN 1 ELSE 0 END) down,"
                + " SUM(CASE WHEN vote='outdated' THEN 1 ELSE 0 END) outdated"
                + " FROM answer_vote WHERE stat_id IN (" + placeholders + ") GROUP BY stat_id";
        return jdbc.query(sql,
                r -> new Tally(r.longOf("stat_id"), r.longOf("up"), r.longOf("down"), r.longOf("outdated")),
                statIds.toArray());
    }

    /** 投票明细（管理后台） */
    public List<VoteDetail> voteDetails(long statId) {
        return jdbc.query("SELECT voter_hash, vote, created_at, updated_at FROM answer_vote"
                        + " WHERE stat_id=? ORDER BY updated_at DESC",
                r -> new VoteDetail(r.str("voter_hash"), r.str("vote"),
                        r.str("created_at"), r.str("updated_at")),
                statId);
    }

    /** 下架：清空该条的**全部投票**（见类注释），返回清掉几条 */
    public int takedown(long statId) {
        return jdbc.update("DELETE FROM answer_vote WHERE stat_id = ?", statId);
    }

    public long voteCount() {
        return jdbc.count("SELECT COUNT(*) FROM answer_vote");
    }

    // ==================== 求助记录 ====================

    /** 插入一条"待确认"的求助（群号与用户留 0 —— 网页访客还不属于任何群） */
    public long insertPendingHelp(String ts, String keyword, String question) {
        return jdbc.insert("INSERT INTO help_request (ts, keyword, question, group_id, user_id, status)"
                + " VALUES (?,?,?,0,0,'pending')", ts, keyword, question);
    }

    /** 按"问题前缀"找待确认的求助（短码匹配由调用方拼进 pattern） */
    public PendingHelp findPendingHelp(String questionPattern) {
        return jdbc.queryOne("SELECT id, keyword, question FROM help_request"
                        + " WHERE status='pending' AND question LIKE ? ORDER BY id DESC LIMIT 1",
                r -> new PendingHelp(r.longOf("id"), r.str("keyword"), r.str("question")),
                questionPattern);
    }

    /** 确认发出（补上真实群号和用户） */
    public int confirmHelp(long id, long groupId, long userId) {
        return jdbc.update("UPDATE help_request SET group_id=?, user_id=?, status='sent' WHERE id=?",
                groupId, userId, id);
    }

    /** 记一条**已发出**的求助 */
    public void logHelp(String ts, String keyword, String question, long groupId, long userId) {
        jdbc.update("INSERT INTO help_request (ts, keyword, question, group_id, user_id, status)"
                + " VALUES (?,?,?,?,?,'sent')", ts, keyword, question, groupId, userId);
    }

    /** 某关键词在某群今天求助过几次（限流用） */
    public long helpCountToday(String keyword, long groupId) {
        return jdbc.count("SELECT COUNT(*) FROM help_request"
                + " WHERE keyword=? AND group_id=?"
                + " AND ts >= datetime('now', '-1 day')", keyword, groupId);
    }

    /** 某人今天求助过几次 */
    public long helpCountTodayByUser(long userId) {
        return jdbc.count("SELECT COUNT(*) FROM help_request"
                + " WHERE user_id=? AND ts >= datetime('now', '-1 day')", userId);
    }

    /** 某群最近一小时有几条求助（防刷屏） */
    public long helpCountLastHour(long groupId) {
        return jdbc.count("SELECT COUNT(*) FROM help_request"
                + " WHERE group_id=? AND ts >= datetime('now', '-1 hour')", groupId);
    }

    public long helpCount() {
        return jdbc.count("SELECT COUNT(*) FROM help_request");
    }

    /** 最近的求助记录（管理后台） */
    public List<HelpRow> recentHelp(int limit) {
        return jdbc.query("SELECT id, ts, keyword, question, group_id, user_id, status"
                        + " FROM help_request ORDER BY id DESC LIMIT ?",
                r -> new HelpRow(r.longOf("id"), r.str("ts"), r.str("keyword"), r.str("question"),
                        r.longOf("group_id"), r.longOf("user_id"), r.str("status")),
                limit);
    }

    // ==================== 广场生成的新答案 ====================

    /**
     * 保存广场生成的新答案，返回新统计行的 id。
     *
     * <p>写三处，**必须原子**（见类注释）：{@code qa_stat}（统计行，字段尽量给全，免得脏数据）
     * + {@code qa_raw}（原文）+ {@code qa_keyword}（关联关键词，这样它才会出现在广场上）。
     *
     * <p>⚠️ {@code group_id} / {@code user_id} 都给 0 —— 这条不是群聊产生的，不该伪造归属。
     *
     * <p>⚠️ 这里原来还写着 {@code has_quote} / {@code text_len} 两列，而 {@code qa_stat} 里
     * 根本没有它们（建表语句和迁移都没加过）—— 于是这段 INSERT 每次都会抛
     * {@code table qa_stat has no column named has_quote}，广场生成答案 100% 失败。
     * 没人读这两列，所以直接不写，而不是补两列没人用的东西。
     */
    public long saveGeneratedAnswer(String ts, String question, String answer, String keyword) {
        return jdbc.transaction(() -> {
            long id = jdbc.insert("INSERT INTO qa_stat (ts, group_id, user_id, message_id, image_count,"
                    + " hit_count, best_cosine, answer_len, answer_empty,"
                    + " llm_ms, total_ms, verdict, source)"
                    + " VALUES (?,0,0,0,0,0,0,?,0,0,0,'unknown','plaza')", ts, answer.length());
            jdbc.update("INSERT INTO qa_raw (id, question, answer) VALUES (?,?,?)", id, question, answer);
            // 关联关键词（这样它才会出现在广场上）
            if (keyword != null && !keyword.isBlank()) {
                jdbc.update("INSERT INTO qa_keyword (stat_id, keyword, term_en, in_kb) VALUES (?,?,?,1)",
                        id, keyword, null);
            }
            return id;
        });
    }
}
