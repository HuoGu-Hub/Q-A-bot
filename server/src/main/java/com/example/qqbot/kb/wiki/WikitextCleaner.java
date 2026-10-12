package com.example.qqbot.kb.wiki;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MediaWiki wikitext → 可读纯文本。
 *
 * <h2>总原则：默认保留，只丢明确噪声（2026-10-11 改）</h2>
 * 旧版本是"白名单"：认识的模板按规则取值，<b>不认识的整块丢</b>。
 * 实测代价太大 —— 14 页样本丢掉三分之一的字，而丢掉的正是攻略内容：
 * {@code Infobox/Skill} 的解锁条件、{@code NPC Infobox} 的抗性与区域、
 * {@code ItemsTableHeader} 里的掉落表、{@code Crafter Infobox} 的位置与解锁任务。
 *
 * <p>现在反过来：模板的<b>参数值默认全部保留</b>。只有三类东西会被丢掉：
 * <ul>
 *   <li>整块都是导航或图标的模板（{@link #NOISE_TEMPLATES}）—— 里面没有一句话是这一页的正文；</li>
 *   <li>图标、文件名、尺寸、样式这类参数（{@link #NOISE_KEYS}）；</li>
 *   <li>值本身长得就像文件名或尺寸的。</li>
 * </ul>
 *
 * <h2>四条规则</h2>
 * <ol>
 *   <li><b>递归</b>：外层模板不认识也要先进去扫一遍，内层白名单命中的照样取值。
 *       实测 {@code ItemsLine} 明明在白名单里，却因为嵌在 {@code ItemsTableHeader}
 *       里被整块带走 —— 掉落表就是这么丢的。</li>
 *   <li><b>表格式模板一行一条</b>：{@code {{ItemsLine|item=…|quantity=…}}} →
 *       {@code Fell Cyclops Head × 1 (Materials, Drop)}。</li>
 *   <li><b>页面上下文</b>：{@code {{PAGENAME}}} 要展开成页面标题，否则
 *       {@code {{PAGENAME}} is a skill} 会清成 {@code  is a skill} —— 主语没了。</li>
 *   <li><b>不重复</b>：技能页的 infobox 描述与正文重复；长文本若正文里已经有，只留正文那一份，
 *       否则同一段话会变成两个块，检索时互相稀释。</li>
 * </ol>
 *
 * <h2>表格：一行一条，而不是一格一行</h2>
 * 一条记录横跨几格（区域 / 地点 / 说明），拆成三行之后语义就散了。现在按行输出，
 * 格与格之间用 {@code |} 连起来，让"一行 = 一条记录"这条性质保住。
 *
 * <h2>gallery：取标签，不取文件名</h2>
 * {@code <gallery>} 里每行是 {@code File:X.png|link=Y|[[Z|标签]]}。旧版本只删了标签本身，
 * 于是 124 个块的正文全是 {@code File:…|link=…} 噪声。现在按行取标签
 * （优先 {@code [[Z|标签]]} 的标签，其次最后一个竖线之后那段），拼成一份清单。
 */
public final class WikitextCleaner {

    private WikitextCleaner() {
    }

    /**
     * 整块丢的模板：内容全是导航或图标。
     *
     * <p>这份清单必须短，而且只能放"确定不含本页正文"的。放进来一个错的，
     * 就是又回到了"静默丢内容"的老路上。
     */
    private static final Set<String> NOISE_TEMPLATES = Set.of(
            "bestiary", "npc nav", "lorenav", "skills", "equipmentnav", "item nav",
            "navbox", "toc", "documentation", "stub", "clear", "clr", "quote box");

    /** 丢弃的参数：图标、文件名、尺寸、样式与内部 id —— 这些不是内容 */
    private static final Set<String> NOISE_KEYS = Set.of(
            "image", "images", "icon", "icons", "size", "width", "height", "alt", "format",
            "align", "style", "id", "bgcolor", "color", "colour", "border",
            "padding", "rowspan", "colspan", "font", "float", "px");
    // ⚠️ 这里**不能**放 "class" 与 "caption"：游戏的技能信息框里 Class=Ranger 是内容
    //    （实测当成 CSS 的 class= 丢掉之后，"这个技能属于哪个职业"就没了），
    //    图注同理，它常常是一句人写的说明。

    /** 保留值但**不加 "键: " 前缀**的参数：管道参数与长文本 */
    private static final Set<String> PLUMBING_KEYS = Set.of("content", "1", "2", "text", "body", "value", "data");

    /** 表格式模板：一行一条记录 */
    private static final Set<String> ROW_TEMPLATES = Set.of("itemsline", "items line", "itemline", "dropsline");

    /** 位置参数里"带引号的那个才是给人看的名字" */
    private static final Set<String> MAP_TEMPLATES = Set.of("maplink", "map link");

    /** 需要页面上下文才能展开 */
    private static final Set<String> PAGE_TEMPLATES = Set.of("pagename", "page name");
    private static final Set<String> SUBPAGE_TEMPLATES = Set.of("subpagename", "subpage name");

    private static final Pattern FILENAME =
            Pattern.compile("(?i)^[^\\s]+\\.(png|jpe?g|gif|webp|svg|ogg|mp3|wav|mp4)$");
    private static final Pattern SIZE =
            Pattern.compile("(?i)^\\d+(\\.\\d+)?\\s*(px|em|rem|%|pt)$");
    // ⚠️ 单位是**必须**的：没有它，"Unlock Cost = 3" 与掉落数量 "1" 都会被当成尺寸丢掉。
    private static final Pattern HEX_COLOR = Pattern.compile("(?i)^#[0-9a-f]{3,8}$");
    /** 数字实体：{@code &#160;} 与 {@code &#xA0;} */
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9A-Fa-f]+);");
    /** 像参数名的键：字母开头，不超过 30 字符，没有冒号斜杠（避免把 URL 当键值对） */
    private static final Pattern KEY_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9 _\\-]{0,29}$");

    /** 不带页面上下文 —— 只在拿不到标题时用，{@code {{PAGENAME}}} 会展开成空 */
    public static String clean(String wikitext) {
        return clean(wikitext, "");
    }

    /**
     * @param wikitext 页面原文
     * @param pageTitle 页面标题，用于展开 {@code {{PAGENAME}}}；没有就给空串
     */
    public static String clean(String wikitext, String pageTitle) {
        if (wikitext == null || wikitext.isBlank()) {
            return "";
        }
        // ⚠️ 先去掉 BOM：它既不是空白也不可见，却会让 startsWith("{|") 这类判断失败 ——
        //    实测 Equipment 整页的表格标记就是这么漏进文档的。
        String s = wikitext.replace("\uFEFF", "");
        s = removeComments(s);
        // ⚠️ gallery 要在 stripTags 之前处理：等标签被剥掉之后，就只能看到一堆
        //    File:x.png|link=y 的残骸，分不出哪些行属于同一个图片列表。
        s = stripGalleries(s);
        // ⚠️ 表格要趁"一行还是一条记录"时先处理，理由同旧版：<br> 变换行之后
        //    单元格会折行，行边界就看不出来了。
        s = stripTemplates(s, pageTitle);
        s = stripTables(s);
        s = stripTags(s);
        s = stripLinks(s);
        // 实体解码放在剥标签**之后**：先解码的话 &lt; 会被当成标签的开头
        s = decodeEntities(s);
        s = stripEmphasis(s);
        s = s.replaceAll("(?m)^\\s*[*#:;]+\\s*", "");       // 列表符号
        s = s.replaceAll("(?m)^=+\\s*(.+?)\\s*=+$", "$1");  // 章节标题
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("(?m)^\\s+", "");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    /** {@code <!-- ... -->} 整段丢掉 */
    static String removeComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * {@code <gallery> … </gallery>} → 每行一个标签，拼成清单。
     *
     * <p>丢掉文件名与 {@code link=} 之后的路径，只留给人看的名字。取不到标签的行直接丢 ——
     * 只剩文件名的那种行对回答毫无用处。
     */
    static String stripGalleries(String s) {
        String out = s;
        int from = 0;
        while (true) {
            int open = indexOfIgnoreCase(out, "<gallery", from);
            if (open < 0) {
                return out;
            }
            int close = indexOfIgnoreCase(out, "</gallery>", open);
            if (close < 0) {
                return out;
            }
            String body = out.substring(out.indexOf('>', open) + 1, close);
            List<String> labels = new ArrayList<>();
            for (String line : body.split("\n")) {
                String label = galleryLabel(line);
                if (!label.isEmpty() && !labels.contains(label)) {
                    labels.add(label);
                }
            }
            out = out.substring(0, open) + String.join("\n", labels) + out.substring(close + "</gallery>".length());
            from = open + 1;
        }
    }

    /** gallery 的一行 → 标签；取不到就返回空串 */
    private static String galleryLabel(String line) {
        String t = line.strip();
        if (t.isEmpty() || !t.toLowerCase().startsWith("file:")) {
            return "";
        }
        // [[Z|标签]] 里的标签最可信
        java.util.regex.Matcher m = Pattern.compile("\\[\\[[^\\]|]*\\|([^\\]]+)\\]\\]").matcher(t);
        if (m.find()) {
            return m.group(1).strip();
        }
        // 否则取最后一个竖线之后那段（形如 File:X.png|link=Y|标签）
        int bar = t.lastIndexOf('|');
        String last = bar >= 0 ? t.substring(bar + 1).strip() : "";
        last = last.replaceAll("^\\[\\[|\\]\\]$", "").strip();
        if (last.isEmpty() || last.toLowerCase().startsWith("link=") || FILENAME.matcher(last).matches()) {
            return "";
        }
        return last;
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        return haystack.toLowerCase().indexOf(needle.toLowerCase(), from);
    }

    /** {@code <br>} 变换行，其余标签剥掉但**保留内部文字** */
    static String stripTags(String s) {
        String out = s.replaceAll("(?i)<br\\s*/?>", "\n");
        out = out.replaceAll("(?is)<ref[^>]*>.*?</ref>", "");
        out = out.replaceAll("(?is)<ref[^>]*/>", "");
        out = out.replaceAll("(?is)<[^>]+>", "");
        return out;
    }

    /** 模板处理（无页面上下文） */
    static String stripTemplates(String s) {
        return stripTemplates(s, "");
    }

    /**
     * 模板处理。用**配对扫描**而不是正则 —— 模板会嵌套，正则配不出正确的括号。
     *
     * <p>括号不配对时丢掉剩余内容：半截 {@code {{}} 进了语料比缺内容更糟。
     */
    static String stripTemplates(String s, String pageTitle) {
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
            String value = resolveTemplate(body, pageTitle);
            if (!value.isEmpty()) {
                value = dropDuplicatedLines(value, s.substring(close + 2));
            }
            out.append(value);
            i = close + 2;
        }
        return out.toString();
    }

    /**
     * 模板里的长文本如果正文里已经有，就不再放一遍。
     *
     * <p>实测技能页：{@code Infobox/Skill} 的 {@code description} 与正文的
     * {@code == Description ==} 一段是同一段话。两份都留会让同一段内容变成两个块，
     * 检索时互相稀释。判断只看开头 50 字，去空白与强调标记之后比对。
     */
    /**
     * 逐行去重：只丢掉"正文里已经有"的那些长行。
     *
     * <p>⚠️ 必须是逐行而不是整块判断。技能信息框是"描述 + 职业 + 解锁点数"一整袋，
     * 描述在正文里重复，整块判重会把职业与解锁点数一起丢掉 —— 实测就是这么丢的。
     */
    private static String dropDuplicatedLines(String value, String rest) {
        if (value.indexOf('\n') < 0) {
            return duplicatedInBody(value, rest) ? "" : value;
        }
        List<String> keep = new ArrayList<>();
        for (String line : value.split("\n")) {
            if (!line.isBlank() && !duplicatedInBody(line, rest)) {
                keep.add(line);
            }
        }
        return String.join("\n", keep);
    }

    private static boolean duplicatedInBody(String value, String rest) {
        String flat = value.replace("\n", " ").strip();
        if (flat.length() < 80) {
            return false;
        }
        String head = squeeze(flat.substring(0, 50));
        if (head.length() < 30) {
            return false;
        }
        return squeeze(rest).contains(head);
    }

    /** 去掉空白与强调标记，只留字 */
    private static String squeeze(String s) {
        return s.replace("'''", "").replace("''", "").replaceAll("\\s+", "");
    }

    /** 找到与 open 处 {@code {{} 配对的 {@code }}} 下标 */
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

    /** 模板体（不含外层花括号），无上下文版本 */
    static String resolveTemplate(String body) {
        return resolveTemplate(body, "");
    }

    /** 模板体（不含外层花括号）→ 保留的文本 */
    static String resolveTemplate(String body, String pageTitle) {
        List<String> args = splitTopLevel(body);
        if (args.isEmpty()) {
            return "";
        }
        String name = templateName(args.get(0));
        if (name.isEmpty()) {
            return "";
        }
        if (PAGE_TEMPLATES.contains(name)) {
            return pageTitle == null ? "" : pageTitle;
        }
        if (SUBPAGE_TEMPLATES.contains(name)) {
            String title = pageTitle == null ? "" : pageTitle;
            int slash = title.indexOf('/');
            return slash >= 0 ? title.substring(slash + 1) : title;
        }
        if (NOISE_TEMPLATES.contains(name)) {
            return "";
        }
        if (MAP_TEMPLATES.contains(name)) {
            return mapLabel(args, pageTitle);
        }
        if (ROW_TEMPLATES.contains(name)) {
            return rowLine(args, pageTitle);
        }
        return keepParams(args, pageTitle);
    }

    /**
     * 参数值 → 可读文本。
     *
     * <p>⚠️ 这里**不能**用 {@link #resolveTemplate}：它接收的是"模板体"，
     * 拿到纯文本 {@code Kick} 会当成一个叫 Kick 的模板、按未知模板处理而返回空 ——
     * 实测这让所有带参数的模板一起清成空白，掉的就是技能解锁条件与怪物抗性。
     * 参数值只需要处理**里面的嵌套模板**，其余原样留下。
     */
    private static String resolveValue(String raw, String pageTitle) {
        return stripTags(stripTemplates(raw, pageTitle)).strip();
    }

    /** 模板名：第一段，剥掉嵌套模板、去掉空白、转小写 */
    private static String templateName(String first) {
        return stripTemplates(first, "").strip().toLowerCase();
    }

    /**
     * 默认策略：**参数值全部保留**。
     *
     * <p>短值加上 {@code 键: } 前缀（"Class: Ranger" 比光一个 "Ranger" 可读）；
     * 长值不加前缀，它本身已经是句子。管道参数（{@code content=}）不加前缀。
     */
    private static String keepParams(List<String> args, String pageTitle) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            int eq = keyEnd(arg);
            // key 用小写做集合判断，keyShown 保留原文大小写做展示 —— wiki 里写的是
            // "Unlock Cost"，压成 "Unlock cost" 虽然不影响检索，但读起来不像原文
            String keyShown = eq < 0 ? null : arg.substring(0, eq).strip();
            String key = keyShown == null ? null : keyShown.toLowerCase();
            String raw = eq < 0 ? arg : arg.substring(eq + 1);
            if (key != null && NOISE_KEYS.contains(key)) {
                continue;
            }
            String value = resolveValue(raw, pageTitle);
            if (value.isEmpty() || looksLikeNoise(value)) {
                continue;
            }
            boolean plumbing = key == null || PLUMBING_KEYS.contains(key);
            if (plumbing || value.length() > 60) {
                out.add(value);
            } else {
                out.add(humanize(keyShown) + ": " + value);
            }
        }
        return String.join("\n", out);
    }

    /** 表格式模板 → 一行一条记录 */
    private static String rowLine(List<String> args, String pageTitle) {
        List<String> values = new ArrayList<>();
        String item = null;
        String quantity = null;
        String type = null;
        String source = null;
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            int eq = keyEnd(arg);
            String key = eq < 0 ? null : arg.substring(0, eq).strip().toLowerCase();
            String raw = eq < 0 ? arg : arg.substring(eq + 1);
            if (key != null && NOISE_KEYS.contains(key)) {
                continue;
            }
            String value = resolveValue(raw, pageTitle);
            if (value.isEmpty() || looksLikeNoise(value)) {
                continue;
            }
            if ("item".equals(key)) {
                item = value;
            } else if ("quantity".equals(key) || "amount".equals(key)) {
                quantity = value;
            } else if ("type".equals(key)) {
                type = value;
            } else if ("source".equals(key)) {
                source = value;
            } else {
                values.add(value);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (item != null) {
            sb.append(item);
        }
        if (quantity != null && !quantity.isEmpty()) {
            sb.append(" × ").append(quantity);
        }
        List<String> tail = new ArrayList<>();
        if (type != null && !type.isEmpty()) {
            tail.add(type);
        }
        if (source != null && !source.isEmpty()) {
            tail.add(source);
        }
        if (!tail.isEmpty()) {
            sb.append(" (").append(String.join(", ", tail)).append(')');
        }
        if (sb.length() == 0) {
            sb.append(String.join(" · ", values));
        }
        return sb.toString();
    }

    /** MapLink：位置参数里有 marker id、图标名、尺寸；取"带引号的那个"，没有就取最后一个像名字的 */
    private static String mapLabel(List<String> args, String pageTitle) {
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (keyEnd(arg) >= 0) {
                continue;
            }
            String v = resolveValue(arg, pageTitle);
            if (!v.isEmpty()) {
                positional.add(v);
            }
        }
        for (String p : positional) {
            if (p.startsWith("\"")) {
                // 只去掉双引号：撇号必须保留（Elder's / Fenrig's Axe 这类是真名字）
                return p.replace("\"", "").strip();
            }
        }
        for (int i = positional.size() - 1; i >= 0; i--) {
            String p = positional.get(i);
            if (!p.matches("^[A-Za-z]+\\d+$") && !looksLikeNoise(p)) {
                return p;
            }
        }
        return positional.isEmpty() ? "" : positional.get(positional.size() - 1);
    }

    /** 键值对里 {@code =} 的下标；不像键值对就返回 -1 */
    private static int keyEnd(String arg) {
        int eq = arg.indexOf('=');
        if (eq <= 0) {
            return -1;
        }
        String key = arg.substring(0, eq).strip();
        return KEY_NAME.matcher(key).matches() ? eq : -1;
    }

    /** 键名转成人看得懂的样子：{@code unlock cost} → {@code Unlock cost} */
    private static String humanize(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    /** 值本身是文件名、尺寸或颜色 —— 不是内容 */
    private static boolean looksLikeNoise(String value) {
        String v = value.strip();
        return FILENAME.matcher(v).matches()
                || SIZE.matcher(v).matches()
                || HEX_COLOR.matcher(v).matches();
    }

    /** 按顶层 {@code |} 切分，忽略 {@code {{}} / [[]]} 内部的竖线 */
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
     * 表格：{@code {|} 头、{@code |-} 行分隔都丢掉，{@code |} 单元格取内容，
     * <b>但一行仍然是一条记录</b> —— 格与格之间用 {@code |} 连起来。
     *
     * <p>为什么不一格一行：任务页的"区域 / 地点 / 说明"三格讲的是一件事，
     * 拆成三行之后检索会把它们当成三条互不相关的信息。
     */
    static String stripTables(String s) {
        StringBuilder out = new StringBuilder();
        boolean inTable = false;
        List<String> row = new ArrayList<>();
        for (String line : s.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("{|")) {
                flushRow(out, row);
                inTable = true;
                continue;
            }
            if (t.startsWith("|}")) {
                flushRow(out, row);
                inTable = false;
                continue;
            }
            if (!inTable) {
                out.append(line).append('\n');
                continue;
            }
            if (t.startsWith("|-")) {
                flushRow(out, row);
                continue;
            }
            if (t.startsWith("!")) {
                flushRow(out, row);
                for (String cell : t.substring(1).split("!!")) {
                    row.add(cellValue(cell));
                }
                continue;
            }
            if (t.startsWith("|")) {
                for (String cell : t.substring(1).split("\\|\\|")) {
                    row.add(cellValue(cell));
                }
                continue;
            }
            // 表格里其余的行：单元格折出来的续行，追加到最后一格，别丢
            if (!t.isEmpty()) {
                if (row.isEmpty()) {
                    row.add(t);
                } else {
                    row.set(row.size() - 1, row.get(row.size() - 1) + " " + t);
                }
            }
        }
        flushRow(out, row);
        return out.toString();
    }

    /** 一行结束：把非空的格用 {@code |} 连起来写成一行 */
    private static void flushRow(StringBuilder out, List<String> row) {
        if (row.isEmpty()) {
            return;
        }
        List<String> cells = new ArrayList<>();
        for (String c : row) {
            String v = c == null ? "" : c.strip();
            if (!v.isEmpty()) {
                cells.add(v);
            }
        }
        row.clear();
        if (!cells.isEmpty()) {
            out.append(String.join(" | ", cells)).append('\n');
        }
    }

    /** 一格：去掉行内样式（{@code style="…" |} 这种前缀） */
    private static String cellValue(String raw) {
        String c = raw;
        int style = c.indexOf("|");
        if (style >= 0 && c.substring(0, style).contains("=")) {
            c = c.substring(style + 1);
        }
        // ⚠️ 单元格里的 <br> 要变成**空格**而不是换行。这里还没到 stripTags，
        //    一旦留到后面变换行，"一行 = 一条记录"就被拆开了：实测 Equipment
        //    整页因此变成每行一个标签，还各带一个孤零零的 " | "。
        c = c.replaceAll("(?i)<br\\s*/?>", " ");
        return c.replace("||", " ").strip();
    }

    /** {@code [[a|b]]} → b；{@code [[a]]} → a；文件/分类/interwiki 链接整条丢掉 */
    static String stripLinks(String s) {
        String out = s.replaceAll("(?i)\\[\\[[^\\]]*?:[^\\]]*\\]\\]", "");
        out = out.replaceAll("\\[\\[([^\\]|]*)\\|([^\\]]*)\\]\\]", "$2");
        out = out.replaceAll("\\[\\[([^\\]]*)\\]\\]", "$1");
        return out;
    }

    /**
     * HTML 实体解码。
     *
     * <p>{@code &nbsp;} 这类不处理就会原样进语料 —— 实测 {@code Survivors} 单页 118 处，
     * 检索时它既不是空格也不是内容，纯粹是噪声。数字实体与十六进制实体一并处理。
     */
    static String decodeEntities(String s) {
        String out = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&mdash;", "—").replace("&ndash;", "–");
        java.util.regex.Matcher m = NUMERIC_ENTITY.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            String rep = code == 160 ? " "
                    : code > 0 && code <= 0x10FFFF ? new String(Character.toChars(code)) : "";
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** {@code '''粗'''} / {@code ''斜''} 去掉标记，保留文字 */
    static String stripEmphasis(String s) {
        return s.replace("'''", "").replace("''", "");
    }
}
