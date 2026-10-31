package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 最近几轮问答表（{@code qa_turn}）。
 *
 * <p>和 {@code qa_stat} 的分工：那张表是**留档**，按保留期删原文、永久留统计；
 * 这张表是**短期记忆**，只为了让下一条消息知道"刚才聊的是哪个东西"。所以它只存摘要、
 * 每人只留几条、过期就删 —— 它不是分析数据源，删掉不心疼。
 */
@Repository
public class TurnRepository {

    /** 一轮问答的摘要。{@code answerDigest} 是回答的前几十个字 */
    public record Row(long id, String ts, String question, String answerDigest, boolean corrected) {
    }

    private static final Jdbc.RowMapper<Row> MAP = r -> new Row(
            r.longOf("id"), r.str("ts"), r.str("question"), r.str("answer_digest"),
            r.intOf("corrected") == 1);

    private final Jdbc jdbc;

    public TurnRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS qa_turn ("
                + " id            INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " group_id      INTEGER NOT NULL DEFAULT 0,"
                + " user_id       INTEGER NOT NULL DEFAULT 0,"
                + " ts            TEXT NOT NULL,"
                + " question      TEXT NOT NULL,"
                + " answer_digest TEXT NOT NULL DEFAULT '',"
                + " corrected     INTEGER NOT NULL DEFAULT 0"
                + " )");
        // 查询永远是「这个群这个人、按时间倒序」，索引照着它建
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_qa_turn_scope"
                + " ON qa_turn(group_id, user_id, ts DESC)");
    }

    public void insert(long groupId, long userId, String ts, String question, String digest) {
        jdbc.update("INSERT INTO qa_turn(group_id, user_id, ts, question, answer_digest, corrected)"
                + " VALUES(?, ?, ?, ?, ?, 0)", groupId, userId, ts, question, digest);
    }

    /** 某人在某群最近的几轮，新的在前 */
    public List<Row> recent(long groupId, long userId, String since, int limit) {
        return jdbc.query("SELECT id, ts, question, answer_digest, corrected FROM qa_turn"
                + " WHERE group_id = ? AND user_id = ? AND ts >= ?"
                + " ORDER BY ts DESC, id DESC LIMIT ?", MAP, groupId, userId, since, limit);
    }

    public void markCorrected(long id) {
        jdbc.update("UPDATE qa_turn SET corrected = 1 WHERE id = ?", id);
    }

    /** 只留最近 {@code keep} 条 —— 记忆不需要归档 */
    public int trim(long groupId, long userId, int keep) {
        return jdbc.update("DELETE FROM qa_turn WHERE group_id = ? AND user_id = ? AND id NOT IN"
                        + " (SELECT id FROM qa_turn WHERE group_id = ? AND user_id = ?"
                        + "  ORDER BY ts DESC, id DESC LIMIT ?)",
                groupId, userId, groupId, userId, keep);
    }

    public int deleteOlderThan(String cutoff) {
        return jdbc.update("DELETE FROM qa_turn WHERE ts < ?", cutoff);
    }

    public long countAll() {
        return jdbc.count("SELECT COUNT(*) FROM qa_turn");
    }
}
