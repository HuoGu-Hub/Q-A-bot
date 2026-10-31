package com.example.qqbot.agent;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.guard.ContentGate;
import com.example.qqbot.kb.KbRetrievalDiagnostics;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.kb.ResourceDirectory;
import com.example.qqbot.llm.KbToolAssistant;
import com.example.qqbot.prompt.PromptStore;
import com.example.qqbot.llm.LlmRouter;
import com.example.qqbot.media.ImageFetcher;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.voice.MediaOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    private PromptStore promptStore;
    private ResourceDirectory resources;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        when(llmRouter.isAvailable()).thenReturn(true);
        assistant = mock(KbToolAssistant.class);
        promptStore = mock(PromptStore.class);
        when(promptStore.appendixFor(anyLong(), anyLong())).thenReturn("");
        resources = mock(ResourceDirectory.class);
        chatService = new ChatService(llmRouter, mock(ImageFetcher.class), new ContentGate(),
                new MediaProperties(), mock(KbRetriever.class), new KbProperties(), assistant, promptStore,
                resources);
    }

    private OneBotEvent groupEvent() {
        OneBotEvent event = mock(OneBotEvent.class);
        when(event.isGroupMessage()).thenReturn(true);
        when(event.getGroupId()).thenReturn(209127588L);
        when(event.getUserId()).thenReturn(2302359993L);
        return event;
    }

    private void modelReplies(String text) {
        when(assistant.chat(anyString(), anyString(), any(KbRetrievalDiagnostics.class), anyBoolean(),
                anyString(), anyString(), any(MediaOutbox.class)))
                .thenReturn(text);
    }

    @Test
    @DisplayName("★ 当前用户的补充提示会传给模型助手")
    void passesPromptAppendix() {
        when(promptStore.appendixFor(209127588L, 2302359993L))
                .thenReturn("【补充提示】\n- 请叫我老板");
        when(assistant.chat(anyString(), anyString(), any(KbRetrievalDiagnostics.class), anyBoolean(),
                anyString(), anyString(), any(MediaOutbox.class))).thenReturn("好");

        chatService.reply(groupEvent(), "你好", null, List.of());

        verify(assistant).chat(anyString(), anyString(), any(KbRetrievalDiagnostics.class), anyBoolean(),
                anyString(), contains("请叫我老板"), any(MediaOutbox.class));
    }

    @Test
    @DisplayName("★ 资源问题模型漏了配图：ChatService 兜底补 oreshot 标记")
    void attachesResourceShotWhenModelOmitsIt() {
        modelReplies("金黄花主要在春之原野，最密的一片在火焰神龛附近。");
        ResourceDirectory.Layer layer = new ResourceDirectory.Layer("Embervale",
                "plant:aureolin_flower", "金黄花", "plant", "aureolin_flower", 296, "#d6a21e");
        ResourceDirectory.Hotspot hotspot = new ResourceDirectory.Hotspot(4926, 1316, 15,
                "Springlands", 4800, 1200, 5000, 1400, "火焰神龛·春之原野", 129, 100, 10);
        when(resources.answer(contains("金黄花"))).thenReturn(
                new ResourceDirectory.Answer("金黄花", layer, 296, 250, List.of(hotspot)));

        ChatService.ReplyResult result = chatService.reply(groupEvent(),
                "金黄花在哪里比较多？", null, List.of());

        assertThat(result.text()).contains("[[img:oreshot:Embervale/plant:aureolin_flower/1]]");
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
