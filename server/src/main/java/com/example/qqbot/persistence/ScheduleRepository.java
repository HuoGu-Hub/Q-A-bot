package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时输出表（{@code bot_schedule}）的**数据访问**。
 *
 * <h2>时间列的存法不是随便定的</h2>
 * {@code fire_at} 存<b>定长 ISO 串</b>（毫秒固定三位、UTC）。扫描时用的是
 * {@code fire_at <= ?} 这种字符串比较，而 {@code Instant.toString()} 的精度是可变的：
 * {@code 13:30:00Z} 与 {@code 13:30:00.123Z} 按字典序比会得出相反结论
 * （{@code '.'} 小于 {@code 'Z'}），于是"到点的任务扫不出来"。
 * 定长之后字典序才等于时间序。
 *
 * <h2>抢行用条件更新，不用先查后改</h2>
 * {@link #markSending} 把 {@code status='pending'} 写进 WHERE 子句：更新到 1 行才算抢到。
 * 先读到的是旧状态，两个 tick 撞上会重复发 —— 而重复发是用户最容易发现的故障。
 */
@Repository
public class ScheduleRepository {

    /** 一行定时输出（{@code params} 是 JSON 原文，解析留给业务侧） */
    public record Row(long id, long groupId, long userId, String nickname, String content,
                      String paramsJson, String fireAt, String repeatRule, String status,
                      int attempts, String lastError, String createdAt, String sentAt) {
    }

    private static final String COLUMNS =
            "id, group_id, user_id, nickname, content, params, fire_at, repeat_rule, status,"
                    + " attempts, last_error, created_at, sent_at";

    private static final Jdbc.RowMapper<Row> MAP = r -> new Row(
            r.longOf("id"), r.longOf("group_id"), r.longOf("user_id"), r.str("nickname"),
            r.str("content"), r.str("params"), r.str("fire_at"), r.str("repeat_rule"), r.str("status"),
            r.intOf("attempts"), r.str("last_error"), r.str("created_at"), r.str("sent_at"));

    private final Jdbc jdbc;

    public ScheduleRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bot_schedule ("
                + " id          INTEGER PRIMARY KEY,"
                + " group_id    INTEGER NOT NULL DEFAULT 0,"
                + " user_id     INTEGER NOT NULL DEFAULT 0,"
                + " nickname    TEXT DEFAULT '',"
                + " content     TEXT NOT NULL,"
                + " params      TEXT NOT NULL DEFAULT '{}',"
                + " fire_at     TEXT NOT NULL,"
                + " repeat_rule TEXT NOT NULL DEFAULT 'once',"
                + " status      TEXT NOT NULL DEFAULT 'pending',"
                + " attempts    INTEGER NOT NULL DEFAULT 0,"
                + " last_error  TEXT,"
                + " created_at  TEXT NOT NULL,"
                + " sent_at     TEXT"
                + " )");
        // 扫描只走这一条：待发 + 到点
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_schedule_due ON bot_schedule(status, fire_at)");
        // 列表与每人上限按这个查
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_schedule_group ON bot_schedule(group_id, status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_schedule_user ON bot_schedule(user_id, status)");
    }

    public long insert(Row row) {
        return jdbc.insert("INSERT INTO bot_schedule"
                        + " (group_id, user_id, nickname, content, params, fire_at, repeat_rule,"
                        + "  status, attempts, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                row.groupId(), row.userId(), row.nickname(), row.content(), row.paramsJson(),
                row.fireAt(), row.repeatRule(), row.status(), row.attempts(), row.createdAt());
    }

    public Row findById(long id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM bot_schedule WHERE id = ?", MAP, id);
    }

    /** 某个群待发的，按到点时间排 */
    public List<Row> pendingByGroup(long groupId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_schedule"
                + " WHERE group_id = ? AND status IN ('pending', 'sending')"
                + " ORDER BY fire_at LIMIT ?", MAP, groupId, limit);
    }

    /** 某人在私聊里待发的 */
    public List<Row> pendingByUser(long userId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_schedule"
                + " WHERE user_id = ? AND group_id = 0 AND status IN ('pending', 'sending')"
                + " ORDER BY fire_at LIMIT ?", MAP, userId, limit);
    }

    /** 到点且还没被抢走的候选。真正的抢走在 {@link #markSending} */
    public List<Row> due(String nowIso, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_schedule"
                + " WHERE status = 'pending' AND fire_at <= ?"
                + " ORDER BY fire_at LIMIT ?", MAP, nowIso, limit);
    }

    /** 抢行：只有仍是 pending 才会被更新到。返回 0 表示被别人抢了 */
    public int markSending(long id) {
        return jdbc.update("UPDATE bot_schedule SET status = 'sending'"
                + " WHERE id = ? AND status = 'pending'", id);
    }

    /** 一次性任务发完：进入终态 */
    public int markSent(long id, String nowIso) {
        return jdbc.update("UPDATE bot_schedule SET status = 'sent', sent_at = ?, last_error = NULL"
                + " WHERE id = ?", nowIso, id);
    }

    /**
     * 周期任务发完：记一次已发，然后回到待发换下一个时刻。
     *
     * <p>次数清零是刻意的：那列记的是"这一轮失败了几次"，
     * 一轮成功之后就该归零，否则一个跑了半年的日报会永远带着 3 次失败。
     */
    public int rearm(long id, String fireAtIso, String sentAtIso) {
        return jdbc.update("UPDATE bot_schedule SET status = 'pending', fire_at = ?, sent_at = ?,"
                + " last_error = NULL, attempts = 0 WHERE id = ?", fireAtIso, sentAtIso, id);
    }

    /** 失败重试：次数 +1，记下原因，换个时刻再来一次 */
    public int retryLater(long id, String fireAtIso, String error) {
        return jdbc.update("UPDATE bot_schedule SET status = 'pending', fire_at = ?,"
                + " attempts = attempts + 1, last_error = ? WHERE id = ?", fireAtIso, error, id);
    }

    /**
     * 撤销：只在还挂着的时候生效。
     *
     * <p>{@code status IN (...)} 不能省。少了它，第二次撤销会静默"成功" ——
     * 更新语句命中没有变成 0 行，群友会以为刚撤掉的是另一条。
     */
    public int cancel(long id, String reason) {
        return jdbc.update("UPDATE bot_schedule SET status = 'canceled', last_error = ?"
                + " WHERE id = ? AND status IN ('pending', 'sending')", reason, id);
    }

    /** 终态：失败、过期用它，附带原因 */
    public int close(long id, String status, String error) {
        return jdbc.update("UPDATE bot_schedule SET status = ?, last_error = ? WHERE id = ?",
                status, error, id);
    }

    // ==================== 管理端 ====================

    /**
     * 管理端列表：按状态与群号筛，不筛的参数传空。
     *
     * <p>排序是 {@code fire_at DESC}：管理端最关心的是"最近要发的"与"刚刚发过的"，
     * 群内列表才需要正序。
     */
    public List<Row> adminQuery(String status, long groupId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM bot_schedule WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (groupId > 0) {
            sql.append(" AND group_id = ?");
            args.add(groupId);
        }
        sql.append(" ORDER BY fire_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MAP, args.toArray());
    }

    /**
     * 各状态条数。
     *
     * <p>一次 GROUP BY 拿全，而不是每种状态查一次 —— 概览上要显示五个数字，
     * 分成五条 SQL 只是把同一份扫描做五遍。
     */
    public Map<String, Long> countByStatus() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.query(
                "SELECT status, COUNT(*) c FROM bot_schedule GROUP BY status",
                r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", r.str("status"));
                    m.put("c", r.longOf("c"));
                    return m;
                })) {
            out.put(String.valueOf(row.get("status")), (Long) row.get("c"));
        }
        return out;
    }

    /** 待发计数：用于"每人最多几条" */
    public long countPendingByUser(long userId, long groupId) {
        return jdbc.count("SELECT COUNT(*) FROM bot_schedule"
                + " WHERE user_id = ? AND group_id = ? AND status IN ('pending', 'sending')", userId, groupId);
    }
}
