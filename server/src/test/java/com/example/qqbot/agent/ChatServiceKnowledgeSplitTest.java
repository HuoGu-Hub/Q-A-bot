package com.example.qqbot.agent;

import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.kb.KbCorpus;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.trace.KbTrace;
import com.example.qqbot.llm.LlmRouter;
import com.example.qqbot.media.ImageFetcher;
import com.example.qqbot.onebot.model.OneBotEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「资料分段注入」的验收测试。
 *
 * <p>这是整套"忠于自己的知识库、而不是忠于 Wiki"在**提示词层面**的落点：
 * <ul>
 *   <li>人工核对过的（{@code src=curated}）→ 单独一段，标注"以它为准"；</li>
 *   <li>导入的资料（{@code source=doc}）→ 另一段，带**资料时间**，标注"可能已过期"。</li>
 * </ul>
 *
 * <p>要断言的不是"回复好不好看"，而是**模型到底看到了什么**：
 * 两段有没有分开、人工段是不是排在前面、Wiki 段有没有带上日期。
 */
class ChatServiceKnowledgeSplitTest {

    /** 条目里的向量在提示词测试里用不到，给个占位 */
    private static final float[] V = {1f, 0f};

    private LlmRouter llmRouter;
    private KbRetriever kbRetriever;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        kbRetriever = mock(KbRetriever.class);
        when(llmRouter.isAvailable()).thenReturn(true);
        when(llmRouter.chat(anyString())).thenReturn("好的");

        chatService = new ChatService(llmRouter, mock(ImageFetcher.class), new GuardProperties().getContentGate(),
                new MediaProperties(), kbRetriever, new KbProperties());
    }

    private String userMessageSeenByModel() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llmRouter).chat(captor.capture());
        return captor.getValue();
    }

    private static OneBotEvent privateEvent() {
        OneBotEvent event = mock(OneBotEvent.class);
        when(event.isGroupMessage()).thenReturn(false);
        return event;
    }

    @Test
    @DisplayName("★ 我们自己维护的那段与导入的资料段分开，且前者在前")
    void splitsCuratedAndWiki() {
        KbCorpus.Entry curated = new KbCorpus.Entry("scrap-cup-0", "废料杯", "废料杯由 5 个废料合成。",
                "https://w/Scrap_Cup", List.of(), true, "2026-09-27T10:00:00Z", V);
        KbCorpus.Entry wiki = new KbCorpus.Entry("flame-altar-0", "Flame Altar",
                "Flame Altar is a respawn point.",
                "https://w/Flame_Altar", List.of(), false, "2026-03-04T19:52:31Z", V);
        when(kbRetriever.retrieve(anyString())).thenReturn(new KbRetriever.Retrieval(
                List.of(new KbRetriever.Hit(curated, 1.0, "vector"),
                        new KbRetriever.Hit(wiki, 0.9, "vector")),
                0.9, 0.9, 2, 0, List.of()));

        chatService.reply(privateEvent(), "废料杯怎么做", null, List.of());

        String msg = userMessageSeenByModel();
        assertThat(msg).contains("【我们维护的内容】").contains("【导入的资料");
        assertThat(msg).contains("废料杯由 5 个废料合成。").contains("Flame Altar is a respawn point.");
        assertThat(msg.indexOf("【我们维护的内容】"))
                .as("我们维护的排在导入的资料前面（模型的注意力有先后）")
                .isLessThan(msg.indexOf("【导入的资料"));
        // ⚠️ 作用范围：这条只管 **ChatService 拼出来的注入文本**。
        //    系统提示词（AGENTS.md）里的措辞由 SystemPromptTest 守 —— 两处都要守，
        //    因为"以它为准"那种权威宣称放哪一处都会生效。
        assertThat(msg).as("★ 注入文本里不能出现「以它为准」这类权威宣称：改过 ≠ 一定对")
                .doesNotContain("以它为准")
                .doesNotContain("最可信");
        assertThat(msg).as("Wiki 段必须带上资料时间，模型才有依据判断新旧")
                .contains("资料时间 2026-03-04");
    }

    @Test
    @DisplayName("只有 Wiki 资料时不出现空的「已人工核对」段")
    void noCuratedSectionWhenNone() {
        KbCorpus.Entry wiki = new KbCorpus.Entry("absorb-0", "Absorb", "Absorb is a skill.",
                "https://w/Absorb", List.of(), false, "2026-09-21T10:00:00Z", V);
        when(kbRetriever.retrieve(anyString())).thenReturn(new KbRetriever.Retrieval(
                List.of(new KbRetriever.Hit(wiki, 0.9, "vector")), 0.9, 0.9, 1, 0, List.of()));

        chatService.reply(privateEvent(), "Absorb 是什么", null, List.of());

        String msg = userMessageSeenByModel();
        assertThat(msg).contains("【导入的资料 · 资料时间 2026-09-21】");
        assertThat(msg).doesNotContain("【我们维护的内容】");
    }

    @Test
    @DisplayName("多个 Wiki 块时间不同 → 显示时间跨度")
    void showsDateRange() {
        KbCorpus.Entry a = new KbCorpus.Entry("a-0", "A", "a", "https://w/A",
                List.of(), false, "2026-09-20T10:00:00Z", V);
        KbCorpus.Entry b = new KbCorpus.Entry("b-0", "B", "b", "https://w/B",
                List.of(), false, "2026-09-21T10:00:00Z", V);
        when(kbRetriever.retrieve(anyString())).thenReturn(new KbRetriever.Retrieval(
                List.of(new KbRetriever.Hit(a, 1.0, "vector"), new KbRetriever.Hit(b, 0.9, "vector")),
                0.9, 0.9, 2, 0, List.of()));

        chatService.reply(privateEvent(), "问题", null, List.of());

        assertThat(userMessageSeenByModel()).contains("资料时间 2026-09-20 ~ 2026-09-21");
    }

    @Test
    @DisplayName("旧语料没有 at 这一列 → 老实说「时间未知」，不编一个日期")
    void unknownDateWhenMissing() {
        KbCorpus.Entry old = new KbCorpus.Entry("legacy-0", "Legacy", "legacy text", "https://w/L",
                List.of(), false, "", V);
        when(kbRetriever.retrieve(anyString())).thenReturn(new KbRetriever.Retrieval(
                List.of(new KbRetriever.Hit(old, 0.9, "vector")), 0.9, 0.9, 1, 0, List.of()));

        chatService.reply(privateEvent(), "问题", null, List.of());

        assertThat(userMessageSeenByModel()).contains("【导入的资料 · 时间未知】");
    }

    @Test
    @DisplayName("「不用知识库」模式：一次检索都不做，模型也不会看到资料块")
    void noneModeSkipsRetrievalEntirely() {
        chatService.reply(privateEvent(), "今天什么版本", null, List.of(), ReplyMode.NONE);

        String msg = userMessageSeenByModel();
        assertThat(msg).doesNotContain("KNOWLEDGE");
        assertThat(msg).contains("不使用知识库");
        verify(kbRetriever, org.mockito.Mockito.never()).retrieve(anyString());
    }
}
