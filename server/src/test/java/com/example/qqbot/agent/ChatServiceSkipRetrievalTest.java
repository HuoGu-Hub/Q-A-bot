package com.example.qqbot.agent;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.guard.ContentGate;
import com.example.qqbot.intent.TurnMove;
import com.example.qqbot.intent.TurnPlan;
import com.example.qqbot.kb.KbRetrievalDiagnostics;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.kb.ResourceDirectory;
import com.example.qqbot.llm.KbToolAssistant;
import com.example.qqbot.llm.LlmRouter;
import com.example.qqbot.media.ImageFetcher;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.voice.MediaOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「社交整活不查资料」的验收测试。
 *
 * <p>要断言的**不是**回答好不好看，而是这一次到底有没有把工作交给检索 ——
 * 也就是 {@code toolAssistant.chat(...)} 的 {@code retrieve} 参数。
 * 判定错了的代价就该只是这个布尔值，不该变成"答不出来"。
 */
class ChatServiceSkipRetrievalTest {

    private LlmRouter llmRouter;
    private KbToolAssistant toolAssistant;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        when(llmRouter.isAvailable()).thenReturn(true);
        toolAssistant = mock(KbToolAssistant.class);
        when(toolAssistant.chat(anyString(), anyString(), any(KbRetrievalDiagnostics.class),
                any(Boolean.class), anyString(), anyString(), any(MediaOutbox.class)))
                .thenReturn("喵~");

        chatService = new ChatService(llmRouter, mock(ImageFetcher.class), mock(ContentGate.class),
                mock(MediaProperties.class), mock(KbRetriever.class), mock(KbProperties.class),
                toolAssistant, null, mock(ResourceDirectory.class));
    }

    private boolean retrieveFlagOf(String question, TurnPlan plan) {
        OneBotEvent event = mock(OneBotEvent.class);
        chatService.reply(event, question, null, List.of(), ReplyMode.KB, false, plan);
        ArgumentCaptor<Boolean> flag = ArgumentCaptor.forClass(Boolean.class);
        verify(toolAssistant).chat(anyString(), anyString(), any(KbRetrievalDiagnostics.class),
                flag.capture(), anyString(), anyString(), any(MediaOutbox.class));
        return flag.getValue();
    }

    @Test
    @DisplayName("★ 判定为社交：不检索")
    void socialSkipsRetrieval() {
        TurnPlan plan = new TurnPlan(TurnMove.SOCIAL, false, false, "这类问题不用资料");
        assertThat(retrieveFlagOf("卖个萌", plan)).isFalse();
    }

    @Test
    @DisplayName("判定为默认：照常检索，与接入前一致")
    void defaultStillRetrieves() {
        assertThat(retrieveFlagOf("香菇在哪刷新", TurnPlan.standard())).isTrue();
    }

    @Test
    @DisplayName("指令显式要求不用知识库：即使判定说检索也不查")
    void explicitNoneWins() {
        OneBotEvent event = mock(OneBotEvent.class);
        chatService.reply(event, "香菇在哪刷新", null, List.of(), ReplyMode.NONE, false, TurnPlan.standard());
        ArgumentCaptor<Boolean> flag = ArgumentCaptor.forClass(Boolean.class);
        verify(toolAssistant).chat(anyString(), anyString(), any(KbRetrievalDiagnostics.class),
                flag.capture(), anyString(), anyString(), any(MediaOutbox.class));
        assertThat(flag.getValue()).isFalse();
    }
}
