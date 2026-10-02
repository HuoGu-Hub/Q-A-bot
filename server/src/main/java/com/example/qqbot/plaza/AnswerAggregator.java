package com.example.qqbot.plaza;

import com.example.qqbot.config.PlazaProperties;
import com.example.qqbot.persistence.SqliteConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 答案聚合：把「同一个关键词下的多条历史问答」整理成可展示的列表。
 *
 * <p>这是问答广场的核心逻辑。做三件事：
 * <ol>
 *   <li><b>按关键词找相关问答</b> —— 走 qa_keyword → qa_stat → qa_raw</li>
 *   <li><b>按投票排序</b> —— 赞数高的在前</li>
 *   <li><b>去重</b> —— 相似的答案合并（同一个问题问多次，回答可能几乎一样）</li>
 * </ol>
 *
 * <p><b>隐私约束</b>：默认只返回「被点赞过」的答案（{@code plaza.only-voted=true}）。
 * 没人点赞过的问答不出现在公开站 —— 相当于社区审核。
 */
@Component
public class AnswerAggregator {

    private static final Logger log = LoggerFactory.getLogger(AnswerAggregator.class);

    private final SqliteConnectionProvider db;
    private final PlazaStore plazaStore;
    private final PlazaProperties props;

    public AnswerAggregator(SqliteConnectionProvider db, PlazaStore plazaStore, PlazaProperties props) {
        this.db = db;
        this.plazaStore = plazaStore;
        this.props = props;
    }

    /**
     * 广场上展示的一条答案。
     *
     * @param statId   对应 qa_stat.id（投票时要用）
     * @param question 问题原文
     * @param answer   回答原文
     * @param up/down/outdated 投票数
     * @param score    赞 - 踩（排序用）
     * @param badge    标记：best（最高赞）/ outdated（被标过时）
     */
    public record PlazaAnswer(
            long statId,
            String question,
            String answer,
            long up, long down, long outdated,
            long score,
            String badge) {
    }

    /** 一个关键词的广场页面数据 */
    public record KeywordPage(
            String keyword,
            String termEn,
            long askedCount,
            boolean needsNewAnswer,
            boolean needsHelp,
            List<PlazaAnswer> answers) {
    }

    /**
     * 取某个关键词的广场页面。
     *
     * @param keyword 中文关键词（如「废料杯」）
     */
    public KeywordPage page(String keyword) {
        if (!props.isEnabled() || !plazaStore.isAvailable() || keyword == null || keyword.isBlank()) {
            return empty(keyword);
        }

        List<RawAnswer> raw = loadRaw(keyword.trim());
        long askedCount = raw.size();
        if (raw.isEmpty()) {
            return new KeywordPage(keyword, null, 0, false, false, List.of());
        }

        String termEn = raw.get(0).termEn;

        // 批量取投票统计
        Map<Long, PlazaStore.VoteSummary> votes = plazaStore.voteSummaries(
                raw.stream().map(r -> r.statId).toList());

        // 组装 + 算分
        List<PlazaAnswer> all = new ArrayList<>();
        for (RawAnswer r : raw) {
            PlazaStore.VoteSummary v = votes.getOrDefault(r.statId,
                    new PlazaStore.VoteSummary(r.statId, 0, 0, 0));
            // ★ 隐私：只展示被点赞过的（默认）
            if (props.isOnlyVoted() && v.up() <= 0) {
                continue;
            }
            all.add(new PlazaAnswer(r.statId, r.question, r.answer,
                    v.up(), v.down(), v.outdated(), v.score(), null));
        }

        // 排序：分高在前；同分则赞数多的在前
        all.sort(Comparator.comparingLong(PlazaAnswer::score).reversed()
                .thenComparing(Comparator.comparingLong(PlazaAnswer::up).reversed()));

        // 去重（相似的合并）
        List<PlazaAnswer> deduped = dedup(all);

        // 取前 N 条
        int max = Math.max(1, props.getMaxAnswersPerKeyword());
        List<PlazaAnswer> top = deduped.size() > max ? deduped.subList(0, max) : deduped;

        // 打标记
        List<PlazaAnswer> marked = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            PlazaAnswer a = top.get(i);
            String badge = null;
            if (i == 0 && a.up() > 0) {
                badge = "best";
            }
            if (a.outdated() > a.up()) {
                badge = "outdated";
            }
            marked.add(new PlazaAnswer(a.statId(), a.question(), a.answer(),
                    a.up(), a.down(), a.outdated(), a.score(), badge));
        }

        // ★ 三级降级的判定
        int threshold = props.getDownvoteThreshold();
        boolean allBad = !marked.isEmpty() && marked.stream().allMatch(a ->
                a.down() >= threshold && a.down() > a.up());
        // 没有可展示的答案时也算「需要新答案」
        boolean none = marked.isEmpty() && askedCount > 0;
        boolean needsNew = allBad || none;

