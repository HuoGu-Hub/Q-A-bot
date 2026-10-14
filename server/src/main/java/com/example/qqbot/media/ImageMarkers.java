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
        if (raw == null || raw.isEmpty() || raw.indexOf("[[img:") < 0) {
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

    private ImageMarkers() {
    }
}
