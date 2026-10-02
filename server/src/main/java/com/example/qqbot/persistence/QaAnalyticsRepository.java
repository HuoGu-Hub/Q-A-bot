package com.example.qqbot.persistence;

import com.example.qqbot.config.AppTime;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

/**
 * 问答统计（看板 / CLI 报表 / 公开站）的**数据访问** —— 所有统计 SQL 都在这里。
 *
 * <p>业务类 {@code qa.QaAnalytics} 现在只做三件事：把行拼成对外记录、
 * 算比率与分位数、以及在库不可用/查询失败时降级成空结果。
 *
 * <h2>口径（这些条件都是**正确性条件**，不是优化）</h2>
 * 产品对「提问」的定义（2026-09-26 确认）：一条消息必须<b>同时</b>满足
 * ① @ 了机器人；② 机器人给出了有效回应且未被拦截。对照 {@code guard_action} 的四个取值，
 * <b>只有 {@link #PASS} 同时满足</b>（四个值的语义见下）。
 *
 * <ul>
 *   <li><b>{@code guard_action = 'pass'}</b> —— 任何"提问量 / 命中率 / 独立用户 /
 *       独立群 / 每日提问量 / 延迟分位数 / 关键词排行 / 未命中排行 / 余弦分布 /
 *       检索来源 / 标注"的分母都必须是它。漏了它，群里没 @ 机器人的闲聊会把榜单灌满
 *       （实测：独立用户 90 个 → 真实 25 个；延迟 P50 39ms → 真实 697ms）。</li>
 *   <li><b>{@code group_id > 0}</b>（{@link #overviewCounts} / {@link #dailyQuestions}）——
 *       广场生成的答案写的是 {@code group_id = 0}（见 {@code PlazaStore.saveGeneratedAnswer}），
 *       不排除的话 {@code COUNT(DISTINCT group_id)} 会凭空多出一个"0 号群"，
 *       而且「群消息总数」会大于各 action 之和。</li>
 *   <li><b>延迟分位数只统计 {@code pass} 行</b> —— {@code drop} 行的 {@code retrieve_ms}
 *       恒为 0，混进来会把 P50/P95 拉低一个数量级（实测全表 39ms vs 提问 697ms）。</li>
 *   <li><b>日期分桶用 {@link AppTime#SQL_DAY}</b>，不是 UTC ——
 *       否则早上 8 点前的提问会落到前一天。</li>
 * </ul>
 *
 * <h2>⚠️ 拼接 {@code ts} 过滤时：先看这条 SQL 里有没有 WHERE</h2>
 * 有几条查询的基础语句**已经带 WHERE**（{@code guard_action = 'pass'}），
 * 那几处必须用 {@code AND ts >= ?}；写成 {@code WHERE ts >= ?} 会拼出两个 WHERE
 * → SQL 语法错误 → 被兜底吞成空列表。后果是看板上的「余弦分布 / 检索来源 / 标注」
 * 对任何「近 7 / 30 / 90 天」都是空的，只有「全部」能出数据 —— 而且不报错。
 * 这个坑踩过一次，{@link #cosines} / {@link #sources} / {@link #verdicts} 三处有注释标记。
 *
 * <h2>⚠️ 对 {@code config.AppTime} 的依赖</h2>
 * 这是已知债务⑥（业务包依赖 config）的一部分，和 {@code SqliteDatabase} 读
 * {@code QaProperties} 同一类。{@code AppTime.SQL_DAY} 是**唯一**的日期口径定义，
 * 复制一份到持久层才是更糟的选择。
 */
@Repository
public class QaAnalyticsRepository {

