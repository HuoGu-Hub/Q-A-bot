package com.example.qqbot.distill;

import com.example.qqbot.llm.LlmRouter;
import com.example.qqbot.llm.VisionImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 蒸馏的模型调用。
 *
 * <p>走 {@link LlmRouter#chatWithSystem}：用蒸馏自己的系统提示词，
 * 不叠群聊人格 {@code system.md}，也不把 {@code dev.langchain4j} 类型带进业务包。
 */
@Service
public class DistillLlm {

    private static final Logger log = LoggerFactory.getLogger(DistillLlm.class);

    public record Result(String text, String model, long tokensIn, long tokensOut) {
    }

    /** 一张要送给视觉模型的图 */
    public record Attachment(long imageId, String mimeType, byte[] bytes) {
    }

    private final LlmRouter router;

    public DistillLlm(LlmRouter router) {
        this.router = router;
    }

    public boolean available() {
        return router.isAvailable();
    }

    public boolean hasVision() {
        return router.hasVision();
    }

    public List<LlmRouter.ProviderInfo> providers() {
        return router.providers();
    }

    public Result summarize(String userPrompt) {
        return summarize(userPrompt, List.of(), "");
    }

    public Result summarize(String userPrompt, List<Attachment> images) {
        return summarize(userPrompt, images, "");
    }

    public Result summarize(String userPrompt, List<Attachment> images, String provider) {
        LlmRouter.Reply reply;
        if (images != null && !images.isEmpty() && router.hasVision()) {
            List<VisionImage> vision = new ArrayList<>();
            for (Attachment a : images) {
                String mime = a.mimeType() == null || a.mimeType().isBlank()
                        ? "image/jpeg" : a.mimeType();
                vision.add(new VisionImage(Base64.getEncoder().encodeToString(a.bytes()), mime));
            }
            try {
                reply = router.chatWithSystemAndImages(DistillPrompts.SYSTEM, userPrompt, vision, provider);
            } catch (RuntimeException e) {
                // 单张图格式不兼容或视觉端点抖动时，退回纯文本，别让整批失败
                log.warn("[DISTILL] 视觉调用失败，本批退回纯文本：{}", e.getMessage());
                reply = router.chatWithSystem(DistillPrompts.SYSTEM, userPrompt, provider);
            }
        } else {
            // 没有视觉模型或本批没有可读图片时退回纯文本，不让整批失败
            reply = router.chatWithSystem(DistillPrompts.SYSTEM, userPrompt, provider);
        }
        return new Result(reply.text(), reply.model(), reply.tokensIn(), reply.tokensOut());
    }
}
