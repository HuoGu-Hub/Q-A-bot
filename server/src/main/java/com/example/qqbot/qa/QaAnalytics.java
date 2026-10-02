package com.example.qqbot.qa;

import com.example.qqbot.config.AppTime;
import com.example.qqbot.config.QaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答记录的统计分析（S2）。
 *
 * <p><b>只出结构化数据，不负责打印。</b> 这样 CLI 报表（S2）和后台 API（S3）
 * 能共用同一套统计口径 —— 否则两边的"关键词排行"很可能算得不一样。
 *
 * <p>用**只读连接**打开同一个 SQLite 文件：WAL 模式支持并发读，
 * 所以跑报表不会打扰正在写记录的机器人进程。
 */
@Component
public class QaAnalytics {

    private static final Logger log = LoggerFactory.getLogger(QaAnalytics.class);

    private final QaProperties props;

    public QaAnalytics(QaProperties props) {
        this.props = props;
    }

    // ==================== 结果类型 ====================

    /**
     * 看板总览。**口径在这里说清楚**，因为它被误解过好几轮。
     *
     * <p>产品对「提问」的定义（2026-09-26 确认）：一条消息必须<b>同时</b>满足两条
     * 才算一次提问 —— ① @ 了机器人；② 机器人给出了有效回应且未被拦截。
     * 对照 {@code guard_action} 的四个取值，<b>只有 {@code pass} 同时满足</b>
     * （见下方 ACTION_* 常量的注释）。
     *
     * <p>所以：
     * <ul>
     *   <li>{@code total}     —— <b>群消息总数</b>。含没 @ 的闲聊、含被拦的。
     *       它<b>不是</b>提问数，任何对外文案都不许叫它"问答数"。</li>
     *   <li>{@code questions} —— <b>提问量</b>（{@code pass}）。这是唯一能叫"提问"的数。</li>
     *   <li>{@code hits}      —— 提问中检索到了资料的条数。</li>
     *   <li>{@code hitRate}   —— {@code hits / questions}，即"提问→检索命中率"。</li>
     *   <li>{@code users}/{@code groups} —— <b>提过问的</b>独立用户/群。
     *       ⚠️ 曾漏加 {@code pass} 过滤，实测把 90 个"说过话的人"算成独立用户，
     *       真实只有 25 个 —— 群里闲聊的人不是用户。
     *       ⚠️ 只算 {@code group_id > 0}：广场生成的答案写的是 {@code group_id = 0}
     *       （见 {@code PlazaStore.saveGeneratedAnswer}），不排除的话
     *       {@code COUNT(DISTINCT group_id)} 会凭空多出一个"0 号群"。</li>
     *   <li>{@code dropped}/{@code fixedReplies} —— 被拦截的两种，分开列。
     *       两者语义都是"被拦"，不该混进任何"回答量"。</li>
     *   <li>{@code commands}  —— 命中的指令数。有回应，但<b>不是提问</b>。</li>
     *   <li>{@code daily}     —— 每日<b>提问量</b>（{@code pass}），不是每日消息量。</li>
     *   <li>延迟分位数只统计 {@code pass} 行。⚠️ 曾跨全部 action 统计，被 1194 条
     *       {@code drop} 行的 {@code retrieve_ms=0} 拉低 —— 全表均值 39ms，
     *       而真实提问是 697ms。</li>
     * </ul>
     */
    public record Overview(long total, long questions,
                           long hits, double hitRate,
                           long users, long groups,
                           long dropped, long fixedReplies, long commands,
                           long p50RetrieveMs, long p95RetrieveMs,
                           long p50TotalMs, long p95TotalMs,
                           Map<String, Long> guardActions,
                           List<DayCount> daily) {
    }

    public record DayCount(String day, long count) {
    }

    /** 关键词排行。missCount = 这个词出现时"整条消息没检索到任何资料"的次数 */
    public record KeywordStat(String zh, String en, long count, long missCount) {
    }

    /** 未命中问题的关键词排行 + 示例问题 */
    /**
     * @param bestCosineRaw 这些未命中问题里，A 路（向量）卡阈值<b>之前</b>的最高余弦。
     *                      接近阈值（比如 0.40+）= 差一点被挡，该考虑调低阈值；
     *                      很低（0.2 以下）= 库里真没这份资料，调阈值没用、该补资料。
     */
    public record MissStat(String zh, String en, long count, double bestCosineRaw, List<String> samples) {
    }

