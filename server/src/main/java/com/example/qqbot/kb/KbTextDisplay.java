package com.example.qqbot.kb;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Wiki 清洗后的文本再「清洗一遍」—— 但只影响**显示**，不动索引。

 * <h2>为什么要这一层</h2>
 * chunks.jsonl 里的文本还带着 Wiki 模板的机器痕迹，比如：
 * <pre>
 *   Acid Bite（Staff Charges）：Item_Infobox: title: Acid Bite;
 *   description: Magical ammunition...; Type: Spell; Rarity: Rare; Stack Size: 500
 * </pre>
 * 这些给玩家看很难受。

 * <h2>为什么不在构建时清洗</h2>
 * 改 chunks.jsonl 的文本 = 向量失效 = **必须全量重建索引**（花钱、花时间）。
 * 而这一层只改 API 返回值 —— **零成本，不用重建**。
 *
 * <p>检索质量不受影响：机器认得这些字段，人不需要看见。
 */
public final class KbTextDisplay {

    private KbTextDisplay() {
    }

    /** 开头的「标题（分类）：」前缀 */
    private static final Pattern TITLE_PREFIX = Pattern.compile(
            "^[^\\n（(：:]{1,120}(?:[（(][^）)\\n]{0,240}[）)])?[：:]\\s*");

    /** description 的值（到下一个「键:」、换行或结尾为止） */
    private static final Pattern DESCRIPTION = Pattern.compile(
            "(?i)description\\s*:\\s*(.+?)(?=;\\s*[A-Za-z][A-Za-z _]{0,20}\\s*:|\\n|$)");

    /** 纯机器键值对行：weapon: xxx; weaponType: xxx; level: 43; */
    private static final Pattern KEY_VALUE_LINE =
            Pattern.compile("^[A-Za-z][A-Za-z _]{0,24}\\s*:\\s*.*;.*$");

    /** 语言链接行：fr:xxx / de:xxx */
    private static final Pattern LANG_LINK =
            Pattern.compile("(?m)^(fr|de|es|ru|zh|ja|ko|pt|it|pl|nl|tr)\\s*:.*$");

    /** markdown 标题 */
    private static final Pattern MD_HEADING = Pattern.compile("(?m)^#{1,6}\\s*");

    /**
     * 列表用的摘要。
     *
     * <p>优先返回 description 的内容 —— 那是 Wiki 里唯一一句「人话」。
     * 没有就找第一个不像键值对转储的段落。
     */
    public static String excerpt(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String t = preprocess(text);

        Matcher m = DESCRIPTION.matcher(t);
        if (m.find()) {
            String desc = m.group(1).trim();
            if (desc.length() >= 12) {
                return truncate(desc, maxLen);
            }
        }

        for (String para : t.split("\\n\\s*\\n")) {
            String q = para.trim();
            if (q.length() < 24) {
                continue;
            }
            String firstLine = q.split("\\n")[0];
            if (KEY_VALUE_LINE.matcher(firstLine).matches() && q.indexOf(59) > 0) {
                continue;
            }
            return truncate(q.replace((char) 10, (char) 32), maxLen);
        }

        return truncate(t.replace((char) 10, (char) 32), maxLen);
    }

    /**
     * 详情页用的全文。
     *
     * <p>保留结构和内容，只去掉机器痕迹。不像摘要那样只抽 description ——
     * 详情页该给完整信息。
     */
    public static String full(String text) {
        if (text == null) {
            return "";
        }
        String t = preprocess(text);
        t = t.replaceAll("(?i)\\bdescription\\s*:\\s*", "");
        t = t.replaceAll("(?i)\\b(weaponType|attackSpeed|parryPower|Stack Size|Unlock Cost|"
                + "Crafted Quantity|Element Type|Mana Cost|Cast Time|workshop|craftedAt)\\s*:\\s*", "");
        return t.trim();
    }

    /** 公共预处理：去前缀、语言链接、markdown 标记、多余空行 */
    private static String preprocess(String text) {
        String t = text == null ? "" : text;

        t = LANG_LINK.matcher(t).replaceAll("");
        t = MD_HEADING.matcher(t).replaceAll("");
        t = TITLE_PREFIX.matcher(t).replaceFirst("");

        // 去掉 Infobox 的包裹名（Item Infobox: / Item_Infobox: / Infobox/Skill:）
        t = t.replaceAll("(?i)\\bItem[_ ]?Infobox\\s*:\\s*", "");
        t = t.replaceAll("(?i)\\bInfobox\\s*/\\s*[A-Za-z]+\\s*:\\s*", "");
        // 去掉纯标识字段
        t = t.replaceAll("(?i)\\btitle\\s*:\\s*", "");
        t = t.replaceAll("(?i)\\bID\\s*:\\s*[^;\\n]*;?\\s*", "");

        t = t.replaceAll("\\n{3,}", "\\n\\n");
        return t.trim();
    }

    private static String truncate(String s, int maxLen) {
        String t = s.trim();
        if (t.length() <= maxLen) {
            return t;
        }
        int cut = t.lastIndexOf(32, maxLen);
        if (cut < maxLen / 2) {
            cut = maxLen;
        }
        return t.substring(0, cut).trim() + "…";
    }
}