    // ==================== guard_action 的取值域 ====================
    //
    // ⚠️ 这四个值是裸字符串（写死在 router/MessageRouter.java），没有枚举类。
    //    集中在这里定义，别在 SQL 里散写 —— 之前散写导致各处口径不一致。
    //
    // 产品对「提问」的定义（2026-09-26 确认）：一条消息必须【同时】满足
    //   ① @ 了机器人（通过准入/提及检测）
    //   ② 机器人给出了有效回应，且这次回应【没有被拦截】
    //
    // 对照四个取值，只有 PASS 同时满足两条：
    //   pass        五层 Guard 全放行 + 预算 OK → 调模型并已发送        → 是提问
    //   drop        静默不回。混装四类：没 @ / 黑名单 / Guard 拦截 /
    //               群级预算超限 / 全局熔断 / kill-switch              → 被拦，不是提问
    //   fixed_reply 回固定话术、不调模型。混装：纯表情兜底 / 入站敏感词 /
    //               限流提示 / 用户级预算超限                          → 被拦，不是提问
    //   command     命中后台配置指令（/help 等），在 Guard 之前执行      → 有回应，但不是提问
    //
    // ⚠️ fixed_reply 最容易被误当成"机器人回了"，但 guard/GuardMetrics 里
    //    **Drop + Reply 都算拦截**。两边口径必须一致，否则同一屏上
    //    「拦截率」和「回答量」两个数字会互相打架。
    private static final String ACTION_PASS = "'pass'";
    private static final String ACTION_DROP = "'drop'";
    private static final String ACTION_FIXED_REPLY = "'fixed_reply'";
    private static final String ACTION_COMMAND = "'command'";

    // ==================== 行形状 ====================

    /** 看板总览的一组计数 */
    public record OverviewCounts(long total, long questions, long dropped, long fixedReplies,
                                 long commands, long users, long groups, long hits) {
    }

    /** 一条提问的延迟 */
    public record Latency(long retrieveMs, long totalMs) {
    }

    /** 某个 guard_action 有多少条 */
    public record ActionCount(String action, long count) {
    }

    /** 按本地日期分桶 */
    public record DayCountRow(String day, long count) {
    }

    /** 关键词排行的一行 */
    public record KeywordStatRow(String keyword, String termEn, long count, long missCount) {
    }

    /** 未命中排行的一行（不含示例问题 —— 那是另一次查询） */
    public record MissRow(String keyword, String termEn, long count, double bestCosineRaw) {
    }

    /** 未命中且没认出词的一行 */
    public record UnmatchedMissRow(String ts, String question, double bestCosineRaw) {
    }

    /** 检索来源统计的一行 */
    public record SourceStatRow(String sources, long count, long hits) {
    }

    /** 标注统计的一行 */
    public record VerdictRow(String verdict, long count) {
    }

    /** 一条问答记录 */
    public record RecordRow(long id, String ts, long groupId, long userId,
                            int hitCount, double bestCosine, String sources, String guardAction,
                            long retrieveMs, long totalMs, String question, String answer,
                            String verdict) {
    }

    /** 后台访问计数 */
    public record VisitCounts(long total, long visitors) {
    }

    /** 后台访问路径 */
    public record PathCount(String path, long count) {
    }

    private final Jdbc jdbc;

    public QaAnalyticsRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    // ==================== 看板总览 ====================

    /**
     * 总览的一组计数。
     *
     * <p>{@code where} 里的 {@code group_id > 0} 见类注释（排除广场生成的行）。
     */
    public OverviewCounts overviewCounts(String cutoff) {
        String where = (cutoff == null ? " WHERE" : " WHERE ts >= ? AND") + " group_id > 0";
        return jdbc.queryOne("SELECT COUNT(*) t,"
                        + " SUM(CASE WHEN guard_action = " + ACTION_PASS + " THEN 1 ELSE 0 END) q,"
                        + " SUM(CASE WHEN guard_action = " + ACTION_DROP + " THEN 1 ELSE 0 END) d,"
                        + " SUM(CASE WHEN guard_action = " + ACTION_FIXED_REPLY + " THEN 1 ELSE 0 END) f,"
                        + " SUM(CASE WHEN guard_action = " + ACTION_COMMAND + " THEN 1 ELSE 0 END) c,"
                        // 独立用户/群只算【提过问的】—— 闲聊的人不是用户
                        + " COUNT(DISTINCT CASE WHEN guard_action = " + ACTION_PASS
                        + " THEN user_id END) u,"
                        + " COUNT(DISTINCT CASE WHEN guard_action = " + ACTION_PASS
                        + " THEN group_id END) g,"
                        + " SUM(CASE WHEN guard_action = " + ACTION_PASS
                        + " AND hit_count > 0 THEN 1 ELSE 0 END) h"
                        + " FROM qa_stat" + where,
                r -> new OverviewCounts(r.longOf("t"), r.longOf("q"), r.longOf("d"), r.longOf("f"),
                        r.longOf("c"), r.longOf("u"), r.longOf("g"), r.longOf("h")),
                ts(cutoff));
    }

