package com.example.qqbot.kb.doc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档分块标记的解析器。
 *
 * <h2>格式约定</h2>
 * <pre>
 * n*= 标题 n*= [&lt;!-- id: xxx --&gt;]
 *
 * === 熔炉 === &lt;!-- id: kiln --&gt;
 *   熔炉用来烧制…
 *
 * == 灵石 ==
 *   灵石……
 * </pre>
 *
 * <ul>
 *   <li><b>等号个数宽容</b>：{@code =} 到 {@code ========} 都认，但**左右必须一样多**
 *       （{@code === 标题 ==} 不是分隔符，当普通正文）。</li>
 *   <li><b>id 可选</b>：只能由英文字母、数字、{@code -} 组成。
 *       有 id 时以 id 为准；没有则**退回用标题当 key**。</li>
 *   <li><b>转义</b>：行首的 {@code \} 可让一行分隔符变回普通正文；
 *       标题里想出现 {@code =} 就用 {@code \=}。</li>
 *   <li><b>标题</b>自动去掉前后空格。</li>
 * </ul>
 *
 * <h2>校验</h2>
 * <ul>
 *   <li>{@code key}（有 id 用 id，否则用标题）在同一份文档内**必须唯一** → 否则 error；</li>
 *   <li>标题重复 → **warning**（不是 error，原因见 {@link #parse} 的注释）。</li>
 * </ul>
 *
 * <p>本类**只做解析**，不碰知识库、不碰数据库 —— 所以它能独立单测，
 * 也能在引入导入功能之前先拿真实语料试跑。
 */
public final class ChunkMarkup {

    private ChunkMarkup() {
    }

    /** id 允许的字符：英文字母、数字、连字符 */
    public static final String ID_REGEX = "[A-Za-z0-9-]+";

    private static final Pattern ID_COMMENT =
            Pattern.compile("^<!--[ \\t]*id:[ \\t]*(" + ID_REGEX + ")[ \\t]*-->$");

    /** 长得像 id 注释、但里面有非法字符 —— 用来给出"id 含非法字符"这种能看懂的报错 */
    private static final Pattern ID_COMMENT_LOOSE =
            Pattern.compile("^<!--[ \\t]*id:[ \\t]*(.*?)[ \\t]*-->$");

    /**
     * 一个解析出来的块。
     *
     * @param key   身份：有 id 用 id，没有就用标题
     * @param id    显式 id；没有则为 {@code null}
     * @param title 标题（已 trim）
     * @param body  正文（已去掉首尾空行）
     * @param line  分隔符所在行号（1 起，报错时给人看）
     */
    public record Block(String key, String id, String title, String body, int line)
            implements ImportableBlock {

        /** 没写 id，身份是"标题顶替"的 —— 提醒作者补上，改标题时才不会丢订正 */
        public boolean idMissing() {
            return id == null || id.isBlank();
        }
    }

    /**
     * 解析结果。
     *
     * @param blocks   分出来的块
     * @param preamble 第一个分隔符**之前**的内容（原样保留，绝不静默丢弃）
     * @param errors   致命问题，调用方不应继续导入
     * @param warnings 可疑但不致命
     */
    public record Parsed(List<Block> blocks, Map<String, String> header, String preamble,
                         List<String> errors, List<String> warnings) {

        public boolean ok() {
            return errors.isEmpty();
        }

        /** 文档头里的某个字段；没有就返回空串 */
        public String meta(String key) {
            return header.getOrDefault(key, "");
        }
    }

    /**
     * 文档头：{@code <!-- doc ... -->} 里的一组 {@code key: value}。
     *
     * <p>为什么要它：块里只装正文，而 {@code url}（回复要给出来源）和
     * {@code tags}（板块 / 公开站分类）**不属于正文**，必须有个地方放。
     * 缺了这两样，检索出来的资料说不出出处、也归不了类。
     *
     * @param fields   键值对（顺序保留；未知键原样留着，不报错）
     * @param nextLine 正文从第几行开始（0 起）
     * @param error    致命问题（比如 {@code -->} 没闭合）
     */
    private record Header(Map<String, String> fields, int nextLine, String error) {
    }

    /** 文档头首行。只有**恰好**是 {@code <!-- doc} 才算，避免把正文里的注释吃成元数据 */
    private static final Pattern HEADER_OPEN = Pattern.compile("^<!--\\s*doc\\s*$", Pattern.CASE_INSENSITIVE);

    /** 分隔符行的解析结果：只表达"是不是分隔符、标题和 id 各是什么" */
    private record Delim(String title, String id, String badId) {
    }

    /**
     * 判断一行是不是分隔符；不是就返回 {@code null}。
     *
     * <p><b>为什么手写扫描而不用一个正则</b>：{@code ^(=+)(.*?)\1$} 这种写法会**回溯** ——
     * 对 {@code === 乙 ==}（左右不等，明显是打字打多了），正则会把左边退成 {@code ==}、
     * 把多出来的 {@code =} 塞进标题，于是**蒙混过关成一个块**。
     * 逐字符数出左右两段等号再比较，才真的做到"左右必须一样多"。
     */
    private static Delim tryDelimiter(String line) {
        if (line == null) {
            return null;
        }
        String s = line.strip();
        if (s.isEmpty() || s.charAt(0) != '=') {
            return null;
        }

        // 行尾可选的 id 注释，先摘掉再数等号
        String comment = null;
        int c = s.lastIndexOf("<!--");
        if (c >= 0 && s.endsWith("-->")) {
            comment = s.substring(c).strip();
            s = s.substring(0, c).strip();
            if (s.isEmpty()) {
                return null;
            }
        }

        // 左边：从 0 开始数，天然是"最长的一串"
        int n1 = 0;
        while (n1 < s.length() && s.charAt(n1) == '=') {
            n1++;
        }
        // 右边：从尾部往左数，遇到被转义的 = 就停
        int end = s.length();
        int n2 = 0;
        while (end - n2 - 1 >= 0
                && s.charAt(end - n2 - 1) == '='
                && !isEscaped(s, end - n2 - 1)) {
            n2++;
        }
        if (n2 == 0 || n1 != n2 || end - n2 < n1) {
            return null;
        }

        String title = unescapeTitle(s.substring(n1, end - n2)).strip();
        String id = null;
        String badId = null;
        if (comment != null) {
            Matcher strict = ID_COMMENT.matcher(comment);
            if (strict.matches()) {
                id = strict.group(1);
            } else {
                Matcher loose = ID_COMMENT_LOOSE.matcher(comment);
                badId = loose.matches() ? loose.group(1) : comment;
            }
        }
        return new Delim(title, id, badId);
    }

    /** 位置 idx 上的字符是否被前面奇数个反斜杠转义 */
    private static boolean isEscaped(String s, int idx) {
        int bs = 0;
        for (int i = idx - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            bs++;
        }
        return bs % 2 == 1;
    }

    /**
     * 解析一份文档。
     *
     * <p><b>为什么"标题重复"只算 warning 而不是 error</b>：真实语料里，
     * 一个 Wiki 页面会被切成多块、标题全都相同（实测：3,630 个页面里有 174 个是这样，
     * 共 675 块；{@code Forging The Path Update} 一个页面就 33 块）。
     * 若把标题重复定为致命错误，这 174 个页面会**整份导入失败**。
     * 所以规则收紧在 {@code key} 上（有 id 用 id，没 id 才轮到标题），
     * 标题重复只提示、由调用方决定要不要处理。
     */
    public static Parsed parse(String document) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        StringBuilder preamble = new StringBuilder();
        Map<String, Integer> keyLines = new LinkedHashMap<>();
        Map<String, Integer> titleLines = new LinkedHashMap<>();

        if (document == null || document.isBlank()) {
            errors.add("文档是空的");
            return new Parsed(List.of(), Map.of(), "", errors, warnings);
        }

        String[] lines = document.split("\\n", -1);

        // 文档头（可选）：只认最前面那一段
        Header header = parseHeader(lines);
        if (header.error() != null) {
            errors.add(header.error());
        }
        if (header.fields().containsKey("_raw")) {
            // 最典型的成因：文档头忘了写 -->，于是它一路吃到正文里第一个 -->
            warnings.add("文档头里有不是「key: value」格式的内容：「"
                    + header.fields().get("_raw") + "」。"
                    + "最常见的原因是文档头忘了写 -->，导致它把后面的正文也吃进去了");
        }
        int startLine = header.nextLine();

        String curId = null;
        String curTitle = null;
        int curLine = 0;
        boolean inBlock = false;
        StringBuilder body = new StringBuilder();
        int delimiters = 0;

        for (int i = startLine; i < lines.length; i++) {
            String raw = stripCr(lines[i]);
            int lineNo = i + 1;

            Delim d = tryDelimiter(raw);
            // 转义：只有当"去掉行首那一个反斜杠之后确实是分隔符"才按字面量处理，
            // 免得把正文里正常的反斜杠（如 Windows 路径）也吃掉
            String literal = stripOneBackslash(raw);
            boolean escapedDelim = d == null && tryDelimiter(literal) != null;

            if (d != null) {
                if (inBlock) {
                    blocks.add(finish(curKey(curId, curTitle), curId, curTitle, body.toString(), curLine));
                }
                delimiters++;

                String title = d.title();
                String id = d.id();
                if (d.badId() != null) {
                    errors.add("第 " + lineNo + " 行：id 只能由英文字母、数字和 - 组成，实际是「"
                            + d.badId() + "」");
                }
                if (title.isEmpty() && id == null) {
                    errors.add("第 " + lineNo + " 行：标题和 id 不能都为空");
                }

                String key = curKey(id, title);
                if (key != null && !key.isBlank()) {
                    Integer prev = keyLines.putIfAbsent(key, lineNo);
                    if (prev != null) {
                        errors.add("第 " + lineNo + " 行：key「" + key + "」重复了（第 " + prev + " 行已用过）"
                                + (id == null ? "；这个块没写 id，是用标题当的身份" : ""));
                    }
                }
                if (!title.isEmpty()) {
                    Integer prevTitle = titleLines.putIfAbsent(title, lineNo);
                    if (prevTitle != null) {
                        warnings.add("第 " + lineNo + " 行：标题「" + title + "」在同一份文档里重复"
                                + "（第 " + prevTitle + " 行已出现）。若这是同一页面的多个块，忽略即可；"
                                + "若想各自独立，请给它们不同的 id。");
                    }
                }

                curId = id;
                curTitle = title;
                curLine = lineNo;
                inBlock = true;
                body.setLength(0);
                continue;
            }

            String text = escapedDelim ? literal : raw;
            if (inBlock) {
                body.append(text).append('\n');
            } else {
                preamble.append(text).append('\n');
            }
        }

        if (inBlock) {
            blocks.add(finish(curKey(curId, curTitle), curId, curTitle, body.toString(), curLine));
        }

        if (delimiters == 0) {
            errors.add("整份文档里没有找到任何分块分隔符（形如 === 标题 === <!-- id: xxx -->）");
        }
        String pre = preamble.toString().strip();
        if (!pre.isEmpty()) {
            warnings.add("第一个分隔符之前有 " + pre.split("\\n").length
                    + " 行内容，它们不属于任何块（已单独保留，不会被当成资料）");
        }
        for (Block b : blocks) {
            if (b.body().isBlank()) {
                warnings.add("第 " + b.line() + " 行：「" + b.title() + "」这一块正文是空的");
            }
        }
        return new Parsed(List.copyOf(blocks), Map.copyOf(header.fields()), pre,
                List.copyOf(errors), List.copyOf(warnings));
    }

    /**
     * 解析文档头。没有就返回空表、从第 0 行开始。
     *
     * <pre>
     * &lt;!-- doc
     * name: Flame Altar
     * source: wiki
     * url: https://enshrouded.wiki.gg/wiki/Flame_Altar
     * tags: Essentials, Stations
     * --&gt;
     * </pre>
     *
     * <p>宽容原则：**只有首行恰好是 {@code <!-- doc}** 才当文档头，其余一律当正文 ——
     * 免得把正文里偶然出现的 HTML 注释吃成元数据。
     */
    private static Header parseHeader(String[] lines) {
        int i = 0;
        while (i < lines.length && stripCr(lines[i]).isBlank()) {
            i++;
        }
        if (i >= lines.length || !HEADER_OPEN.matcher(stripCr(lines[i]).strip()).matches()) {
            return new Header(Map.of(), 0, null);
        }

        StringBuilder raw = new StringBuilder();
        int j = i;
        boolean closed = false;
        for (; j < lines.length; j++) {
            String l = stripCr(lines[j]);
            raw.append(l).append('\n');
            if (l.contains("-->")) {
                closed = true;
                break;
            }
        }
        if (!closed) {
            return new Header(Map.of(), j, "第 " + (i + 1) + " 行：文档头没有闭合（缺少 -->）");
        }

        String body = raw.toString()
                .replaceFirst("^\\s*<!--\\s*doc\\s*", "")
                .replaceFirst("-->[\\s\\S]*$", "");
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : body.split("\\n")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            int c = t.indexOf(':');
            if (c <= 0) {
                // 不是 key: value —— 不报错，原样收进 _raw，免得丢信息
                map.merge("_raw", t, (a, b) -> a + " / " + b);
                continue;
            }
            map.put(t.substring(0, c).strip().toLowerCase(java.util.Locale.ROOT),
                    t.substring(c + 1).strip());
        }
        return new Header(map, j + 1, null);
    }

    private static String stripCr(String s) {
        return s != null && s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    /** 把文档头里的 {@code tags}（逗号分隔）拆开 */
    public static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : tags.split(",")) {
            String s = t.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** key = 有 id 用 id，否则用标题 */
    private static String curKey(String id, String title) {
        if (id != null && !id.isBlank()) {
            return id;
        }
        return title == null || title.isBlank() ? null : title;
    }

    private static Block finish(String key, String id, String title, String rawBody, int line) {
        return new Block(key, id, title, rawBody.strip(), line);
    }

    /** 去掉行首那一个转义反斜杠（只去行首的那个，正文里的反斜杠原样保留） */
    private static String stripOneBackslash(String raw) {
        int i = 0;
        while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t')) {
            i++;
        }
        if (i < raw.length() && raw.charAt(i) == '\\') {
            return raw.substring(0, i) + raw.substring(i + 1);
        }
        return raw;
    }

    /** 标题里的 {@code \=} → {@code =}，{@code \\} → {@code \} */
    static String unescapeTitle(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '=' || next == '\\') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 标题里的 {@code =} / {@code \} 转义回去 —— 导出时用，保证 render→parse 能往返 */
    static String escapeTitle(String s) {
        return s.replace("\\", "\\\\").replace("=", "\\=");
    }

    /** 正文行若会被解析成分隔符，就在行首加一个反斜杠让它变回普通文本 */
    static String escapeBodyLine(String line) {
        return tryDelimiter(line) != null ? "\\" + line : line;
    }

    /**
     * 把一个块渲染回文本（导出 / 往返测试用）。
     *
     * <p>id 非法时直接抛错而不是照写 —— 照写会生成一份**自己都解析不了的文档**，
     * 那种"导出去的备份恢复不回来"最坑。
     */
    public static String render(Block b) {
        if (b.id() != null && !b.id().isBlank() && !b.id().matches(ID_REGEX)) {
            throw new IllegalArgumentException("id 只能由英文字母、数字和 - 组成：" + b.id());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(escapeTitle(b.title())).append(" ===");
        if (b.id() != null && !b.id().isBlank()) {
            sb.append(" <!-- id: ").append(b.id()).append(" -->");
        }
        sb.append('\n');
        if (b.body() != null && !b.body().isBlank()) {
            for (String line : b.body().split("\\n", -1)) {
                sb.append(escapeBodyLine(line)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 把一组块渲染成一份文档 */
    public static String renderDocument(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(render(b));
        }
        return sb.toString();
    }
}