    /**
     * 未命中、**且连一个游戏名词都没识别出来**的问题。
     *
     * <p>这类最值得看：说明术语表里根本没有对应的词 ——
     * 连"用户在问什么"都没认出来，比"认出来了但语料里没有"更严重。
     */
    /** @param bestCosineRaw 同上：卡阈值之前的最高余弦，用来判断该调阈值还是该补资料 */
    public record UnmatchedMiss(String ts, String question, double bestCosineRaw) {
    }

    public record Bucket(String range, long count) {
    }

    /** 最近问答记录（后台列表用）。question/answer 可能因原文清理而为空 */
    public record RecordRow(long id, String ts, long groupId, long userId,
                            int hitCount, double bestCosine, String sources, String guardAction,
                            long retrieveMs, long totalMs, String question, String answer,
                            String verdict) {
    }

    public record VisitDay(String day, long count) {
    }

    public record VisitSummary(long total, long visitors, List<VisitDay> daily, List<SourceStat> paths) {
    }

    public record SourceStat(String source, long count, long hits, double hitRate) {
    }

    public record VerdictStat(String verdict, long count) {
    }

    // ==================== 统计入口 ====================

    /** 统计窗口的起始时间（ISO8601）；days<=0 时返回 null 表示不限 */
    private String cutoff(int days) {
        return days <= 0 ? null : Instant.now().minus(days, ChronoUnit.DAYS).toString();
    }

    private Connection open() throws SQLException {
        Path file = Paths.get(props.getDb()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return DriverManager.getConnection("jdbc:sqlite:" + file);
    }

    /** 打开连接跑一段查询；库不存在或出错都返回空结果，绝不抛给调用方 */
    private <T> T query(int days, T empty, SqlFunction<T> fn) {
        Path file = Paths.get(props.getDb()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            return empty;
        }
        try (Connection conn = open()) {
            if (conn == null) {
                return empty;
            }
            return fn.apply(conn, cutoff(days));
        } catch (Exception e) {
            log.warn("[QA] 统计查询失败：{}", e.getMessage());
            return empty;
        }
    }

    private interface SqlFunction<T> {
        T apply(Connection conn, String cutoff) throws SQLException;
    }

    // ==================== guard_action 的语义 ====================
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

    // ==================== 各段报表 ====================

    public Overview overview(int days) {
        return query(days, new Overview(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), List.of()),
                (conn, cutoff) -> {
                    // 只看真实群聊产生的行：广场生成的答案 group_id = 0，
                    // 它不是"群消息"，混进来会让 KPI「群消息总数」大于各 action 之和
                    String where = (cutoff == null ? " WHERE" : " WHERE ts >= ? AND") + " group_id > 0";
                    long total;
                    long questions;
                    long users;
                    long groups;
                    long hits;
                    long dropped;
                    long fixedReplies;
                    long commands;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT COUNT(*) t,"
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
                                    + " FROM qa_stat" + where)) {
                        bind(ps, cutoff);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            total = rs.getLong("t");
                            questions = rs.getLong("q");
                            dropped = rs.getLong("d");
                            fixedReplies = rs.getLong("f");
                            commands = rs.getLong("c");
                            users = rs.getLong("u");
                            groups = rs.getLong("g");
                            hits = rs.getLong("h");
                        }
                    }

