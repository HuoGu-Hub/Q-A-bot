package com.example.qqbot.kb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 同文档**兄弟块补全**：命中一份资料里的某一块时，把这份资料的其它块也补上。
 *
 * <h2>为什么必须补</h2>
 * 超长资料会被切成多块（wiki 一页实测可切成 {@code xxx}、{@code xxx-2}、{@code xxx-3}…）。
 * 检索是按**块**做的，一个问题往往只命中其中一块 —— 而这一块通常只讲整份资料的一小节。
 * 旧行为把这一块单独喂给模型，于是「升级材料」那块被命中、「它是什么」那块没被命中时，
 * 模型手里的资料就是残缺的，它只能照着残缺资料答。
 *
 * <h2>两个刻意的设计决定</h2>
 * <ol>
 *   <li><b>补出来的块追加在最后</b>，不是插在命中块旁边 ——
 *       这样前 k 名的排名<b>一个都不动</b>。排名是黄金集与问答统计的口径，
 *       "多给了两块资料"不该把它搅乱。</li>
 *   <li><b>有预算</b>（见 {@link Sibling}）：一份资料可能切成十几块，全量补进去会撑爆
 *       prompt、也会稀释真正相关那一块。默认最多补 6 块 / 5000 字。</li>
 * </ol>
 *
 * <p>只补**未被下架**的块：入参 {@code entries} 是检索快照（已排除下架），
 * 所以补出来的东西一定是可以进 prompt 的。
 */
final class KbSiblingExpander {

    /** 「（续 2）」这种切块标记 —— wiki 页面被切块时后续块带它，用它定顺序 */
    private static final Pattern CHUNK_MARK = Pattern.compile("[（(]续\\s*(\\d+)[）)]\\s*$");

    private KbSiblingExpander() {
    }

    /**
     * 补全。返回新列表；没有任何可补的块时**原样返回**入参。
     *
     * @param hits    已排序的命中（前 k 名）
     * @param entries 本次检索的语料快照（用来找同一 docId 的其它块）
     * @param cfg     预算；{@code null} 或关闭时原样返回
     */
    static List<KbRetriever.Hit> expand(List<KbRetriever.Hit> hits, List<KbCorpus.Entry> entries,
                                        Sibling cfg) {
        if (hits == null || hits.isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        if (cfg == null || !cfg.isEnabled() || entries == null || entries.isEmpty()) {
            return hits;
        }

        // 按 docId 归组：只有被切成多块的资料才需要补
        Map<String, List<KbCorpus.Entry>> byDoc = new HashMap<>();
        for (KbCorpus.Entry e : entries) {
            if (e == null || e.id() == null || docOf(e).isEmpty()) {
                continue;
            }
            byDoc.computeIfAbsent(docOf(e), k -> new ArrayList<>()).add(e);
        }
        boolean anySplit = false;
        for (KbRetriever.Hit h : hits) {
            List<KbCorpus.Entry> sib = byDoc.get(h.entry() == null ? "" : docOf(h.entry()));
            if (sib != null && sib.size() > 1) {
                anySplit = true;
                break;
            }
        }
        if (!anySplit) {
            return hits;
        }

        Set<String> seen = new HashSet<>();
        for (KbRetriever.Hit h : hits) {
            if (h.entry() != null && h.entry().id() != null) {
                seen.add(h.entry().id().value());
            }
        }

        List<KbRetriever.Hit> out = new ArrayList<>(hits);
        int added = 0;
        int addedChars = 0;
        for (KbRetriever.Hit h : hits) {
            if (added >= cfg.getMaxBlocks() || addedChars >= cfg.getMaxChars()) {
                break;
            }
            if (h.entry() == null) {
                continue;
            }
            List<KbCorpus.Entry> sib = byDoc.get(docOf(h.entry()));
            if (sib == null || sib.size() <= 1) {
                continue;
            }
            List<KbCorpus.Entry> ordered = new ArrayList<>(sib);
            ordered.sort(CHUNK_ORDER);
            for (KbCorpus.Entry e : ordered) {
                if (added >= cfg.getMaxBlocks() || addedChars >= cfg.getMaxChars()) {
                    break;
                }
                String id = e.id().value();
                if (seen.contains(id)) {
                    continue;
                }
                int len = e.text() == null ? 0 : e.text().length();
                // 至少补一块：单块就超预算时也把它给上，否则"补全"等于没做
                if (added > 0 && addedChars + len > cfg.getMaxChars()) {
                    break;
                }
                out.add(new KbRetriever.Hit(e, h.score(), "sibling"));
                seen.add(id);
                added++;
                addedChars += len;
            }
        }
        return out;
    }

    private static String docOf(KbCorpus.Entry e) {
        return e.docId() == null ? "" : e.docId().trim();
    }

    /** 先按标题里的「（续 N）」定序，再按 id 自然序（保证 {@code -2} 排在 {@code -10} 前） */
    private static final Comparator<KbCorpus.Entry> CHUNK_ORDER = (a, b) -> {
        int oa = chunkOrdinal(a.title());
        int ob = chunkOrdinal(b.title());
        if (oa != ob) {
            return Integer.compare(oa, ob);
        }
        return naturalCompare(idOf(a), idOf(b));
    };

    private static String idOf(KbCorpus.Entry e) {
        return e.id() == null ? "" : e.id().value();
    }

    /** 标题末尾「（续 N）」里的 N；没有就是 0（第一块） */
    static int chunkOrdinal(String title) {
        if (title == null) {
            return 0;
        }
        Matcher m = CHUNK_MARK.matcher(title.trim());
        if (!m.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 自然序：数字段按数值比，其余按字符比。
     *
     * <p>为什么不直接 {@code String.compareTo}：块 id 是 {@code xxx-2}、{@code xxx-10}，
     * 字典序会把 {@code -10} 排到 {@code -2} 前面，补出来的资料顺序就是乱的。
     */
    static int naturalCompare(String a, String b) {
        if (a == null) {
            a = "";
        }
        if (b == null) {
            b = "";
        }
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String na = stripLeadingZeros(a.substring(si, i));
                String nb = stripLeadingZeros(b.substring(sj, j));
                if (na.length() != nb.length()) {
                    return Integer.compare(na.length(), nb.length());
                }
                int c = na.compareTo(nb);
                if (c != 0) {
                    return c;
                }
            } else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }
}