    /** 每条**提问**的延迟（分位数在业务侧算）—— 见类注释 */
    public List<Latency> passLatencies(String cutoff) {
        return jdbc.query("SELECT retrieve_ms, total_ms FROM qa_stat"
                        + (cutoff == null ? " WHERE" : " WHERE ts >= ? AND")
                        + " guard_action = " + ACTION_PASS,
                r -> new Latency(r.longOf("retrieve_ms"), r.longOf("total_ms")), ts(cutoff));
    }

    /** 各 guard_action 的条数 */
    public List<ActionCount> actionCounts(String cutoff) {
        String where = (cutoff == null ? " WHERE" : " WHERE ts >= ? AND") + " group_id > 0";
        return jdbc.query("SELECT guard_action, COUNT(*) c FROM qa_stat" + where
                        + " GROUP BY guard_action",
                r -> new ActionCount(r.str("guard_action"), r.longOf("c")), ts(cutoff));
    }

    /** 每日【提问量】—— 不是每日消息量；日期按 {@link AppTime#SQL_DAY} 算 */
    public List<DayCountRow> dailyQuestions(String cutoff) {
        return jdbc.query("SELECT " + AppTime.SQL_DAY + " d, COUNT(*) c FROM qa_stat"
                        + (cutoff == null ? " WHERE" : " WHERE ts >= ? AND")
                        + " guard_action = " + ACTION_PASS
                        + " AND group_id > 0"
                        + " GROUP BY d ORDER BY d",
                r -> new DayCountRow(r.str("d"), r.longOf("c")), ts(cutoff));
    }

    // ==================== 排行 ====================

    /** 关键词排行；{@code missCount} = 这个词出现时"整条消息没检索到任何资料"的次数 */
    public List<KeywordStatRow> keywords(String cutoff, int topN) {
        String sql = "SELECT k.keyword, k.term_en, COUNT(*) c,"
                + " SUM(CASE WHEN s.hit_count = 0 THEN 1 ELSE 0 END) miss"
                + " FROM qa_keyword k JOIN qa_stat s ON s.id = k.stat_id"
                // 只算机器人真的作答过的行 —— 否则群里的闲聊会把榜单灌满
                + " WHERE s.guard_action = " + ACTION_PASS + andTs(cutoff)
                + " GROUP BY k.keyword, k.term_en ORDER BY c DESC, k.keyword LIMIT ?";
        return jdbc.query(sql,
                r -> new KeywordStatRow(r.str("keyword"), r.str("term_en"),
                        r.longOf("c"), r.longOf("miss")),
                args(cutoff, topN));
    }

    /** 未命中排行：整条消息一条资料都没检索到的那些关键词 —— 直接告诉我们要补什么 */
    public List<MissRow> misses(String cutoff, int topN) {
        String sql = "SELECT k.keyword, k.term_en, COUNT(*) c, MAX(s.best_cosine_raw) raw"
                + " FROM qa_keyword k JOIN qa_stat s ON s.id = k.stat_id"
                + " WHERE s.hit_count = 0 AND s.guard_action = " + ACTION_PASS + andTs(cutoff)
                + " GROUP BY k.keyword, k.term_en ORDER BY c DESC, k.keyword LIMIT ?";
        return jdbc.query(sql,
                r -> new MissRow(r.str("keyword"), r.str("term_en"),
                        r.longOf("c"), r.dbl("raw")),
                args(cutoff, topN));
    }

    /** 某个未命中关键词的示例问题（原文可能已被清理，那时返回 {@code (原文已清理)}） */
    public List<String> missSamples(String cutoff, String keyword, int limit) {
        String sql = "SELECT r.question FROM qa_stat s"
                + " JOIN qa_raw r ON r.id = s.id"
                + " JOIN qa_keyword k ON k.stat_id = s.id"
                + " WHERE s.hit_count = 0 AND k.keyword = ? AND s.guard_action = " + ACTION_PASS
                + andTs(cutoff)
                + " LIMIT ?";
        // ⚠️ 参数顺序跟着 SQL 走：keyword 在 ts 之前
        Object[] params = cutoff == null
                ? new Object[]{keyword, limit}
                : new Object[]{keyword, cutoff, limit};
        return jdbc.query(sql, r -> {
            String q = r.str("question");
            return q == null ? "(原文已清理)" : q;
        }, params);
    }