                    // 延迟分位数只统计提问行：drop 行的 retrieve_ms 恒为 0，
                    // 混进来会把 P50/P95 拉低一个数量级（实测全表 39ms vs 提问 697ms）
                    List<Long> retrieveMs = new ArrayList<>();
                    List<Long> totalMs = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT retrieve_ms, total_ms FROM qa_stat"
                                    + (cutoff == null ? " WHERE" : " WHERE ts >= ? AND")
                                    + " guard_action = " + ACTION_PASS)) {
                        bind(ps, cutoff);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                retrieveMs.add(rs.getLong(1));
                                totalMs.add(rs.getLong(2));
                            }
                        }
                    }

                    Map<String, Long> guard = new LinkedHashMap<>();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT guard_action, COUNT(*) c FROM qa_stat" + where + " GROUP BY guard_action")) {
                        bind(ps, cutoff);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                guard.put(rs.getString(1), rs.getLong(2));
                            }
                        }
                    }

                    // 每日【提问量】—— 不是每日消息量，否则这张图会被没 @ 的闲聊淹没。
                    // 日期按 AppTime 的时区算，不是 UTC —— 否则早上 8 点前的提问会落到前一天
                    List<DayCount> daily = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT " + AppTime.SQL_DAY + " d, COUNT(*) c FROM qa_stat"
                                    + (cutoff == null ? " WHERE" : " WHERE ts >= ? AND")
                                    + " guard_action = " + ACTION_PASS
                                    + " AND group_id > 0"
                                    + " GROUP BY d ORDER BY d")) {
                        bind(ps, cutoff);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                daily.add(new DayCount(rs.getString(1), rs.getLong(2)));
                            }
                        }
                    }

                    // 命中率的分母是【提问量】，不是消息总数
                    double rate = questions == 0 ? 0 : (double) hits / questions;
                    return new Overview(total, questions, hits, rate, users, groups,
                            dropped, fixedReplies, commands,
                            percentile(retrieveMs, 0.50), percentile(retrieveMs, 0.95),
                            percentile(totalMs, 0.50), percentile(totalMs, 0.95),
                            guard, daily);
                });
    }

    public List<KeywordStat> keywords(int days, int topN) {
        return query(days, List.of(), (conn, cutoff) -> {
            String where = cutoff == null ? "" : " AND s.ts >= ?";
            String sql = "SELECT k.keyword, k.term_en, COUNT(*) c,"
                    + " SUM(CASE WHEN s.hit_count = 0 THEN 1 ELSE 0 END) miss"
                    + " FROM qa_keyword k JOIN qa_stat s ON s.id = k.stat_id"
                    // 只算机器人真的作答过的行 —— 否则群里的闲聊会把榜单灌满
                    + " WHERE s.guard_action = 'pass'" + where
                    + " GROUP BY k.keyword, k.term_en ORDER BY c DESC, k.keyword LIMIT ?";
            List<KeywordStat> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int i = 1;
                if (cutoff != null) {
                    ps.setString(i++, cutoff);
                }
                ps.setInt(i, topN);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new KeywordStat(rs.getString(1), rs.getString(2),
                                rs.getLong(3), rs.getLong(4)));
                    }
                }
            }
            return out;
        });
    }

    /** 未命中排行：整条消息一条资料都没检索到的那些关键词 —— 直接告诉我们要补什么 */
    public List<MissStat> misses(int days, int topN, int samplesPerKeyword) {
        return query(days, List.of(), (conn, cutoff) -> {
            String where = cutoff == null ? "" : " AND s.ts >= ?";
            String sql = "SELECT k.keyword, k.term_en, COUNT(*) c, MAX(s.best_cosine_raw) raw"
                    + " FROM qa_keyword k JOIN qa_stat s ON s.id = k.stat_id"
                    + " WHERE s.hit_count = 0 AND s.guard_action = 'pass'" + where
                    + " GROUP BY k.keyword, k.term_en ORDER BY c DESC, k.keyword LIMIT ?";
            List<MissStat> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int i = 1;
                if (cutoff != null) {
                    ps.setString(i++, cutoff);
                }
                ps.setInt(i, topN);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String zh = rs.getString(1);
                        List<String> samples = samplesForMiss(conn, cutoff, zh, samplesPerKeyword);
                        out.add(new MissStat(zh, rs.getString(2), rs.getLong(3), rs.getDouble(4), samples));
                    }
                }
            }
            return out;
        });
    }

    private List<String> samplesForMiss(Connection conn, String cutoff, String keyword, int limit)
            throws SQLException {
        String where = cutoff == null ? "" : " AND s.ts >= ?";
        String sql = "SELECT r.question FROM qa_stat s"
                + " JOIN qa_raw r ON r.id = s.id"
                + " JOIN qa_keyword k ON k.stat_id = s.id"
                + " WHERE s.hit_count = 0 AND k.keyword = ? AND s.guard_action = 'pass'" + where
                + " LIMIT ?";
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, keyword);
            if (cutoff != null) {
                ps.setString(i++, cutoff);
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String q = rs.getString(1);
                    out.add(q == null ? "(原文已清理)" : q);
                }
            }
        }
        return out;
    }

    /**
     * 未命中且抽不出关键词的问题 —— 术语表连"这是什么"都没认出来。
     *
     * <p>为什么单独列：{@link #misses} 是靠 qa_keyword 关联的，
     * 而"没认出词"的记录在关键词表里根本没有行，会被 join 掉、永远不出现。
     */
    public List<UnmatchedMiss> unmatchedMisses(int days, int limit) {
        return query(days, List.of(), (conn, cutoff) -> {
            String where = cutoff == null ? "" : " AND s.ts >= ?";
            String sql = "SELECT s.ts, r.question, s.best_cosine_raw FROM qa_stat s"
                    + " LEFT JOIN qa_raw r ON r.id = s.id"
                    + " WHERE s.hit_count = 0 AND s.guard_action = 'pass'"
                    + " AND NOT EXISTS (SELECT 1 FROM qa_keyword k WHERE k.stat_id = s.id)"
                    + where + " ORDER BY s.ts DESC LIMIT ?";
            List<UnmatchedMiss> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int i = 1;
                if (cutoff != null) {
                    ps.setString(i++, cutoff);
                }
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String q = rs.getString(2);
                        out.add(new UnmatchedMiss(rs.getString(1),
                                q == null ? "(原文已清理)" : q, rs.getDouble(3)));
                    }
                }
            }
            return out;
        });
    }

    /**
     * 最高余弦的分布 —— <b>定 min-score 阈值就看这张表</b>。
     *
     * <p>把 0~1 分成 10 档，每档再拆成"检索到了资料"和"没检索到"。
     * 阈值应该卡在"正常问题"和"无关问题"之间。
     */
    public List<Bucket> cosineDistribution(int days) {
        return query(days, List.of(), (conn, cutoff) -> {
            // ⚠️ 必须用 AND：下面这条查询的前面**已经有** WHERE guard_action='pass' 了。
            //    原先这里写的是 " WHERE ts >= ?"，拼出来是两个 WHERE → SQL 语法错误 →
            //    被 query() 的兜底吞成空列表。后果是看板上的「余弦分布 / 检索来源 / 标注」
            //    对任何「近 7 / 30 / 90 天」都是空的，只有「全部」能出数据。
            String where = cutoff == null ? "" : " AND ts >= ?";
            List<Double> values = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT best_cosine FROM qa_stat WHERE guard_action = 'pass'" + where)) {
                bind(ps, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double v = rs.getDouble(1);
                        if (!rs.wasNull()) {
                            values.add(v);
                        }
                    }
                }
            }
            long[] buckets = new long[10];
            for (double v : values) {
                int idx = (int) Math.floor(Math.max(0, Math.min(0.999, v)) * 10);
                buckets[idx]++;
            }
            List<Bucket> out = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                out.add(new Bucket(String.format("%.1f~%.1f", i / 10.0, (i + 1) / 10.0), buckets[i]));
            }
            return out;
        });
    }

    public List<SourceStat> sources(int days) {
        return query(days, List.of(), (conn, cutoff) -> {
            // ⚠️ 必须用 AND：下面这条查询的前面**已经有** WHERE guard_action='pass' 了。
            //    原先这里写的是 " WHERE ts >= ?"，拼出来是两个 WHERE → SQL 语法错误 →
            //    被 query() 的兜底吞成空列表。后果是看板上的「余弦分布 / 检索来源 / 标注」
            //    对任何「近 7 / 30 / 90 天」都是空的，只有「全部」能出数据。
            String where = cutoff == null ? "" : " AND ts >= ?";
            List<SourceStat> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT sources, COUNT(*) c, SUM(CASE WHEN hit_count > 0 THEN 1 ELSE 0 END) h"
                            + " FROM qa_stat WHERE guard_action = 'pass'" + where
                            + " GROUP BY sources ORDER BY c DESC")) {
                bind(ps, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long c = rs.getLong(2);
                        long h = rs.getLong(3);
                        out.add(new SourceStat(rs.getString(1), c, h, c == 0 ? 0 : (double) h / c));
                    }
                }
            }
            return out;
        });
    }

    public List<VerdictStat> verdicts(int days) {
        return query(days, List.of(), (conn, cutoff) -> {
            // ⚠️ 必须用 AND：下面这条查询的前面**已经有** WHERE guard_action='pass' 了。
            //    原先这里写的是 " WHERE ts >= ?"，拼出来是两个 WHERE → SQL 语法错误 →
            //    被 query() 的兜底吞成空列表。后果是看板上的「余弦分布 / 检索来源 / 标注」
            //    对任何「近 7 / 30 / 90 天」都是空的，只有「全部」能出数据。
            String where = cutoff == null ? "" : " AND ts >= ?";
            List<VerdictStat> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COALESCE(verdict,'unknown') v, COUNT(*) c FROM qa_stat"
                            + " WHERE guard_action = 'pass'" + where
                            + " GROUP BY v ORDER BY c DESC")) {
                bind(ps, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new VerdictStat(rs.getString(1), rs.getLong(2)));
                    }
                }
            }
            return out;
        });
    }

    /** 最近的问答记录（后台列表） */
    /**
     * 最近记录（后台列表用）。question/answer 可能因原文清理而为空。
     *
     * <p>⚠️ 修过一个口径 bug：这里原来<b>只有时间过滤</b>，于是「问答记录」页
     * 列出的 93%（1194/1279）是群里没 @ 机器人的闲聊，页面却顶着一个
     * 「还没有记录？去群里 @ 机器人问几个问题」的提示语 —— 自相矛盾。
     *
     * @param onlyQuestions true（默认）= 只看真正的提问（{@code guard_action='pass'}）；
     *                      false = 看全部消息，用于排查"为什么没回我"
     */
    public List<RecordRow> recentRecords(int days, int limit, int offset, boolean onlyQuestions) {
        return query(days, List.of(), (conn, cutoff) -> {
            StringBuilder w = new StringBuilder();
            if (cutoff != null) {
                w.append(" WHERE s.ts >= ?");
            }
            if (onlyQuestions) {
                w.append(cutoff == null ? " WHERE" : " AND")
                        .append(" s.guard_action = ").append(ACTION_PASS);
            }
            String sql = "SELECT s.id, s.ts, s.group_id, s.user_id, s.hit_count, s.best_cosine,"
                    + " s.sources, s.guard_action, s.retrieve_ms, s.total_ms, r.question, r.answer,"
                    + " s.verdict"
                    + " FROM qa_stat s LEFT JOIN qa_raw r ON r.id = s.id"
                    + w + " ORDER BY s.id DESC LIMIT ? OFFSET ?";
            List<RecordRow> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int i = 1;
                if (cutoff != null) {
                    ps.setString(i++, cutoff);
                }
                ps.setInt(i++, limit);
                ps.setInt(i, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new RecordRow(
                                rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                                rs.getInt(5), rs.getDouble(6), rs.getString(7), rs.getString(8),
                                rs.getLong(9), rs.getLong(10),
                                rs.getString(11), rs.getString(12), rs.getString(13)));
                    }
                }
            }
            return out;
        });
    }

    /** 记录总数 —— 与 {@link #recentRecords} 用同一套过滤，供分页用 */
    public long countRecords(int days, boolean onlyQuestions) {
        return query(days, 0L, (conn, cutoff) -> {
            StringBuilder w = new StringBuilder();
            if (cutoff != null) {
                w.append(" WHERE ts >= ?");
            }
            if (onlyQuestions) {
                w.append(cutoff == null ? " WHERE" : " AND")
                        .append(" guard_action = ").append(ACTION_PASS);
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM qa_stat" + w)) {
                bind(ps, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** 管理后台的访问统计 */
    public VisitSummary visits(int days) {
        return query(days, new VisitSummary(0, 0, List.of(), List.of()), (conn, cutoff) -> {
            String where = cutoff == null ? "" : " WHERE ts >= ?";
            long total = 0;
            long visitors = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) c, COUNT(DISTINCT ip_hash) v FROM admin_visit" + where)) {
                bind(ps, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                    visitors = rs.getLong(2);
                }
            }
            List<VisitDay> daily = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + AppTime.SQL_DAY + " d, COUNT(*) c FROM admin_visit" + where
                            + " GROUP BY d ORDER BY d")) {
                bind(ps, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        daily.add(new VisitDay(rs.getString(1), rs.getLong(2)));
                    }
                }
            }
            List<SourceStat> paths = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT path, COUNT(*) c FROM admin_visit" + where
                            + " GROUP BY path ORDER BY c DESC LIMIT 20")) {
                bind(ps, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long c = rs.getLong(2);
                        paths.add(new SourceStat(rs.getString(1), c, c, 1.0));
                    }
                }
            }
            return new VisitSummary(total, visitors, daily, paths);
        });
    }

    // ==================== 小工具 ====================

    private static void bind(PreparedStatement ps, String cutoff) throws SQLException {
        if (cutoff != null) {
            ps.setString(1, cutoff);
        }
    }

    /** 线性插值分位数（先排序） */
    static long percentile(List<Long> values, double p) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }
}
