package com.example.qqbot.plaza;

import com.example.qqbot.config.PlazaProperties;
import com.example.qqbot.persistence.PlazaQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    private final PlazaQueryRepository queries;
    private final PlazaStore plazaStore;
    private final PlazaProperties props;

    public AnswerAggregator(PlazaQueryRepository queries, PlazaStore plazaStore, PlazaProperties props) {
        this.queries = queries;
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
        try {
            for (PlazaQueryRepository.HotKeyword k : queries.hotKeywords(limit)) {
                // 过滤泛词 —— 「武器」这种点进去等于没点（业务规则，留在这一层）
                if (com.example.qqbot.qa.KeywordFilter.isTooGeneric(k.keyword())) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("keyword", k.keyword());
                m.put("termEn", k.termEn());
                m.put("count", k.count());
                // 有多少条被点赞过（决定公开站会不会有内容）
                m.put("votedCount", countVoted(k.keyword()));
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("[PLAZA] 取热门关键词失败：{}", e.getMessage());
        }
        return out;
    }

    // ==================== 内部 ====================

    private record RawAnswer(long statId, String question, String answer, String termEn) {
    }

    /** 按关键词拉原始问答（只取有原文的） */
    private List<RawAnswer> loadRaw(String keyword) {
        try {
            return queries.rawAnswers(keyword).stream()
                    .map(r -> new RawAnswer(r.statId(), r.question(), r.answer(), r.termEn()))
                    .toList();
        } catch (Exception e) {
            log.warn("[PLAZA] 取关键词问答失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 某关键词有多少条被点赞过 */
    private long countVoted(String keyword) {
        try {
            return queries.countVoted(keyword);
        } catch (Exception e) {
            return 0;
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
