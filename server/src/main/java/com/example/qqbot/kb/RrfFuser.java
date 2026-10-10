package com.example.qqbot.kb;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RRF 融合 + 标题加成 + 闸门置顶。
 *
 * <p><b>为什么按排名而不是按分数</b>：两路的分数量纲完全不同（余弦 0~1 vs 词频累加），
 * 直接相加没有意义；按排名融合既不需要归一化，也对"某一路特别自信"天然鲁棒。
 *
 * <p>键是<b>条目的 id</b>（不是行号）—— 换存储之后"同一个块"靠 id 认。
 */
@Component
public class RrfFuser implements KbFuser {

    /**
     * RRF 融合参数。{@code score = Σ w / (K + rank)}。
     */
    private static final int RRF_K = 10;

    /** 关键词路的权重。它一旦命中就是"术语表里确实有这个游戏名词"，精度很高，所以给更大权重 */
    private static final double KEYWORD_WEIGHT = 2.0;

    /**
     * 标题<b>正好等于</b>查询里的游戏名词时的加成。
     *
     * <p>实测的问题：搜「废料杯」时，正文里提到 Scrap Cup 的 Tableware 页面会因为两路都排得
     * 靠前而压过 Scrap Cup 本体；搜「火把」时 Crypt Skull Torch 会排在 Torch 前面。
     * <b>用户打的是物品名，要的就是那一页</b>，所以标题精确命中直接给一个足够大的加成。
     */
    private static final double TITLE_EXACT_BONUS = 0.5;

    /** 标题里出现了这个词（但不是全部）时的少量加成 */
    private static final double TITLE_PARTIAL_BONUS = 0.15;

    /**
     * 置顶那一条在 RRF 量纲下的报告分。
     *
     * <p>闸门命中的块不是通过排名融合进来的，本来没有 RRF 分；但 {@code Hit.score}
     * 会进问答统计，混一个余弦进来会把统计口径搞乱。所以给它一个"某一路的第 1 名"
     * 该有的值 —— 只用于展示，不参与任何排序。
     */
    private static final double GATE_FUSED_SCORE = 1.0 / (RRF_K + 1);

    @Override
    public List<KbRetriever.Hit> fuse(List<KbRetriever.Hit> byVector, List<KbRetriever.Hit> byKeyword,
                                      int topK, List<String> terms) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, KbCorpus.Entry> entries = new HashMap<>();
        Map<String, String> sources = new HashMap<>();

        for (int i = 0; i < byVector.size(); i++) {
            String id = byVector.get(i).entry().id();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            entries.put(id, byVector.get(i).entry());
            sources.merge(id, "vector", (a, b) -> a.equals(b) ? a : "both");
        }
        for (int i = 0; i < byKeyword.size(); i++) {
            String id = byKeyword.get(i).entry().id();
            scores.merge(id, KEYWORD_WEIGHT / (RRF_K + i + 1), Double::sum);
            entries.put(id, byKeyword.get(i).entry());
            sources.merge(id, "keyword", (a, b) -> a.equals(b) ? a : "both");
        }
        // 标题加成：用户打的是"物品名"时，物品页必须压过"正文提到它的页"
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            double bonus = titleBonus(entries.get(e.getKey()), terms);
            if (bonus > 0) {
                e.setValue(e.getValue() + bonus);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> new KbRetriever.Hit(entries.get(e.getKey()), e.getValue(), sources.get(e.getKey())))
                .toList();
    }

    @Override
    public List<KbRetriever.Hit> pin(List<KbRetriever.Hit> fused, KbRetriever.TitleGate gate, int topK) {
        if (!gate.hit() || gate.entry() == null) {
            return fused;
        }
        String id = gate.entry().id();
        List<KbRetriever.Hit> rest = new ArrayList<>();
        KbRetriever.Hit existing = null;
        for (KbRetriever.Hit h : fused) {
            if (h.entry().id().equals(id)) {
                existing = h;
            } else {
                rest.add(h);
            }
        }
        KbRetriever.Hit pinned = existing != null
                // 来源标成 x+title：既保留它原来是被哪一路找到的，又能让统计看出"闸门开过"
                ? new KbRetriever.Hit(existing.entry(), existing.score(), existing.source() + "+title")
                : new KbRetriever.Hit(gate.entry(), GATE_FUSED_SCORE, "title");
        List<KbRetriever.Hit> out = new ArrayList<>();
        out.add(pinned);
        for (KbRetriever.Hit h : rest) {
            if (out.size() >= topK) {
                break;
            }
            out.add(h);
        }
        return out;
    }

    /**
     * 标题加成：标题正好就是这个名词 → 大加成；标题里含这个词 → 小加成。
     *
     * <p><b>为什么要剥掉结尾的「（English）」</b>：语料里标题大量写成
     * {@code 酸性菌丝（Acidic Mycelium）}，而词条名是 {@code 酸性菌丝} ——
     * 两者<b>不相等</b>，于是本体永远只吃到"部分命中"的小加成。
     * 实测后果：「酸性菌丝有什么用」把本体排到了<b>第 3</b>，前面是两个 {@code 菌丝体}。
     * 剥掉之后中文名算精确命中，本体回到第 1；直接打英文也能命中括号里那半截。
     */
    private static double titleBonus(KbCorpus.Entry entry, List<String> terms) {
        if (terms == null || terms.isEmpty() || entry == null || entry.title() == null) {
            return 0;
        }
        String title = entry.title().toLowerCase(Locale.ROOT).trim();
        String head = stripTrailingParens(title);
        String inner = trailingParenContent(title);
        double best = 0;
        for (String raw : terms) {
            if (raw == null) {
                continue;
            }
            String term = raw.toLowerCase(Locale.ROOT).trim();
            if (term.isEmpty()) {
                continue;
            }
            if (title.equals(term) || head.equals(term) || term.equals(inner)) {
                return TITLE_EXACT_BONUS;
            }
            if (KeywordContentRetriever.containsTerm(title, term) || KeywordContentRetriever.containsTerm(head, term)) {
                best = Math.max(best, TITLE_PARTIAL_BONUS);
            }
        }
        return best;
    }

    /** 去掉标题<b>结尾</b>的「（…）」或「(…)」；只看最后一对，不碰中间的括号 */
    private static String stripTrailingParens(String s) {
        int close = s.length() - 1;
        if (close < 0) {
            return s;
        }
        char last = s.charAt(close);
        if (last != '）' && last != ')') {
            return s;
        }
        int at = s.lastIndexOf(last == '）' ? '（' : '(', close);
        return at > 0 ? s.substring(0, at).trim() : s;
    }

    /** 结尾括号里的内容（没有就空串）—— 英文原名就写在这里 */
    private static String trailingParenContent(String s) {
        int close = s.length() - 1;
        if (close < 0) {
            return "";
        }
        char last = s.charAt(close);
        if (last != '）' && last != ')') {
            return "";
        }
        int at = s.lastIndexOf(last == '）' ? '（' : '(', close);
        return at >= 0 ? s.substring(at + 1, close).trim() : "";
    }
}