        return new KeywordPage(keyword, termEn, askedCount, needsNew, false, marked);
    }

    /** 关键词列表（公开站首页展示「大家都在查什么」） */
    public List<Map<String, Object>> hotKeywords(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!props.isEnabled() || !plazaStore.isAvailable()) {
            return out;
        }
        String sql = "SELECT k.keyword, k.term_en, COUNT(*) c,"
                + " MAX(s.hit_count) hit"
                + " FROM qa_keyword k JOIN qa_stat s ON s.id = k.stat_id"
                // ⚠️ 必须过滤提问：qa_keyword 对【所有】消息都抽了关键词（含群里没 @
                //    机器人的闲聊），不加这个条件，热词榜会被闲聊灌满 ——
                //    实测「装备」16 次（真实 6 次）、「欢迎」4 次（真实 0 次）。
                + " WHERE s.guard_action = 'pass'"
                + " GROUP BY k.keyword, k.term_en ORDER BY c DESC LIMIT ?";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        String kw = rs.getString(1);
                        // 过滤泛词 —— 「武器」这种点进去等于没点
                        if (com.example.qqbot.qa.KeywordFilter.isTooGeneric(kw)) {
                            continue;
                        }
                        m.put("keyword", kw);
                        m.put("termEn", rs.getString(2));
                        m.put("count", rs.getLong(3));
                        // 有多少条被点赞过（决定公开站会不会有内容）
                        m.put("votedCount", countVoted(kw));
                        out.add(m);
                    }
                }
            } catch (Exception e) {
                log.warn("[PLAZA] 取热门关键词失败：{}", e.getMessage());
            }
        }
        return out;
    }

    // ==================== 内部 ====================

    private record RawAnswer(long statId, String question, String answer, String termEn) {
    }

    /** 按关键词拉原始问答（只取有原文的） */
    private List<RawAnswer> loadRaw(String keyword) {
        List<RawAnswer> out = new ArrayList<>();
        String sql = "SELECT s.id, r.question, r.answer, k.term_en"
                + " FROM qa_keyword k"
                + " JOIN qa_stat s ON s.id = k.stat_id"
                + " LEFT JOIN qa_raw r ON r.id = s.id"
                + " WHERE k.keyword = ? AND r.answer IS NOT NULL AND r.answer != ''"
                // 同上：只算真正的提问。fixed_reply 行的 answer 是限流/敏感词话术，
                // 混进来会把它算成"这个关键词被问过几次"
                + " AND s.guard_action = 'pass'"
                + " ORDER BY s.id DESC LIMIT 200";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, keyword);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new RawAnswer(rs.getLong(1), rs.getString(2),
                                rs.getString(3), rs.getString(4)));
                    }
                }
            } catch (Exception e) {
                log.warn("[PLAZA] 取关键词问答失败：{}", e.getMessage());
            }
        }
        return out;
    }

    /** 某关键词有多少条被点赞过 */
    private long countVoted(String keyword) {
        String sql = "SELECT COUNT(DISTINCT k.stat_id)"
                + " FROM qa_keyword k"
                + " JOIN answer_vote v ON v.stat_id = k.stat_id"
                + " JOIN qa_stat s ON s.id = k.stat_id"
                // 只有提问才可能出现在广场上；不过滤的话，闲聊行的关键词
                // 一旦被投票也会被算进来（drop 行占了 qa_keyword 的多数）
                + " WHERE k.keyword = ? AND v.vote = 'up' AND s.guard_action = 'pass'";
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, keyword);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /**
     * 去重：相似的答案只留分最高的那条。
     *
     * <p>为什么需要：同一个问题被问多次、检索到同样的资料，
     * 模型生成的回答往往**几乎一样**。展示三条雷同的答案体验很差。
     *
     * <p>相似度用**字符 3-gram Jaccard** 近似 —— 不需要引入向量计算，
     * 对这种「近似重复文本」的判定够用了。
     */
    private List<PlazaAnswer> dedup(List<PlazaAnswer> sorted) {
        double threshold = props.getDedupThreshold();
        List<PlazaAnswer> out = new ArrayList<>();
        for (PlazaAnswer candidate : sorted) {
            boolean similar = out.stream().anyMatch(
                    kept -> similarity(kept.answer(), candidate.answer()) >= threshold);
            if (!similar) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** 字符 3-gram Jaccard 相似度 */
    static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        var sa = ngrams(a);
        var sb = ngrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0;
        }
        var inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        var union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    private static java.util.Set<String> ngrams(String s) {
        String t = s.replaceAll("\\s+", "");
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i + 3 <= t.length(); i++) {
            out.add(t.substring(i, i + 3));
        }
        return out;
    }

    private KeywordPage empty(String keyword) {
        return new KeywordPage(keyword, null, 0, false, false, List.of());
    }
}
