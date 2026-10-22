package com.example.qqbot.text;

/**
 * 提示词里的“不可信文本”处理。
 *
 * <p>系统消息与用户消息都靠固定标记分段：{@code <<<USER_CONTENT>>>}、
 * {@code <<<KNOWLEDGE>>>}、{@code <<<QUOTED_CONTENT>>>}。这些标记只有代码能写，
 * 用户正文、引用、资料块、工具结果里的同名文本必须被改掉，否则一段外部文本就能
 * 提前闭合标记、伪造后面的“信封”。
 *
 * <p>这里用可见的 {@code ‹‹‹} / {@code ›››} 替换，而不是插入零宽字符：
 * 零宽字符可能被模型忽略，等于没转义；可见替换能保证模型看到的已经不是同一个标记。
 *
 * <p><b>只用于不可信内容</b>。代码自己拼的信封、资料段标题不要过这一层。
 */
public final class PromptText {

    private PromptText() {
    }

    /**
     * 把文本里所有三连尖括号替换成不可与定界符混淆的字符。
     *
     * <p>同时处理全角写法：模型可能把全角 {@code ＜＜＜} 也当成标记，
     * 而代码只认半角，提前改掉可以少一层歧义。
     *
     * @param text 不可信文本；{@code null} 当空串
     * @return 转义后的文本，绝不为 {@code null}
     */
    public static String escapeDelimiters(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("<<<", "‹‹‹")
                .replace(">>>", "›››")
                .replace("＜＜＜", "‹‹‹")
                .replace("＞＞＞", "›››");
    }

    /**
     * 压成一行并截断，专给昵称 / 群名片这类会直接拼进信封的短文本用。
     *
     * <p>换行是这里最危险的东西：一行 {@code 发言者：} 后面如果跟了换行，
     * 攻击者就能伪造出“新的段落”。所以先把所有控制字符压成空格。
     *
     * @param text     原始文本
     * @param maxChars 最大长度；{@code <= 0} 表示不截断
     * @return 单行文本
     */
    public static String oneLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String t = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .replaceAll("\\p{Cntrl}", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (maxChars <= 0 || t.length() <= maxChars) {
            return t;
        }
        return t.substring(0, maxChars) + "…";
    }
}
