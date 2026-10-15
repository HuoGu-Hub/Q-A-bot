package com.example.qqbot.llm;

import java.util.regex.Pattern;

/**
 * 模型写进正文里的"工具调用标记" —— 出站前必须摘掉的协议噪音。
 *
 * <h2>为什么需要它</h2>
 * 有些厂商（或某些模型）在"要调工具"时不返回结构化的 {@code tool_calls}，
 * 而是把调用写成正文：
 *
 * <pre>
 *   &lt;tool_call&gt;{"name": "search_titles", "arguments": {"query": "光芒戒指"}}&lt;/tool_call&gt;
 *   &lt;｜｜DSML｜｜ calls&gt; ... &lt;/｜｜DSML｜｜ calls&gt;      （DeepSeek 系的原生标记）
 * </pre>
 *
 * <p>这类文字一旦被当成回答发出去，群友看到的就是一串协议细节 ——
 * 2026-10-15 实测发生过两次（一次 DSML、一次 {@code <tool_call>}）。
 * 根因是把工具定义漏在请求外的那个 bug 已经修了，但**兜底必须留着**：
 * 模型编错工具名、厂商漏解析、以后换模型，都可能再吐一次。
 *
 * <h2>为什么放在 llm 包</h2>
 * 标记的格式是"工具调用协议"的一部分，只有模型层认识它。
 * 它本身是纯文本工具（不 import 任何框架类型），所以出站那条路也能安全地用。
 *
 * <h2>摘除规则</h2>
 * <ul>
 *   <li>完整的 {@code <tool_call>…</tool_call>} 块：整块删掉，标记后面正常的正文保留；</li>
 *   <li>没闭合的 {@code <tool_call>}：从标记起到末尾全删 —— 半截协议绝不能发出去；</li>
 *   <li>DSML：从第一个标记起到末尾全删。它的开闭标签互相嵌套，
 *       逐个配对容易留下残渣，而这类输出本来也不会和正文混排。</li>
 * </ul>
 */
public final class ToolCallMarkup {

    /**
     * 三条规则按顺序尝试，命中即替换。全部带 {@code (?is)}：忽略大小写 + 让 {@code .} 跨行。
     */
    private static final Pattern MARKUP = Pattern.compile(
            "(?is)<tool_calls?[^>]*>.*?</tool_calls?>"
                    + "|(?is)<tool_calls?[^>]*>.*$"
                    + "|(?is)<[｜|]{2}\\s*DSML\\s*[｜|]{2}.*$");

    private ToolCallMarkup() {
    }

    /** 正文里有没有工具调用标记 */
    public static boolean hasMarkup(String text) {
        return text != null && MARKUP.matcher(text).find();
    }

    /**
     * 摘掉工具调用标记。
     *
     * @return 摘除后的正文（首尾空白一并去掉）；传入 null 返回 null
     */
    public static String strip(String text) {
        if (text == null) {
            return null;
        }
        return MARKUP.matcher(text).replaceAll("").strip();
    }

    /**
     * 整条回复除了标记什么都没有 —— 这种情况不能"摘完发空消息"，调用方要用兜底话术顶上。
     */
    public static boolean isOnlyMarkup(String text) {
        return hasMarkup(text) && strip(text).isBlank();
    }
}
