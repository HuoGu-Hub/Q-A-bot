package com.example.qqbot.agent;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.guard.ContentGate;
import com.example.qqbot.kb.KbRetrievalDiagnostics;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.llm.KbToolAssistant;
import com.example.qqbot.llm.LlmRouter;
import com.example.qqbot.media.ImageFetcher;
import com.example.qqbot.onebot.model.OneBotEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回复里出现"工具调用标记"时的处理 —— 2026-10-15 群里漏出 {@code <tool_call>} 的回归测试。
 *
 * <p>模型只吐标记时不能"摘完发空消息"：群友该收到的是一句兜底话术。
 * 标记混在正文里时只摘标记，正文一个字都不能少。
 */
class ChatServiceToolMarkupTest {

    private LlmRouter llmRouter;
    private KbToolAssistant assistant;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        when(llmRouter.isAvailable()).thenReturn(true);
        assistant = mock(KbToolAssistant.class);
        chatService = new ChatService(llmRouter, mock(ImageFetcher.class), new ContentGate(),
                new MediaProperties(), mock(KbRetriever.class), new KbProperties(), assistant);
    }

    private OneBotEvent groupEvent() {
        OneBotEvent event = mock(OneBotEvent.class);
        when(event.isGroupMessage()).thenReturn(true);
        when(event.getGroupId()).thenReturn(209127588L);
        when(event.getUserId()).thenReturn(2302359993L);
        return event;
    }

    private void modelReplies(String text) {
        when(assistant.chat(anyString(), anyString(), any(KbRetrievalDiagnostics.class), anyBoolean(), anyString()))
                .thenReturn(text);
    }

    @Test
    @DisplayName("★ 模型只返回工具调用标记：换成兜底话术，标记绝不出门")
    void markupOnlyBecomesFallback() {
        modelReplies("<tool_call>{\"name\": \"search_titles\", \"arguments\": {\"query\": \"光芒戒指\"}}</tool_call>");

        ChatService.ReplyResult result = chatService.reply(groupEvent(), "光芒戒指在哪里", null, List.of());

        assertThat(result.text()).isEqualTo("抱歉，我刚才走神了，稍后再问我一次吧～");
    }

    @Test
    @DisplayName("★ 标记混在正文里：只摘标记，正文保持不变")
    void markupIsStrippedFromMixedReply() {
        modelReplies("戒指在 Elder Bridge 的宝箱里。\n<tool_call>{\"name\": \"lookup\"}</tool_call>");

        ChatService.ReplyResult result = chatService.reply(groupEvent(), "光芒戒指在哪里", null, List.of());

        assertThat(result.text()).isEqualTo("戒指在 Elder Bridge 的宝箱里。");
    }

    @Test
    @DisplayName("正常回复一个字都不动")
    void normalReplyUntouched() {
        modelReplies("戒指在 Elder Bridge 的宝箱里。");

        ChatService.ReplyResult result = chatService.reply(groupEvent(), "光芒戒指在哪里", null, List.of());

        assertThat(result.text()).isEqualTo("戒指在 Elder Bridge 的宝箱里。");
    }
}
