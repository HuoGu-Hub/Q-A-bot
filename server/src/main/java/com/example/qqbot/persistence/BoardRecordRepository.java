package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 板块记录表（{@code bot_record}）—— 所有板块的数据都在这**一张表**里。
 *
 * <h2>为什么是一张表</h2>
 * <ul>
 *   <li><b>编号共用</b>：每个板块各自一张表的话，两套自增序列一定会撞；</li>
 *   <li><b>软删只有一处</b>：删了能恢复，这件事在一张表里做才说得清；</li>
 *   <li><b>定时扫描不用跨表</b>：到点发的和立即展示的只是 {@code delivery} 不同。</li>
 * </ul>
 *
 * <h2>软删</h2>
 * {@code deleted_at} 非空即已删。所有面向群友的查询都带 {@code deleted_at IS NULL}，
 * 只有后台的"已删除"视图故意不带 —— 那是用来恢复的。
 *
 * <h2>时间列存定长 ISO 串</h2>
 * 扫描时用 {@code fire_at <= ?} 做字符串比较，而 {@code Instant.toString()} 精度可变：
 * {@code 13:30:00Z} 与 {@code 13:30:00.123Z} 按字典序会得出相反结论。定长之后字典序才等于时间序。
 */
@Repository
public class BoardRecordRepository {

    /** 投递方式：立即展示 / 到点发 */
    public static final String DIRECT = "direct";
    public static final String SCHEDULED = "scheduled";

    /** 一行记录（{@code params} 与 {@code times} 是 JSON 原文，解析留给业务侧） */
    public record Row(long id, String board, long groupId, long userId, String nickname,
                      String text, String paramsJson, String delivery,
                      String fireAt, String repeatRule, String timesJson, String anchorAt,
                      String status, int attempts, String lastError,
                      String createdAt, String sentAt, String deletedAt) {
    }

    private static final String COLUMNS =
            "id, board, group_id, user_id, nickname, text, params, delivery,"
                    + " fire_at, repeat_rule, times, anchor_at, status, attempts, last_error,"
                    + " created_at, sent_at, deleted_at";

    private static final Jdbc.RowMapper<Row> MAP = r -> new Row(
            r.longOf("id"), r.str("board"), r.longOf("group_id"), r.longOf("user_id"),
            r.str("nickname"), r.str("text"), r.str("params"), r.str("delivery"),
            r.str("fire_at"), r.str("repeat_rule"), r.str("times"), r.str("anchor_at"),
            r.str("status"), r.intOf("attempts"), r.str("last_error"),
            r.str("created_at"), r.str("sent_at"), r.str("deleted_at"));

    private final Jdbc jdbc;

