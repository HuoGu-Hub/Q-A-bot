package com.example.qqbot.agent;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.guard.ContentGate;
import com.example.qqbot.intent.TurnMove;
import com.example.qqbot.intent.TurnPlan;
import com.example.qqbot.intent.TurnStore;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「纠正」这条路的验收测试。
 *
 * <p>要断言的是**模型到底看到了什么**：有上一轮时才把 RECENT 塞进信封，
 * 而且那句话必须明确写着"只改被指出的那一点" —— 否则模型会把整段重答一遍。
 */
class ChatServiceCorrectionTest {

    private KbToolAssistant toolAssistant;
    private TurnStore turnStore;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        LlmRouter llmRouter = mock(LlmRouter.class);
        when(llmRouter.isAvailable()).thenReturn(true);
        toolAssistant = mock(KbToolAssistant.class);
        when(toolAssistant.chat(anyString(), anyString(), any(KbRetrievalDiagnostics.class),
                any(Boolean.class), anyString(), anyString(), any(MediaOutbox.class)))
                .thenReturn("嗯，是 185");
        turnStore = mock(TurnStore.class);
        when(turnStore.recent(anyLong(), anyLong())).thenReturn(List.of(
                new TurnStore.Turn(1L, "现在技能点上限是多少", "目前是 10 点，满级后还能再加")));

        chatService = new ChatService(llmRouter, mock(ImageFetcher.class), mock(ContentGate.class),
                mock(MediaProperties.class), mock(KbRetriever.class), mock(KbProperties.class),
                toolAssistant, null, mock(ResourceDirectory.class), turnStore);
    }

    private String envelopeOf(String question, TurnPlan plan) {
        OneBotEvent event = mock(OneBotEvent.class);
        chatService.reply(event, question, null, List.of(), ReplyMode.KB, false, plan);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(toolAssistant).chat(message.capture(), anyString(), any(KbRetrievalDiagnostics.class),
                any(Boolean.class), anyString(), anyString(), any(MediaOutbox.class));
        return message.getValue();
    }

    private static TurnPlan correctingPlan() {
        return new TurnPlan(TurnMove.CORRECT, true, true, "对上一轮的纠正");
    }

    @Test
    @DisplayName("★ 纠正：信封里带上上一轮，并要求只改那一点")
    void correctionCarriesPreviousTurn() {
        String message = envelopeOf("不是185吗", correctingPlan());
        assertThat(message).contains("<<<RECENT");
        assertThat(message).contains("现在技能点上限是多少");
        assertThat(message).contains("目前是 10 点");
        assertThat(message).contains("只改对方指出的那一点");
        assertThat(message).contains("RECENT>>>");
    }

    @Test
    @DisplayName("普通提问不带历史 —— 带了反而会把检索往上一轮拽")
    void normalTurnHasNoRecent() {
        String message = envelopeOf("香菇在哪刷新", TurnPlan.standard());
        assertThat(message).doesNotContain("RECENT");
    }

    @Test
    @DisplayName("说是纠正但没有上一轮：按普通提问处理")
    void correctionWithoutHistoryIsNormal() {
        when(turnStore.recent(anyLong(), anyLong())).thenReturn(List.of());
        String message = envelopeOf("不是185吗", correctingPlan());
        assertThat(message).doesNotContain("RECENT");
    }

    @Test
    @DisplayName("答完记一轮；纠正时先标旧的再记新的")
    void remembersTurnAndMarksCorrected() {
        envelopeOf("不是185吗", correctingPlan());
        verify(turnStore).markCorrected(anyLong(), anyLong());
        verify(turnStore).append(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("普通提问只记，不标纠正")
    void normalTurnDoesNotMark() {
        envelopeOf("香菇在哪刷新", TurnPlan.standard());
        verify(turnStore, never()).markCorrected(anyLong(), anyLong());
        verify(turnStore).append(anyLong(), anyLong(), anyString(), anyString());
    }
}