    /**
     * 未命中且抽不出关键词的问题 —— 术语表连"这是什么"都没认出来。
     *
     * <p>为什么单独列：{@link #misses} 是靠 {@code qa_keyword} 关联的，
     * 而"没认出词"的记录在关键词表里根本没有行，会被 join 掉、永远不出现。
     */
    public List<UnmatchedMissRow> unmatchedMisses(String cutoff, int limit) {
        String sql = "SELECT s.ts, r.question, s.best_cosine_raw FROM qa_stat s"
                + " LEFT JOIN qa_raw r ON r.id = s.id"
                + " WHERE s.hit_count = 0 AND s.guard_action = " + ACTION_PASS
                + " AND NOT EXISTS (SELECT 1 FROM qa_keyword k WHERE k.stat_id = s.id)"
                + andTs(cutoff) + " ORDER BY s.ts DESC LIMIT ?";
        return jdbc.query(sql, r -> {
            String q = r.str("question");
            return new UnmatchedMissRow(r.str("ts"),
                    q == null ? "(原文已清理)" : q, r.dbl("best_cosine_raw"));
        }, args(cutoff, limit));
    }

    // ==================== 分布 ====================

    /**
     * 所有**提问**行的最高余弦（NULL 已剔除）—— 分桶在业务侧算。
     *
     * <p>⚠️ {@code AND ts >= ?}：这条查询前面**已经有** {@code WHERE guard_action} 了。见类注释。
     */
    public List<Double> cosines(String cutoff) {
        List<Double> raw = jdbc.query("SELECT best_cosine FROM qa_stat WHERE guard_action = " + ACTION_PASS
                        + andTsPlain(cutoff),
                r -> r.isNull("best_cosine") ? null : r.dbl("best_cosine"), ts(cutoff));
        return raw.stream().filter(Objects::nonNull).toList();
    }

    /** ⚠️ {@code AND ts >= ?}：见类注释 */
    public List<SourceStatRow> sources(String cutoff) {
        return jdbc.query("SELECT sources, COUNT(*) c, SUM(CASE WHEN hit_count > 0 THEN 1 ELSE 0 END) h"
                        + " FROM qa_stat WHERE guard_action = " + ACTION_PASS + andTsPlain(cutoff)
                        + " GROUP BY sources ORDER BY c DESC",
                r -> new SourceStatRow(r.str("sources"), r.longOf("c"), r.longOf("h")), ts(cutoff));
    }

    /** ⚠️ {@code AND ts >= ?}：见类注释 */
    public List<VerdictRow> verdicts(String cutoff) {
        return jdbc.query("SELECT COALESCE(verdict,'unknown') v, COUNT(*) c FROM qa_stat"
                        + " WHERE guard_action = " + ACTION_PASS + andTsPlain(cutoff)
                        + " GROUP BY v ORDER BY c DESC",
                r -> new VerdictRow(r.str("v"), r.longOf("c")), ts(cutoff));
    }

    // ==================== 记录列表 ====================

