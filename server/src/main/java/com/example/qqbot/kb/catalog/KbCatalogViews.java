package com.example.qqbot.kb.catalog;

import com.example.qqbot.kb.category.KbGroups;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 词条层的<b>只读规则</b>：视图筛选、检索匹配、板块归类、统计口径。
 *
 * <h2>为什么单独成类</h2>
 * 它原先住在 {@code KbTermService} 里，与"写名字"和"组装目录"挤在一起。
 * 筛选与口径单独成类之后，"看到什么就导出什么"这条约束只在一个文件里改，
 * 而它服务的对象是组合层的目录读模型 {@link KbCatalog}。
 *
 * <p>输出类型复用 {@link KbCatalog} 的公开记录（{@code Term} / {@code Counts} /
 * {@code BoardStat}）—— 那些是调用方认得的词汇，搬走等于制造一次没有收益的改签。
 * 本类与单个词条无关的方法一律 static：它们不持有任何状态。
 */
final class KbCatalogViews {

    /** 视图预设。前端只传这一个参数，语义全在这里，省得每个筛选各写一遍 */
    static final List<String> VIEWS =
            List.of("main", "all", "draft", "verified", "rejected", "unnamed", "nochunk");

    private KbCatalogViews() {
    }

    /**
     * 列表与导出<b>共用</b>的筛选 —— 保证「看到什么就导出什么」。
     *
     * <p>以前导出是单独一份逻辑（只认 view），于是「在某个板块视图下点导出」
     * 得到的是全表 —— 651 条待补译名混在 3416 行里，人得自己筛。
     *
     * @param view 已由调用方定好默认值（列表是 main、导出是 all），这里不再兜底
     */
    static List<KbCatalog.Term> filter(Map<String, KbCatalog.Term> all,
                                          String q, String view, String board) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String v = view == null || view.isBlank() ? "all" : view.trim().toLowerCase(Locale.ROOT);
        List<KbCatalog.Term> out = new ArrayList<>();
        for (KbCatalog.Term t : all.values()) {
            if (!matchesView(t, v)) {
                continue;
            }
            if (board != null && !board.isEmpty() && !board.equals(t.board())) {
                continue;
            }
            if (!needle.isEmpty() && !matchesQuery(t, needle)) {
                continue;
            }
            out.add(t);
        }
        // 按英文名排序：列表要能翻页、导出要能对账，顺序必须稳定且可预期
        out.sort(Comparator.comparing(KbCatalog.Term::en, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static boolean matchesView(KbCatalog.Term t, String view) {
        return switch (view) {
            case "all" -> true;
            // 默认视图：把「既没正文、又没中文名」的空页面挡在外面，
            // 它们只该在「无正文」预设里被主动看到
            case "main" -> t.chunkCount() > 0 || hasChinese(t.zh());
            case "draft" -> "draft".equals(t.status());
            case "verified" -> "verified".equals(t.status());
            case "rejected" -> "rejected".equals(t.status());
            // 「还没有中文名」= zh 里**一个中文字符都没有**（不是 isEmpty，理由见 hasChinese）
            case "unnamed" -> !hasChinese(t.zh());
            case "nochunk" -> t.chunkCount() == 0;
            default -> true;
        };
    }

    private static boolean matchesQuery(KbCatalog.Term t, String needleLower) {
        if (t.en().toLowerCase(Locale.ROOT).contains(needleLower)) {
            return true;
        }
        if (t.zh().toLowerCase(Locale.ROOT).contains(needleLower)) {
            return true;
        }
        return t.aliases().stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).contains(needleLower));
    }

    static KbCatalog.Counts count(Map<String, KbCatalog.Term> all) {
        int main = 0;
        int withChunks = 0;
        int noChunk = 0;
        int draft = 0;
        int verified = 0;
        int rejected = 0;
        int unnamed = 0;
        for (KbCatalog.Term t : all.values()) {
            if (t.chunkCount() > 0) {
                withChunks++;
            } else {
                noChunk++;
            }
            if (!hasChinese(t.zh())) {
                unnamed++;
            }
            if (t.chunkCount() > 0 || hasChinese(t.zh())) {
                main++;
            }
            switch (t.status()) {
                case "verified" -> verified++;
                case "rejected" -> rejected++;
                case "draft" -> draft++;
                default -> {
                    // "" —— 还没中文名，不计入任何核对状态
                }
            }
        }
        return new KbCatalog.Counts(all.size(), main, withChunks, noChunk, draft, verified, rejected, unnamed);
    }

