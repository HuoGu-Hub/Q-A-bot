package com.example.qqbot.media;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回复文本里的**图片标记** —— 模型表达"这里要配一张图"的唯一方式。
 *
 * <h2>标记长什么样</h2>
 * <pre>
 *   参考图：[[img:kb:copper-vein]]
 *   位置如下：[[img:map:embervale/copper-vein]]
 * </pre>
 * 形状是 {@code [[img:来源:名字]]}。为什么不用裸 URL：
 * <ul>
 *   <li>模型编得出任意 URL，却编不出我们图库里的名字 —— 名字必须来自
 *       资料块或工具结果，天然把"我们自己维护的资产"和"模型瞎编的外链"分开；</li>
 *   <li>来源写进 key 里，解析时按来源分派，加来源不动调用方；</li>
 *   <li>标记一定被摘掉，群里永远不会出现 {@code [[img:...]]} 这种字样。</li>
 * </ul>
 *
 * <h2>为什么要先摘标记、再过滤文本</h2>
 * 出站敏感词过滤是**按词替换文本**的。如果先过滤，命中的词可能改动标记内部的字，
 * 标记就再也匹配不上 —— 于是"这张图没了"，而且**不会报错**。
 * 所以解析必须在过滤之前（见 {@code OutboundSender.dispatch}）。
 */
public final class ImageMarkers {

    /** 标记的前缀。它是协议的一部分，提示词、解析、重建标记都用这一个常量 */
    public static final String PREFIX = "[[img:";

    /** 标记本体。key 里不许有方括号，避免贪婪吞掉正文 */
    private static final Pattern MARKER = Pattern.compile("\\[\\[img:([^\\[\\]]*)\\]\\]");

    /** key 的长度上限。够放下一长串拼音；更长的基本可以断定是模型写错了 */
    private static final int MAX_KEY_LENGTH = 120;

    /**
     * key 的语法校验：{@code 来源:名字}。
     *
     * <p>用代码写而不是一条正则，是因为这里挡的东西很具体，正则反而看不出挡了什么。
     *
     * <p><b>名字里允许中文</b>：图库里的文件名是中文再正常不过。
     * 真正危险的是路径分隔符与 {@code ..} —— 那些在名字里一律不许出现，
     * 顺序、层级什么都不认：key 最终会拼成磁盘路径，少挡一层就是一次任意文件读取。
     */
    static boolean isWellFormedKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            return false;
        }
        for (int i = 0; i < colon; i++) {
            char c = key.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                return false;
            }
        }
        for (int i = colon + 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '[' || c == ']' || c == '\\' || c == '\n' || c == '\r'
                    || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        // 名字按斜杠分段，每段都必须是一个正常名字：空段 / 点 / 双点一律不合法
        for (String part : key.substring(colon + 1).split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** 给提示词用的一句话说明，避免格式在代码与提示词里各写一份 */
    public static final String FORMAT = "[[img:来源:名字]]";

    /** 解析结果：摘掉标记后的正文 + 按出现顺序去重前的 key 列表 */
    public record Parsed(String text, List<String> keys) {
        public boolean hasKeys() {
            return !keys.isEmpty();
        }
    }

    public static Parsed parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf(PREFIX) < 0) {
            return new Parsed(raw == null ? "" : raw, List.of());
        }
        Matcher m = MARKER.matcher(raw);
        StringBuilder text = new StringBuilder();
        List<String> keys = new ArrayList<>();
        int last = 0;
        int removed = 0;
        while (m.find()) {
            text.append(raw, last, m.start());
            last = m.end();
            removed++;
            String key = m.group(1).trim();
            if (isWellFormedKey(key)) {
                keys.add(key);
            }
            // 不合法的标记同样要摘掉：留在正文里只会变成群里的一串乱码
        }
        text.append(raw, last, raw.length());
        String cleaned = text.toString();
        if (removed > 0) {
            // 标记被摘掉后常留下两个空格（"看看 [[img:x]] 吧"），顺手收一收。
            // 只在真的摘过标记时才动空格 —— 没标记的回复必须**一个字都不改**。
            cleaned = cleaned.replaceAll("[ \t]{2,}", " ");
        }
        return new Parsed(cleaned.strip(), List.copyOf(keys));
    }

    /**
     * 把**唯一**的那个标记挪到正文末尾，单独一行。
     *
     * <h2>为什么值得做</h2>
     * 资料块与工具结果的正文是模型要改写的东西。标记夹在句子中间时，
     * 模型改写一遍正文就很容易把它<b>整句重写掉</b> —— 表现是"资料里明明有图，
     * 机器人却从来不发"，而且不报错。提到末尾单独一行，它就成了一个显眼的、
     * 可以不改写的东西。
     *
     * <h2>为什么只挪唯一的一个</h2>
     * 一个块讲一件事、配一张图，这是绝大多数情况。出现两个及以上标记时
     * <b>一个都不动</b>：那说明图与正文的对应关系有顺序（A 的图、B 的图），
     * 挪到末尾就把对应关系弄丢了，模型可能把 B 的图配给 A，同样看不出来。
     *
     * @return 标记数不是 1、或者本来就在末尾时，原样返回
     */
    public static String hoistSingleMarker(String text) {
        if (text == null || text.isEmpty() || text.indexOf(PREFIX) < 0) {
            return text;
        }
        Matcher m = MARKER.matcher(text);
        List<String> keys = new ArrayList<>();
        int[] span = null;
        while (m.find()) {
            String key = m.group(1).trim();
            if (isWellFormedKey(key)) {
                keys.add(key);
                span = new int[]{m.start(), m.end()};
            }
        }
        if (keys.size() != 1 || span == null) {
            return text;
        }
        String before = text.substring(0, span[0]);
        String after = text.substring(span[1]);
        // 已经"单独占一行且在最后"就原样返回；贴在句尾的（"…砍刀。[[img:x]]"）仍要拆成两行
        if (after.isBlank() && (before.isEmpty() || before.endsWith("\n"))) {
            return text;
        }
        String body = (before + after).strip().replaceAll("[ \\t]{2,}", " ");
        String marker = PREFIX + keys.get(0) + "]]";
        return body.isEmpty() ? marker : body + "\n" + marker;
    }

    private ImageMarkers() {
    }
}
