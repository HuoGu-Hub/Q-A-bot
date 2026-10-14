package com.example.qqbot.kb;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 名字的纯文本规则 —— 标题与别名怎么变出"可匹配的名字"，与存储无关，所以住在核心包。
 *
 * <h2>为什么它不在上层</h2>
 * 生产者（文档导入、wiki 导入）要算"这一块该登记哪些名字"，消费者（{@link Glossary}）
 * 要拆名字去匹配 —— 两边都不该把这套规则各写一遍。规则是纯函数，放核心两边都能用。
 *
 * <h2>「（English）」这一半是别名</h2>
 * 实测语料里大量是「深渊之翼斧（Abyssal Wing Axe）」这种写法。标题整体是词条标题，
 * 括号里的英文自动成为别名 —— 短的中文名才是关键词路能命中的东西，而群友不会
 * 把整条带英文的长标题说出来。
 */
public final class TermNames {

    /** 分块标记：{@code （续 2）} —— 它不属于名字，见 {@code KbTextChunker} */
    private static final Pattern CHUNK_MARK = Pattern.compile("\\s*[（(]续\\s*\\d+[）)]$");

    /** 末尾的「（English）」—— 只有落在结尾才拆 */
    private static final Pattern TRAILING_PARENS = Pattern.compile("^(.*?)[（(]([^）)]+)[）)]$");

    private TermNames() {
    }

    /**
     * 标题自动派生出的别名：末尾「（English）」里的那一截；没有就返回空表。
     *
     * <p>括号不在结尾（比如「A（B）C」）就原样保留，不乱动。
     * 切块标记（「（续 2）」）要先去掉 —— 不去掉的话上面那个正则会把「续 2」当成英文名。
     */
    public static List<String> aliasesOf(String title) {
        String t = stripChunkMark(title);
        if (t.isEmpty()) {
            return List.of();
        }
        Matcher m = TRAILING_PARENS.matcher(t);
        if (m.matches()) {
            String zh = m.group(1).trim();
            String en = m.group(2).trim();
            if (!zh.isEmpty() && !en.isEmpty()) {
                return List.of(en);
            }
        }
        return List.of();
    }

    /**
     * 存库的别名 —— 空的时候用标题派生兜底。
     *
     * <p>「别名」是可以人工改的，改过就以人工的为准；没改过则显示标题里那截英文，
     * 于是老语料不需要重导一次就能看到别名。
     */
    public static String effectiveAlias(String title, String storedAlias) {
        if (storedAlias != null && !storedAlias.isBlank()) {
            return storedAlias.trim();
        }
        return String.join("、", aliasesOf(title));
    }

    /**
     * 一条词条**全部**可用来匹配的名字：标题整体、标题末尾括号拆出的两半、别名的各部分。
     *
     * <p>顺序即优先级，调用方一般按顺序取第一个纯英文名当"英文那一侧"。
     */
    public static List<String> namesOf(String title, String storedAlias) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String t = stripChunkMark(title);
        if (!t.isEmpty()) {
            out.add(t);
            Matcher m = TRAILING_PARENS.matcher(t);
            if (m.matches()) {
                addIfPresent(out, m.group(1).trim());
                addIfPresent(out, m.group(2).trim());
            }
        }
        for (String part : splitAliases(storedAlias)) {
            addIfPresent(out, part);
        }
        return new ArrayList<>(out);
    }

    /** 别名文本按「、」「/」「｜」「|」拆开 */
    public static List<String> splitAliases(String aliases) {
        List<String> out = new ArrayList<>();
        if (aliases == null || aliases.isBlank()) {
            return out;
        }
        for (String part : aliases.split("[、/｜|]")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static void addIfPresent(LinkedHashSet<String> out, String s) {
        if (!s.isEmpty()) {
            out.add(s);
        }
    }

    private static String stripChunkMark(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            return "";
        }
        return CHUNK_MARK.matcher(t).replaceFirst("").trim();
    }
}