    static List<KbCatalog.BoardStat> boardStats(Map<String, KbCatalog.Term> all) {
        Map<String, int[]> perBoard = new LinkedHashMap<>();
        for (KbCatalog.Term t : all.values()) {
            int[] acc = perBoard.computeIfAbsent(t.board(), x -> new int[2]);
            acc[0]++;
            acc[1] += t.chunkCount();
        }
        List<KbCatalog.BoardStat> boards = new ArrayList<>();
        for (KbGroups.Group g : KbGroups.ALL) {
            int[] acc = perBoard.getOrDefault(g.key(), new int[2]);
            boards.add(new KbCatalog.BoardStat(g.key(), g.label(), g.icon(), g.desc(), acc[0], acc[1]));
        }
        int[] hidden = perBoard.getOrDefault(KbGroups.HIDDEN, new int[2]);
        boards.add(new KbCatalog.BoardStat(KbGroups.HIDDEN, "版本更新（不对外展示）", "",
                "更新日志、早期访问说明等，公开站隐藏", hidden[0], hidden[1]));
        return boards;
    }

    /**
     * 词条归属板块。
     *
     * <p>取<b>第一个非「版本更新类」的分类</b>。理由：2501 个块至少带一个隐藏分类，
     * 416 个块是「纯隐藏」；如果按 {@code cats[0]} 取，归属就由 Wiki 的编辑顺序决定，
     * 更新日志页会挤进玩家的分类里。没有任何可见分类时归 {@code __hidden__}；
     * 连分类都没有（308 个块）归「其他」。
     */
    static String boardOf(List<String> cats, Map<String, String> rawToGroup) {
        if (cats == null || cats.isEmpty()) {
            return KbGroups.DEFAULT_GROUP;
        }
        String firstAny = null;
        for (String raw : cats) {
            String g = rawToGroup.getOrDefault(raw, KbGroups.autoGroup(raw));
            if (firstAny == null) {
                firstAny = g;
            }
            if (!KbGroups.HIDDEN.equals(g)) {
                return g;
            }
        }
        return firstAny == null ? KbGroups.DEFAULT_GROUP : firstAny;
    }

    static String boardLabel(String board, Map<String, String> rawToLabel) {
        if (KbGroups.HIDDEN.equals(board)) {
            return "版本更新（不对外展示）";
        }
        KbGroups.Group g = KbGroups.get(board);
        return g == null ? board : g.label();
    }

    /** 中文名用「、」「/」「｜」「|」分隔多个别名（与 Glossary 的解析保持一致） */
    static List<String> splitAliases(String zh) {
        if (zh.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : zh.split("[、/｜|]")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 这个中文名里<b>有没有中文字符</b> —— 判定「还没有中文名」的唯一正确方式。
     *
     * <h2>⚠️ 为什么不能用 {@code isEmpty()}（2026-10-04 实测踩到）</h2>
     * {@code buildAll} 里有一句回退：词条没中文名时<b>退回用块标题</b>。
     * 而自动导入的英文页（技能 / 机制 / 属性 / 收集品笔记）标题就是英文，
     * 于是它们的 zh 是 {@code "Strength"} 这种<b>非空英文</b>。
     *
     * <p>结果：{@code case "unnamed" -> zh.isEmpty()} <b>恒为 false</b>，
     * 「无中文名」筛选器一条都抓不到（实测 651 条该被抓的抓不到），
     * 而 {@code main} 的「挡掉空页面」也同时失效 —— 两处都是同一个原因。
     *
     * <p>所以这里按「有没有汉字」判定：不依赖回退行为，也不会把
     * {@code "Bamboo Ladder、Bamboo Ladder"} 这种「中文名和别名都是英文」的漏掉。
     */
    static boolean hasChinese(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}
