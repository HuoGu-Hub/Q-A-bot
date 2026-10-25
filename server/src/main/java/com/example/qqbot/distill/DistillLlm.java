package com.example.qqbot.distill;

import com.example.qqbot.llm.LlmRouter;
import org.springframework.stereotype.Service;

/**
 * 蒸馏的模型调用。
 *
 * <p>走 {@link LlmRouter#chatWithSystem}：用蒸馏自己的系统提示词，
 * 不叠群聊人格 {@code system.md}，也不把 {@code dev.langchain4j} 类型带进业务包。
 */
@Service
public class DistillLlm {

    public record Result(String text, String model, long tokensIn, long tokensOut) {
    }

    private final LlmRouter router;

    public DistillLlm(LlmRouter router) {
        this.router = router;
    }

    public boolean available() {
        return router.isAvailable();
    }

    public Result summarize(String userPrompt) {
        LlmRouter.Reply reply = router.chatWithSystem(DistillPrompts.SYSTEM, userPrompt);
        return new Result(reply.text(), reply.model(), reply.tokensIn(), reply.tokensOut());
    }
}
