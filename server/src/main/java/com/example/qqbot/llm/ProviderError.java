package com.example.qqbot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把厂商返回的错误体翻成一句人话。
 *
 * <h2>为什么需要它</h2>
 * OpenAI 兼容厂商报错时给的是 JSON：
 * <pre>
 *   {"error":{"type":"authentication_error","code":"api_key_expired","message":"This API key has expired…"}}
 * </pre>
 * 原样丢给用户（或日志）有两个问题：
 * <ul>
 *   <li>读的人得自己在 JSON 里找重点；</li>
 *   <li>更糟的是被误判 —— 管理后台拉模型列表拿到 401，界面却说
 *       "该厂商可能不提供模型列表接口"，于是人去手打模型 ID，白折腾（2026-10-22 实测）。</li>
 * </ul>
 *
 * <p>只做一件事：从 {@code error.message} / {@code error} / 纯文本里取出那句话，
 * 取不到就原样返回。**不判断业务含义** —— 那是调用方的事（见 {@link #isAuthError(int)}）。
 */
public final class ProviderError {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 再长的错误也不值得整段展示，截断即可 */
    private static final int MAX_CHARS = 300;

    private ProviderError() {
    }

    /** 401/403 = 密钥问题。这类错误值得单独说清楚，别混进"接口不支持"里 */
    public static boolean isAuthError(int statusCode) {
        return statusCode == 401 || statusCode == 403;
    }

    /**
     * 从厂商的原始响应体里取出可读的那句话。
     *
     * @param raw 原始响应体；可能不是 JSON（超时、连接失败的异常消息也走这里），
     *            也可能是 null
     * @return 人话；取不到时返回空串，调用方据此不加冒号
     */
    public static String readable(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.strip();
        String message = null;
        if (text.startsWith("{")) {
            try {
                JsonNode root = MAPPER.readTree(text);
                JsonNode error = root.path("error");
                if (error.isObject()) {
                    message = firstText(error, "message", "code", "type");
                } else if (error.isTextual()) {
                    message = error.asText();
                } else {
                    message = firstText(root, "message");
                }
            } catch (Exception ignored) {
                // 不是合法 JSON —— 当纯文本处理
            }
        }
        if (message == null || message.isBlank()) {
            message = text;
        }
        return message.length() > MAX_CHARS ? message.substring(0, MAX_CHARS) + "…" : message;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }
}
