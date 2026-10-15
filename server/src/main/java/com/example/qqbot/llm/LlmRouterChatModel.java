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
 * 这一点与 {@link LlmRouter#chat(String)} 不同 —— 那条入口自己拼系统消息，
 * 现在只服务广场与提案两条旁路。
 *
 * <p><b>它必须整条转发 {@code ChatRequest}，不能只转发 {@code request.messages()}</b>：
 * 工具定义住在 {@code ChatRequest.toolSpecifications()} 里。只传消息的话工具
 * 永远到不了模型 —— 而且不报错：模型只能照着 system.md 里提到的工具名自己"编"，
 * 写出来的 {@code <tool_call>{"name": ...}</tool_call>} 被当成正常回答发进了群（实测事故 2026-10-15）。
 */
@Component
public class LlmRouterChatModel implements ChatModel {

    private final LlmRouter router;

    public LlmRouterChatModel(LlmRouter router) {
        this.router = router;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        // 整条转发：工具定义、工具选择、响应格式都在 request 里，漏一个模型就"看不见"工具
        return router.chatMessages(request);
    }
}
