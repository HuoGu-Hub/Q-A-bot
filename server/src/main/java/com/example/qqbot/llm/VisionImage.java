package com.example.qqbot.llm;

/**
 * 一张要交给视觉模型的图片 —— **中性类型**。
 *
 * <h2>为什么要有它</h2>
 * 原先 {@code LlmRouter.chatWithImages} 的签名直接是
 * {@code List<dev.langchain4j.data.message.ImageContent>}，于是 {@code agent/ChatService}
 * 为了下载图片、组装列表，**被迫 import 了 langchain4j 的图片类型** ——
 * 视觉消息怎么构造属于「用哪个模型、怎么调」的细节，不该漏到业务层。
 *
 * <p>现在业务层只说"这是一张 base64 + mimeType 的图"，
 * 转成 langchain4j 的 {@code ImageContent} 只发生在 {@code llm} 包里。
 *
 * @param base64   图片内容（不含 data URI 前缀）
 * @param mimeType 形如 {@code image/png}
 */
public record VisionImage(String base64, String mimeType) {
}
