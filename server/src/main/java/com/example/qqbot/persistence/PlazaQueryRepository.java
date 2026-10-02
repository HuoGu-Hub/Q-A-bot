package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 广场的**跨表读** —— SQL 只在这里。
 *
 * <p>这些查询都跨 {@code qa_stat} / {@code qa_raw} / {@code qa_keyword} / {@code answer_vote}，
 * 属于"报表式读取"，不适合塞进任何单个 Store。
 *
 * <p><b>过滤条件跟着 SQL 一起搬过来</b>，因为它们不是可选的优化而是正确性条件：
 * <ul>
 *   <li>{@code s.guard_action = 'pass'} —— {@code qa_keyword} 对**所有**消息都抽了关键词
 *       （含群里没 @ 机器人的闲聊）。不加这个条件，热词榜会被闲聊灌满：
 *       实测「装备」16 次（真实 6 次）、「欢迎」4 次（真实 0 次）。</li>
 *   <li>{@code v.vote = 'up'} —— 只有提问才可能出现在广场上。</li>
 * </ul>
 */
@Repository
public class PlazaQueryRepository {

    /** 后台"已投票答案"列表的一行 */
    public record VotedAnswer(long statId, String ts, long groupId, long userId, String source,
                              String question, String answer, long up, long down, long outdated) {
    }

    /** 热词榜的一行 */
    public record HotKeyword(String keyword, String termEn, long count, long maxHit) {
    }

    /** 某个关键词下的一条原始问答 */
    public record RawAnswer(long statId, String question, String answer, String termEn) {
    }

    private final Jdbc jdbc;

    public PlazaQueryRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    /** 后台：按净赞数排序的答案列表 */
    public List<VotedAnswer> votedAnswers(int limit) {
        return jdbc.query("SELECT v.stat_id, s.ts, s.group_id, s.user_id, s.source,"
                        + " MAX(r.question) question, MAX(r.answer) answer,"
                        + " SUM(CASE WHEN v.vote='up' THEN 1 ELSE 0 END) up,"
                        + " SUM(CASE WHEN v.vote='down' THEN 1 ELSE 0 END) down,"
                        + " SUM(CASE WHEN v.vote='outdated' THEN 1 ELSE 0 END) outdated"
                        + " FROM answer_vote v"
                        + " JOIN qa_stat s ON s.id = v.stat_id"
                        + " LEFT JOIN qa_raw r ON r.id = v.stat_id"
                        + " GROUP BY v.stat_id ORDER BY (up - down) DESC, up DESC LIMIT ?",
                r -> new VotedAnswer(r.longOf("stat_id"), r.str("ts"), r.longOf("group_id"),
                        r.longOf("user_id"), r.str("source"), r.str("question"), r.str("answer"),
                        r.longOf("up"), r.longOf("down"), r.longOf("outdated")),
                Math.min(limit, 200));
    }

    /** 热词榜：只算真正的提问（{@code guard_action = 'pass'}） */
    public List<HotKeyword> hotKeywords(int limit) {
        return jdbc.query("SELECT k.keyword, k.term_en, COUNT(*) c,"
                        + " MAX(s.hit_count) hit"
                        + " FROM qa_keyword k JOIN qa_stat s ON s.id = k.stat_id"
                        + " WHERE s.guard_action = 'pass'"
                        + " GROUP BY k.keyword, k.term_en ORDER BY c DESC LIMIT ?",
                r -> new HotKeyword(r.str("keyword"), r.str("term_en"), r.longOf("c"), r.longOf("hit")),
                limit);
    }

    /** 某个关键词下的原始问答（只取有原文的） */
    public List<RawAnswer> rawAnswers(String keyword) {
        return jdbc.query("SELECT s.id, r.question, r.answer, k.term_en"
                        + " FROM qa_keyword k"
                        + " JOIN qa_stat s ON s.id = k.stat_id"
                        + " LEFT JOIN qa_raw r ON r.id = s.id"
                        + " WHERE k.keyword = ? AND r.answer IS NOT NULL AND r.answer != ''"
                        + " AND s.guard_action = 'pass'"
                        + " ORDER BY s.id DESC LIMIT 200",
                r -> new RawAnswer(r.longOf("id"), r.str("question"), r.str("answer"), r.str("term_en")),
                keyword);
    }

    /** 某个关键词有多少条被点赞过 */
    public long countVoted(String keyword) {
        return jdbc.count("SELECT COUNT(DISTINCT k.stat_id)"
                + " FROM qa_keyword k"
                + " JOIN answer_vote v ON v.stat_id = k.stat_id"
                + " JOIN qa_stat s ON s.id = k.stat_id"
                + " WHERE k.keyword = ? AND v.vote = 'up' AND s.guard_action = 'pass'", keyword);
    }
}