    public BoardRecordRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bot_record ("
                + " id          INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " board       TEXT NOT NULL,"
                + " group_id    INTEGER NOT NULL DEFAULT 0,"
                + " user_id     INTEGER NOT NULL DEFAULT 0,"
                + " nickname    TEXT NOT NULL DEFAULT '',"
                + " text        TEXT NOT NULL DEFAULT '',"
                + " params      TEXT NOT NULL DEFAULT '{}',"
                + " delivery    TEXT NOT NULL DEFAULT 'direct',"
                + " fire_at     TEXT,"
                + " repeat_rule TEXT,"
                + " times       TEXT NOT NULL DEFAULT '[]',"
                + " anchor_at   TEXT,"
                + " status      TEXT NOT NULL DEFAULT 'pending',"
                + " attempts    INTEGER NOT NULL DEFAULT 0,"
                + " last_error  TEXT,"
                + " sending_at  TEXT,"
                + " created_at  TEXT NOT NULL,"
                + " sent_at     TEXT,"
                + " deleted_at  TEXT"
                + " )");
        // 板块列表：按板块 + 群 + 删除标记取，最常用
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_record_board"
                + " ON bot_record(board, group_id, deleted_at)");
        // 扫描只走这一条：没删的 + 到点的
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_record_due"
                + " ON bot_record(delivery, status, fire_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_record_user"
                + " ON bot_record(user_id, deleted_at)");
    }

    public long insert(Row row) {
        return jdbc.insert("INSERT INTO bot_record"
                        + " (board, group_id, user_id, nickname, text, params, delivery,"
                        + "  fire_at, repeat_rule, times, anchor_at, status, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.board(), row.groupId(), row.userId(), row.nickname(), row.text(),
                row.paramsJson(), row.delivery(), row.fireAt(), row.repeatRule(),
                row.timesJson() == null ? "[]" : row.timesJson(), row.anchorAt(),
                row.status(), row.createdAt());
    }

    public Row findById(long id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM bot_record WHERE id = ?", MAP, id);
    }

    /** 某个板块在某群的记录；默认不含已删 */
    public List<Row> listByBoard(String board, long groupId, boolean withDeleted, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM bot_record WHERE board = ?"
                + (groupId > 0 ? " AND group_id = ?" : "")
                + (withDeleted ? "" : " AND deleted_at IS NULL")
                + " ORDER BY id DESC LIMIT ?";
        List<Object> args = new ArrayList<>();
        args.add(board);
        if (groupId > 0) {
            args.add(groupId);
        }
        args.add(limit);
        return jdbc.query(sql, MAP, args.toArray());
    }

    /**
     * 软删。只打标记，行还在 —— 删错了能恢复，也留得住"谁在什么时候删了什么"。
     *
     * @return 1 = 这次真的删掉了；0 = 本来就不存在或已经删过
     */
    public int softDelete(long id, String nowIso) {
        return jdbc.update("UPDATE bot_record SET deleted_at = ?"
                + " WHERE id = ? AND deleted_at IS NULL", nowIso, id);
    }

    /** 从"已删除"里捡回来 */
    public int restore(long id) {
        return jdbc.update("UPDATE bot_record SET deleted_at = NULL WHERE id = ?", id);
    }

    // ==================== 扫描器专用 ====================

    /** 到点且没删、还没被抢走的候选 */
    public List<Row> due(String nowIso, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_record"
                + " WHERE delivery = 'scheduled' AND status = 'pending'"
                + " AND deleted_at IS NULL AND fire_at <= ?"
                + " ORDER BY fire_at LIMIT ?", MAP, nowIso, limit);
    }

    /** 抢行：只有仍是 pending 才会被更新到。返回 0 表示被别人抢了 */
    public int markSending(long id, String nowIso) {
        return jdbc.update("UPDATE bot_record SET status = 'sending', sending_at = ?"
                + " WHERE id = ? AND status = 'pending'", nowIso, id);
    }

    public int markSent(long id, String nowIso) {
        return jdbc.update("UPDATE bot_record SET status = 'sent', sent_at = ?, last_error = NULL"
                + " WHERE id = ?", nowIso, id);
    }

    public int rearm(long id, String fireAtIso, String sentAtIso) {
        return jdbc.update("UPDATE bot_record SET status = 'pending', fire_at = ?,"
                + " sent_at = ?, sending_at = NULL, attempts = 0 WHERE id = ?",
                fireAtIso, sentAtIso, id);
    }

    public int retryLater(long id, String fireAtIso, String error) {
        return jdbc.update("UPDATE bot_record SET status = 'pending', fire_at = ?,"
                + " attempts = attempts + 1, sending_at = NULL, last_error = ? WHERE id = ?",
                fireAtIso, error, id);
    }

    public int close(long id, String status, String error) {
        return jdbc.update("UPDATE bot_record SET status = ?, last_error = ? WHERE id = ?",
                status, error, id);
    }

    /**
     * 把卡在「发送中」太久的行为放回待发。
     *
     * <p>进程正好停在"已抢走、还没发完"之间时，那一行会永远停在 sending，
     * 之后再也不会被扫到 —— 数据还在，但永远不会发。
     */
    public int reclaimStaleSending(String before) {
        return jdbc.update("UPDATE bot_record SET status = 'pending'"
                + " WHERE status = 'sending' AND (sending_at IS NULL OR sending_at < ?)", before);
    }

    /** 某人在某个会话里还没删的条数 —— 防的是把定时发送当骚扰工具 */
    public long countLiveByUser(long userId, long groupId) {
        return jdbc.count("SELECT COUNT(*) FROM bot_record"
                + " WHERE user_id = ? AND group_id = ? AND deleted_at IS NULL", userId, groupId);
    }
}
