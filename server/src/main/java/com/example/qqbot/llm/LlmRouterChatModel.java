package com.example.qqbot.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * 把多厂商降级链包成官方 {@link ChatModel}。
 *
 * <p><b>为什么需要它</b>：官方 {@code AiServices} 只认一个 {@code ChatModel}，
 * 而我们真正的模型入口是 {@link LlmRouter} —— 它管着降级链、按能力过滤、空回复换下一个、
 * 后台热替换模型、以及 token 预算累加。包一层之后，工具循环走的就是这一整套，
 * 而不是另起一条没有降级、没有计费的旁路。
 *
 * <p><b>它不注入系统提示词</b>：系统消息由 AiServices 提供。
 * 这一点与 {@code chat()/chatWithImages()} 不同 —— 那两条老路径自己拼系统消息。
 */
@Component
public class LlmRouterChatModel implements ChatModel {

    private final LlmRouter router;

    public LlmRouterChatModel(LlmRouter router) {
        this.router = router;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return router.chatMessages(request.messages());
    }
}
