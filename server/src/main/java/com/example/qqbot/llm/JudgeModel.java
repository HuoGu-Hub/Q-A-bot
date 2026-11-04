package com.example.qqbot.llm;

import org.springframework.stereotype.Component;

/**
 * 判定用的模型通道 —— 只负责发一次对话，不理解分数。
 *
 * <p>与 {@link LlmRouter} 分开的原因：判定模型应当可以和被测模型不是同一个。
 * {@code app.eval.judge.provider} 指定优先 provider，留空走默认降级链。
 * 判定器、rubric、解析都在 eval 侧，这里不掺业务。
 */
@Component
public class JudgeModel {

    private final LlmRouter router;

    public JudgeModel(LlmRouter router) {
        this.router = router;
    }

    public String ask(String system, String user, String preferredProvider) {
        LlmRouter.Reply reply = preferredProvider == null || preferredProvider.isBlank()
                ? router.chatWithSystem(system, user)
                : router.chatWithSystem(system, user, preferredProvider);
        return reply.text() == null ? "" : reply.text();
    }

}