    /**
     * 最近记录（后台列表用）。question/answer 可能因原文清理而为空。
     *
     * <p>⚠️ 修过一个口径 bug：这里原来<b>只有时间过滤</b>，于是「问答记录」页
     * 列出的 93%（1194/1279）是群里没 @ 机器人的闲聊，页面却顶着一个
     * 「还没有记录？去群里 @ 机器人问几个问题」的提示语 —— 自相矛盾。
     *
     * @param onlyQuestions true = 只看真正的提问（{@code guard_action='pass'}）；
     *                      false = 看全部消息，用于排查"为什么没回我"
     */
    public List<RecordRow> recentRecords(String cutoff, int limit, int offset, boolean onlyQuestions) {
        String sql = "SELECT s.id, s.ts, s.group_id, s.user_id, s.hit_count, s.best_cosine,"
                + " s.sources, s.guard_action, s.retrieve_ms, s.total_ms, r.question, r.answer,"
                + " s.verdict"
                + " FROM qa_stat s LEFT JOIN qa_raw r ON r.id = s.id"
                + recordWhere(cutoff, onlyQuestions) + " ORDER BY s.id DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql,
                r -> new RecordRow(r.longOf("id"), r.str("ts"), r.longOf("group_id"),
                        r.longOf("user_id"), r.intOf("hit_count"), r.dbl("best_cosine"),
                        r.str("sources"), r.str("guard_action"), r.longOf("retrieve_ms"),
                        r.longOf("total_ms"), r.str("question"), r.str("answer"), r.str("verdict")),
                args(cutoff, limit, offset));
    }

    /** 记录总数 —— 与 {@link #recentRecords} 用**同一套过滤**，供分页用 */
    public long countRecords(String cutoff, boolean onlyQuestions) {
        return jdbc.count("SELECT COUNT(*) FROM qa_stat" + recordWhere(cutoff, onlyQuestions),
                ts(cutoff));
    }

    /** ⚠️ 过滤条件只此一处 —— 列表与计数分头写就会分页错位 */
    private static String recordWhere(String cutoff, boolean onlyQuestions) {
        StringBuilder w = new StringBuilder();
        if (cutoff != null) {
            w.append(" WHERE ts >= ?");
        }
        if (onlyQuestions) {
            w.append(cutoff == null ? " WHERE" : " AND").append(" guard_action = ").append(ACTION_PASS);
        }
        return w.toString();
    }

    // ==================== 后台访问 ====================

    public VisitCounts visitCounts(String cutoff) {
        return jdbc.queryOne("SELECT COUNT(*) c, COUNT(DISTINCT ip_hash) v FROM admin_visit" + whereTs(cutoff),
                r -> new VisitCounts(r.longOf("c"), r.longOf("v")), ts(cutoff));
    }

    public List<DayCountRow> visitsDaily(String cutoff) {
        return jdbc.query("SELECT " + AppTime.SQL_DAY + " d, COUNT(*) c FROM admin_visit"
                        + whereTs(cutoff) + " GROUP BY d ORDER BY d",
                r -> new DayCountRow(r.str("d"), r.longOf("c")), ts(cutoff));
    }

    public List<PathCount> visitPaths(String cutoff, int limit) {
        return jdbc.query("SELECT path, COUNT(*) c FROM admin_visit" + whereTs(cutoff)
                        + " GROUP BY path ORDER BY c DESC LIMIT ?",
                r -> new PathCount(r.str("path"), r.longOf("c")), args(cutoff, limit));
    }

    // ==================== 拼 SQL 的小工具 ====================

    /**
     * 基础语句**已经有 WHERE 且表带别名 s** 时用这个（见类注释）。
     *
     * <p>别名是必须区分的：{@code cosines} / {@code sources} / {@code verdicts} 三条
     * 查的是裸 {@code qa_stat}（没有 join，也就没有别名），写成 {@code s.ts} 会
     * {@code no such column: s.ts}。
     */
    private static String andTs(String cutoff) {
        return cutoff == null ? "" : " AND s.ts >= ?";
    }

    /** 同 {@link #andTs}，但基础语句的表**没有别名** */
    private static String andTsPlain(String cutoff) {
        return cutoff == null ? "" : " AND ts >= ?";
    }

    /** 基础语句**没有 WHERE** 时用这个 */
    private static String whereTs(String cutoff) {
        return cutoff == null ? "" : " WHERE ts >= ?";
    }

    private static Object[] ts(String cutoff) {
        return cutoff == null ? new Object[0] : new Object[]{cutoff};
    }

    /** {@code cutoff} 在最前，其余参数按出现顺序跟在后面 */
    private static Object[] args(String cutoff, Object... rest) {
        Object[] out = new Object[(cutoff == null ? 0 : 1) + rest.length];
        int i = 0;
        if (cutoff != null) {
            out[i++] = cutoff;
        }
        for (Object o : rest) {
            out[i++] = o;
        }
        return out;
    }
}
