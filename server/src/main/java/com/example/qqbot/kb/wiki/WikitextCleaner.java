package com.example.qqbot.kb.wiki;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MediaWiki wikitext → 可读纯文本。
 *
 * <h2>为什么必须清洗，不能直接塞进知识库</h2>
 * 实测一篇任务页有 10 个模板、32 处加粗、1 张表格；机制页有 58 个链接、6 张表格。
 * 原样入库会把这些标记一起喂给向量模型与重排模型 ——
 * 它们会把 `{{` 当成内容的一部分，检索质量直接塌掉。
 *
 * <h2>清洗原则：**宁可少留，不要留错**</h2>
 * 模板一律按白名单处理：认识的模板取它的**有意义的那个参数**，
 * 不认识的整体丢弃。理由和中文地名一样 —— 猜错一个模板的语义，
 * 会往语料里灌一批噪声，而噪声比缺失更难发现。
 *
 * <p>白名单模板分两种取法：<b>按位置取</b>（下面三个）和<b>按参数名取</b>
 * （{@code {{NPC Infobox | description = ...}}} 里那句描述，见 KEYED_TEMPLATES）。
 * 三个按位置取的是实测确认有价值的：
 * <ul>
 *   <li>`{{quest+icon|In Need Of A Tanning Station}}` → 前置任务名</li>
 *   <li>`{{item+iconright|Beehive Smoker}}` → 奖励物品名</li>
 *   <li>`{{MapLink | icon= Camp | FarAwayFrayTavern | "Far Away Fray" Tavern | size= 25px }}`
 *       → `"Far Away Fray" Tavern`（**第 3 个参数就是地图 marker id**，
 *       将来可以用它把任务和地图坐标接起来）</li>
 * </ul>
 *
 * <h2>表格：宁可多留一行，不能吞掉内容（2026-10-05 修）</h2>
 * 表格里**不以竖线 / 感叹号 / 表格行分隔符开头的行不能丢** —— 单元格里的
 * {@code <br>}（或真实换行）会把内容折成这样的行。少了这条兜底，
 * {@code Equipment} 那种"整页都是图片链接表格"的页面会清洗成**空**
 * （实测 1542 字 → 0 字），而且全程不报错。
 * 配套改动见 {@link #clean(String)}：{@code stripTables} 必须排在 {@code stripTags} **之前**
 * （先按行看懂表格结构，再让 {@code <br>} 变成换行）。
 */
public final class WikitextCleaner {

    private WikitextCleaner() {
    }

    /** 取「第一个位置参数」的模板：`{{X|值}}` → `值` */
    private static final Set<String> FIRST_ARG_TEMPLATES = Set.of(
            "quest+icon", "item+iconright", "item+icon", "item+iconleft",
            "crafting equipment", "itemsline", "footnote", "bestiary");

    /** 取「看起来像名字的那个参数」的模板（参数里有 marker id、图标名、尺寸等噪声） */
    private static final Set<String> LABEL_TEMPLATES = Set.of("maplink");

    /**
     * 按**参数名**取值的模板：{@code {{NPC Infobox | description = ...}}} → 那句描述。
     *
     * <p>为什么要收它：9 个 {@code Baby *} 页 + {@code Bees} 的正文**只有**这一个模板，
     * 而 {@code description} 是真人写的描述句 —— 不收就整页清洗成空
     * （实测原文 400~540 字 → 0 字，块一个都不生成）。
     *
     * <p><b>只收 {@code description}</b>：{@code Behavior} / {@code Tameable} 是单词，
     * {@code images} 是文件名，{@code Region} 与地点语料重复 —— 收进来只会灌噪声。
     * 仍然是那条原则：宁可少留，不要留错。
     */
    private static final Map<String, Set<String>> KEYED_TEMPLATES = Map.of(
            "npc infobox", Set.of("description"));

    public static String clean(String wikitext) {
        if (wikitext == null || wikitext.isBlank()) {
            return "";
        }
        String s = wikitext;
        s = removeComments(s);
        // ⚠️ 顺序是**语义**：表格要趁"一行还是一条单元格"时先处理。
        //    把 stripTags 放在前面会让 <br> 先变成换行，单元格折出来的半截行
        //    会被 stripTables 当成垃圾丢掉（Equipment 那种页面整页变空的原因）。
        s = stripTemplates(s);
        s = stripTables(s);
        s = stripTags(s);
        s = stripLinks(s);
        s = stripEmphasis(s);
        s = s.replaceAll("(?m)^\\s*[*#:;]+\\s*", "");      // 列表符号
        s = s.replaceAll("(?m)^=+\\s*(.+?)\\s*=+$", "$1"); // 章节标题
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("(?m)^\\s+", "");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    /** `<!-- ... -->` 整段丢掉 */
    static String removeComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    /** `<br>` 变换行，其余标签剥掉但**保留内部文字**（如 `<span>x</span>` → x） */
    static String stripTags(String s) {
        String out = s.replaceAll("(?i)<br\\s*/?>", "\n");
        out = out.replaceAll("(?is)<ref[^>]*>.*?</ref>", "");
        out = out.replaceAll("(?is)<ref[^>]*/>", "");
        out = out.replaceAll("(?is)<[^>]+>", "");
        return out;
    }

    /**
     * 模板处理。用**配对扫描**而不是正则 —— 模板会嵌套
     * （实测 `{{Quest|{{SUBPAGENAME}}}}`），正则配不出正确的括号。
     */
    static String stripTemplates(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf("{{", i);
            if (open < 0) {
                out.append(s, i, s.length());
                break;
            }
            out.append(s, i, open);
            int close = matchClose(s, open);
            if (close < 0) {
                // 括号不配对：宁可整段丢掉，也不要留下半截 {{ 
                break;
            }
            String body = s.substring(open + 2, close);
            out.append(resolveTemplate(body));
            i = close + 2;
        }
        return out.toString();
    }

    /** 找到与 `open` 处 `{{` 配对的 `}}` 的下标（`{{` 与 `}}` 都要计嵌套） */
    static int matchClose(String s, int open) {
        int depth = 0;
        int i = open;
        while (i < s.length() - 1) {
            if (s.startsWith("{{", i)) {
                depth++;
                i += 2;
                continue;
            }
            if (s.startsWith("}}", i)) {
                depth--;
                if (depth == 0) {
                    return i;
                }
                i += 2;
                continue;
            }
            i++;
        }
        return -1;
    }

    /** 按白名单解析一个模板体（不含外层花括号） */
    static String resolveTemplate(String body) {
        List<String> args = splitTopLevel(body);
        if (args.isEmpty()) {
            return "";
        }
        // 模板名本身可能是个模板（{{Quest|{{SUBPAGENAME}}}} 的第一段就是 "Quest"）
        String name = stripTemplates(args.get(0)).strip().toLowerCase();
        // 取位置参数（不含 "=" 的）
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            if (!args.get(i).contains("=")) {
                positional.add(stripTemplates(args.get(i)).strip());
            }
        }
        Set<String> keys = KEYED_TEMPLATES.get(name);
        if (keys != null) {
            return namedValues(args, keys);
        }
        if (FIRST_ARG_TEMPLATES.contains(name)) {
            return positional.isEmpty() ? "" : positional.get(0);
        }
        if (LABEL_TEMPLATES.contains(name)) {
            // MapLink：位置参数形如 [图标名, markerId, "显示名", ...]
            // 取"带引号的那个"，没有就取最后一个位置参数
            for (String p : positional) {
                if (p.startsWith("\"")) {
                    // 只去掉**双引号**：它在这里是展示用的装饰。
                    // 撇号必须保留 —— 实测语料里有 Elder's / Fenrig's Axe 这类真名字，
                    // 一起删掉会把名字改坏。
                    return p.replace("\"", "").strip();
                }
            }
            return positional.isEmpty() ? "" : positional.get(positional.size() - 1);
        }
        return "";
    }

    /**
     * 取模板里**指定参数名**的值，按出现顺序用换行拼起来。
     *
     * <p>缺参数、或值本身是空，都返回空串 —— 所以
     * {@code {{NPC Infobox|name=Cyclops|health=100}}} 这种没有 description 的模板
     * 仍然是"整块丢掉"（宁可少留，不要留错）。
     */
    private static String namedValues(List<String> args, Set<String> keys) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            int eq = a.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = a.substring(0, eq).strip().toLowerCase();
            if (!keys.contains(key)) {
                continue;
            }
            String value = stripTemplates(a.substring(eq + 1)).strip();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return String.join("\n", out);
    }

    /** 按顶层 `|` 切分，忽略 `{{}}` / `[[]]` 内部的竖线 */
    static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depthT = 0;
        int depthL = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{' && i + 1 < body.length() && body.charAt(i + 1) == '{') {
                depthT++;
                cur.append("{{");
                i++;
                continue;
            }
            if (c == '}' && i + 1 < body.length() && body.charAt(i + 1) == '}') {
                depthT = Math.max(0, depthT - 1);
                cur.append("}}");
                i++;
                continue;
            }
            if (c == '[' && i + 1 < body.length() && body.charAt(i + 1) == '[') {
                depthL++;
                cur.append("[[");
                i++;
                continue;
            }
            if (c == ']' && i + 1 < body.length() && body.charAt(i + 1) == ']') {
                depthL = Math.max(0, depthL - 1);
                cur.append("]]");
                i++;
                continue;
            }
            if (c == '|' && depthT == 0 && depthL == 0) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /**
     * 表格：`{|` 头、`|-` 行分隔、`!` 表头行都丢掉，`|` 单元格取内容。
     * 保留内容而不是整表丢弃 —— 任务页的"目标/地点/描述"全在表里。
     */
    static String stripTables(String s) {
        StringBuilder out = new StringBuilder();
        boolean inTable = false;
        for (String line : s.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("{|")) {
                inTable = true;
                continue;
            }
            if (t.startsWith("|}")) {
                inTable = false;
                continue;
            }
            if (!inTable) {
                out.append(line).append('\n');
                continue;
            }
            if (t.startsWith("|-") || t.startsWith("!")) {
                continue;
            }
            if (t.startsWith("|")) {
                String cell = t.substring(1);
                int style = cell.indexOf("|");
                if (style >= 0 && cell.substring(0, style).contains("=")) {
                    cell = cell.substring(style + 1);
                }
                out.append(cell.replace("||", " ").strip()).append('\n');
                continue;
            }
            // ⚠️ 兜底：表格里其余的行**原样留下**，别丢。
            //    单元格里的 <br> / 真实换行会折出这种行；丢了它们，
            //    "整页都是图片链接表格"的页面会清洗成空（实测 Equipment 1542 字 → 0 字）。
            out.append(t).append('\n');
        }
        return out.toString();
    }

    /** `[[a|b]]` → b；`[[a]]` → a；文件/分类链接整条丢掉 */
    static String stripLinks(String s) {
        String out = s.replaceAll("(?i)\\[\\[[^\\]]*?:[^\\]]*\\]\\]", "");      // [[File:..]] / [[Category:..]]
        out = out.replaceAll("\\[\\[([^\\]|]*)\\|([^\\]]*)\\]\\]", "$2");
        out = out.replaceAll("\\[\\[([^\\]]*)\\]\\]", "$1");
        return out;
    }

    /** `'''粗'''` / `''斜''` 去掉标记，保留文字 */
    static String stripEmphasis(String s) {
        return s.replace("'''", "").replace("''", "");
    }
}
