package com.example.qqbot.distill;

import com.example.qqbot.llm.LlmRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistillLlmTest {

    @Test
    @DisplayName("有视觉能力时把图片字节作为附件交给模型")
    void sendsImagesWhenVisionAvailable() {
        LlmRouter router = mock(LlmRouter.class);
        when(router.hasVision()).thenReturn(true);
        when(router.chatWithSystemAndImages(anyString(), anyString(), anyList(), anyString(),
                org.mockito.ArgumentMatchers.eq(LlmRouter.CallMode.BACKGROUND)))
                .thenReturn(new LlmRouter.Reply("{\"notes\":[]}", "fake", 10, 5));

        DistillLlm llm = new DistillLlm(router);
        var result = llm.summarize("看看这张图", List.of(
                new DistillLlm.Attachment(3, "image/png", new byte[]{1, 2, 3})));

        assertThat(result.text()).isEqualTo("{\"notes\":[]}");
        verify(router).chatWithSystemAndImages(
                org.mockito.ArgumentMatchers.eq(DistillPrompts.SYSTEM),
                org.mockito.ArgumentMatchers.eq("看看这张图"),
                argThat(images -> images.size() == 1
                        && "image/png".equals(images.get(0).mimeType())),
                org.mockito.ArgumentMatchers.eq(""),
                org.mockito.ArgumentMatchers.eq(LlmRouter.CallMode.BACKGROUND));
    }

    @Test
    @DisplayName("没有图片时也走后台长超时，不占用前台 60 秒")
    void textOnlyUsesBackgroundMode() {
        LlmRouter router = mock(LlmRouter.class);
        when(router.hasVision()).thenReturn(false);
        when(router.chatWithSystem(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(LlmRouter.CallMode.BACKGROUND)))
                .thenReturn(new LlmRouter.Reply("ok", "fake", 1, 1));

        DistillLlm llm = new DistillLlm(router);
        assertThat(llm.summarize("总结一下").text()).isEqualTo("ok");

        verify(router).chatWithSystem(
                org.mockito.ArgumentMatchers.eq(DistillPrompts.SYSTEM),
                org.mockito.ArgumentMatchers.eq("总结一下"),
                org.mockito.ArgumentMatchers.eq(""),
                org.mockito.ArgumentMatchers.eq(LlmRouter.CallMode.BACKGROUND));
    }
}
