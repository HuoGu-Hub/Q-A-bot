package com.example.qqbot.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 厂商没有回 usage 时的 token 估算。
 *
 * <h2>为什么需要它</h2>
 * 统计与预算都建立在 {@code ChatResponse.tokenUsage()} 上。OpenAI 协议里 usage 是
 * <b>可选字段</b>，只要有一家厂商不回，那一部分消耗就会在账上变成 0 ——
 * 不是"免费"，而是"看不见"。看不见的成本没法做预算，也没法回答"这个月花了多少"。
 *
 * <h2>为什么按字符算，而不是引一个分词器</h2>
 * 精确分词要跟厂商的 tokenizer 逐家对齐：DeepSeek、Qwen、GLM 各一套，
 * 还要跟着模型版本升级。为一条兜底路径背这个维护成本不划算 ——
 * 厂商回了 usage 就用真实值，这里只要求"量级对、趋势对"。
 *
 * <h2>系数怎么来的</h2>
 * 2026-11-08 用 opencode-go / deepseek-v4.1-flash 实测：
 * <table>
 *   <tr><th>样本</th><th>实际 prompt_tokens</th><th>加权字符</th></tr>
 *   <tr><td>三字短问</td><td>33</td><td>4.2</td></tr>
 *   <tr><td>约 1.1KB 英文</td><td>277</td><td>275</td></tr>
 *   <tr><td>system.md + 短问</td><td>1982</td><td>2507</td></tr>
 * </table>
 * 长文本的斜率约 0.79 token / 加权字，短文本有一笔 25~30 token 的固定开销。
 * 取 0.85 与 25 是在两组样本上的折中：长文本高估约 10%，短文本误差更大但绝对量小。
 *
 * <p>中文按 0.85 token / 字，ASCII 按 4 字符 / token，其余字符也按 4 字符 / token。
 * 一条消息再加 4 token 的结构开销，整条请求加 25 token。
 *
 * <h2>它测不到什么</h2>
 * 推理模型的「思考」token 不会出现在返回正文里，所以<b>输出会偏低</b>。
 * 这正是"只在缺 usage 时兜底"的原因：默认 provider 会回 usage，主线不吃这个偏差。
 */
final class TokenEstimator {

    /** 中日韩文字与全角标点的折算系数，实测斜率约 0.79，取 0.85 略偏保守 */
    static final double CJK_TOKENS = 0.85;

    /** 其余字符每 4 个算 1 token */
    static final double OTHER_CHARS_PER_TOKEN = 4.0;

    /** 一条消息的角色与分隔符开销 */
    static final int MESSAGE_OVERHEAD = 4;

    /** 一次请求的角色声明、结束标记等固定开销 */
    static final int REQUEST_OVERHEAD = 25;

    /**
     * 一张图折算多少 token。
     *
     * <p>视觉模型的图片 token 取决于分辨率与切块策略，我们拿不到像素尺寸，
     * 只能给一个中间值。它的作用是把"带图请求"和"纯文本请求"区分开，
     * 不是精确计费。
     */
    static final int IMAGE_TOKENS = 800;

    private TokenEstimator() {
    }

    /** 估一次请求的输入 token 数，含消息、工具定义与图片 */
    static long inputTokens(ChatRequest request) {
        if (request == null) {
            return 0;
        }
        long weighted = 0;
        int messages = 0;
        int images = 0;

        for (ChatMessage message : request.messages()) {
            messages++;
            if (message instanceof SystemMessage system) {
                weighted += weightedChars(system.text());
            } else if (message instanceof UserMessage user) {
                for (Content content : user.contents()) {
                    if (content instanceof TextContent text) {
                        weighted += weightedChars(text.text());
                    } else if (content instanceof ImageContent) {
                        images++;
                    }
                }
            } else if (message instanceof AiMessage ai) {
                weighted += weightedChars(ai.text());
                if (ai.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                        weighted += weightedChars(call.name());
                        weighted += weightedChars(call.arguments());
                    }
                }
            } else if (message instanceof ToolExecutionResultMessage result) {
                weighted += weightedChars(result.text());
            }
        }

        long toolTokens = 0;
        if (request.toolSpecifications() != null) {
            for (ToolSpecification spec : request.toolSpecifications()) {
                toolTokens += weightedChars(toolJson(spec));
            }
        }

        long total = weighted + toolTokens
                + (long) messages * MESSAGE_OVERHEAD
                + REQUEST_OVERHEAD
                + (long) images * IMAGE_TOKENS;
        return Math.max(1, total);
    }

    /** 估一次回复的输出 token 数，含工具调用参数 */
    static long outputTokens(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            return 1;
        }
        AiMessage message = response.aiMessage();
        long weighted = weightedChars(message.text());
        if (message.hasToolExecutionRequests()) {
            for (ToolExecutionRequest call : message.toolExecutionRequests()) {
                weighted += weightedChars(call.name());
                weighted += weightedChars(call.arguments());
            }
        }
        return Math.max(1, weighted + 2);
    }

    /** 一段文本折算成多少 token；结构开销另算 */
    static long textTokens(String text) {
        return weightedChars(text);
    }

    /**
     * 按字符类型加权。
     *
     * <p>中文一个字通常就是一个 token 上下，英文四个字符一个 token。
     * 全角标点、假名、谚文按中文算 —— 它们在分词器里的密度接近。
     */
    static long weightedChars(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjk = 0;
        long other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        return Math.round(cjk * CJK_TOKENS + other / OTHER_CHARS_PER_TOKEN);
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL) {
            return true;
        }
        // CJK 标点与全角字符：分词器大多一个字一个 token
        return (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFFEF);
    }

    /** 工具定义转成 JSON 的字符量；转不出来就退化成名字与描述 */
    private static String toolJson(ToolSpecification spec) {
        try {
            String json = spec.toJson();
            if (json != null && !json.isBlank()) {
                return json;
            }
        } catch (Exception ignored) {
            // 自定义 schema 可能不可序列化，退化成下面的拼接
        }
        return String.valueOf(spec.name()) + spec.description();
    }
}
