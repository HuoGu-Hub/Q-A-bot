package com.example.qqbot.kb;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 名字的纯文本规则 —— 标题怎么变名字，与存储无关，所以住在核心包。
 *
 * <h2>为什么它不在 {@code kb.term}</h2>
 * 生产者（{@code kb.block.KbDocImporter} 导入时要知道"这一块该登记什么名字"）
 * 不能依赖名字层 —— 那正是要消掉的那条边。规则是纯函数，放核心两边都能用。
 */
public final class TermNames {

    /** 分块标记：{@code （续 2）} —— 它不属于名字，见 {@code KbTextChunker} */
    private static final Pattern CHUNK_MARK = Pattern.compile("\\s*[（(]续\\s*\\d+[）)]$");

    /** 末尾的「（English）」—— 只有落在结尾才拆 */
    private static final Pattern TRAILING_PARENS = Pattern.compile("^(.*?)[（(]([^）)]+)[）)]$");

    private TermNames() {
    }

    /**
     * 块标题 → 词条的中文名。
     *
     * <p>实测语料里大量是「深渊之翼斧（Abyssal Wing Axe）」这种写法。把它拆成
     * 「深渊之翼斧、Abyssal Wing Axe」：
     * <b>短的中文名才是关键词路能命中的东西</b> —— 词表判定"命中"靠的是
     * "这个名字出现在问句里"，而群友不会把整条带英文的长标题说出来。
     *
     * <p>括号不在结尾（比如「A（B）C」）就原样保留，不乱动。
     *
     * <p>⚠️ 切块标记要先去掉：超长页面会被切成多块，后续块标题是
     * 「水（Water）（续 2）」。不去掉的话上面那个"末尾括号"正则会把
     * <b>「续 2」当成英文名</b>，长出一批名为「水（Water）、续 2」的垃圾词条。
     */
    public static String termName(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            return "";
        }
        t = CHUNK_MARK.matcher(t).replaceFirst("").trim();
        if (t.isEmpty()) {
            return "";
        }
        Matcher m = TRAILING_PARENS.matcher(t);
        if (m.matches()) {
            String zh = m.group(1).trim();
            String en = m.group(2).trim();
            if (!zh.isEmpty() && !en.isEmpty()) {
                return zh + "、" + en;
            }
        }
        return t;
    }
}
