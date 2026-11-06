package com.example.qqbot.router;

import com.example.qqbot.agent.ChatService;
import com.example.qqbot.config.AsyncProperties;
import com.example.qqbot.agent.Quote;
import com.example.qqbot.agent.ReplyMode;
import com.example.qqbot.guard.Access;
import com.example.qqbot.guard.BlockNotifier;
import com.example.qqbot.guard.BudgetGuard;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardPipeline;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.guard.RateLimit;
import com.example.qqbot.intent.IntentPlanner;
import com.example.qqbot.intent.TurnPlan;
import com.example.qqbot.onebot.BotIdentity;
import com.example.qqbot.onebot.client.OneBotApiClient;
import com.example.qqbot.onebot.codec.MessageCodec;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.onebot.outbound.OutboundSender;
import com.example.qqbot.qa.QaCollector;
import com.example.qqbot.trace.KbTrace;
import com.example.qqbot.trigger.ReplyIntent;
import com.example.qqbot.trigger.ReplyTrigger;
import com.example.qqbot.trigger.TriggerDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 概率回复的接线：呼叫直通、主动接话用 chime 计划、记 auto、引用只取一次。
 */
class MessageRouterTriggerTest {

    private static final long GROUP = 209127588L;
    private static final long USER = 200L;

    private final ObjectMapper mapper = new ObjectMapper();

    private OneBotApiClient apiClient;
    private MessageCodec codec;
    private BotIdentity identity;
    private ChatService chatService;
    private GuardPipeline guardPipeline;
    private OutboundSender outboundSender;
    private BudgetGuard budgetGuard;
    private QaCollector qaCollector;
    private CommandHandler commandHandler;
    private IntentPlanner intentPlanner;
    private ReplyTrigger replyTrigger;
    private Access access;
    private MessageRouter router;

    @BeforeEach
    void setUp() {
        apiClient = mock(OneBotApiClient.class);
        codec = mock(MessageCodec.class);
        identity = mock(BotIdentity.class);
        chatService = mock(ChatService.class);
        guardPipeline = mock(GuardPipeline.class);
        outboundSender = mock(OutboundSender.class);
        budgetGuard = mock(BudgetGuard.class);
        qaCollector = mock(QaCollector.class);
        commandHandler = mock(CommandHandler.class);
        intentPlanner = mock(IntentPlanner.class);
        replyTrigger = mock(ReplyTrigger.class);

        access = new Access();
        access.setRequireMentionInGroup(false);

        when(identity.isSelf(any())).thenReturn(false);
        when(identity.getSelfId()).thenReturn(999L);
        when(identity.getSelfName()).thenReturn("飘雪喵");
        when(codec.extractImageRefs(any(OneBotEvent.class))).thenReturn(List.of());
        when(codec.extractImageRefs(any(JsonNode.class))).thenReturn(List.of());
        when(codec.extractReplyId(any())).thenReturn(Optional.empty());
        when(codec.isMentioned(any(), anyLong())).thenReturn(false);
        when(codec.hasAtOther(any(), anyLong())).thenReturn(false);

        when(commandHandler.tryCommand(any(), anyString(), anyLong()))
                .thenReturn(CommandHandler.Outcome.NOT_COMMAND);
        when(guardPipeline.isEnabled()).thenReturn(true);
        when(guardPipeline.check(any())).thenReturn(GuardResult.pass());
        when(budgetGuard.check(anyLong(), anyLong(), anyBoolean()))
                .thenReturn(new BudgetGuard.Decision(BudgetGuard.Verdict.OK, null));
        when(intentPlanner.plan(anyString())).thenReturn(TurnPlan.standard());
        when(chatService.reply(any(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new ChatService.ReplyResult("好", 10L, KbTrace.disabled()));
        when(outboundSender.send(any(), anyString())).thenReturn("好");

        router = new MessageRouter(apiClient, codec, identity, chatService, guardPipeline,
                outboundSender, budgetGuard, new AsyncProperties(), new RateLimit(), access,
                mock(BlockNotifier.class), qaCollector, commandHandler, null, null, intentPlanner,
                replyTrigger, null);
    }

    @Test
    @DisplayName("疑问被概率命中：走 chime 计划，记 auto，不算追问")
    void questionChimeRecordsAuto() {
        OneBotEvent event = groupEvent("雾锁的钥匙怎么拿");
        when(replyTrigger.decide(eq(GROUP), eq(USER), eq("雾锁的钥匙怎么拿"), eq(false), eq(false)))
                .thenReturn(TriggerDecision.yes(ReplyIntent.QUESTION, 0.3, 0.1));

        router.handle(event);

        ArgumentCaptor<TurnPlan> plan = ArgumentCaptor.forClass(TurnPlan.class);
        verify(chatService).reply(eq(event), eq("雾锁的钥匙怎么拿"), any(), any(),
                any(ReplyMode.class), eq(false), plan.capture());
        assertThat(plan.getValue().chimeIn()).isTrue();
        assertThat(plan.getValue().retrieve()).isTrue();

        verify(qaCollector).collect(eq(event), eq("雾锁的钥匙怎么拿"), any(), eq(0),
                eq("auto"), any(), any(), anyLong(), anyLong(), eq(false));
        verify(outboundSender).send(eq(event), eq("好"));
    }

    @Test
    @DisplayName("正文含昵称：直接算呼叫，不调概率判定")
    void nameCallBypassesProbability() {
        OneBotEvent event = groupEvent("飘雪喵 这个在哪");

        router.handle(event);

        verify(replyTrigger, never()).decide(anyLong(), anyLong(), anyString(), anyBoolean(), anyBoolean());
        ArgumentCaptor<GuardContext> ctx = ArgumentCaptor.forClass(GuardContext.class);
        verify(guardPipeline).check(ctx.capture());
        assertThat(ctx.getValue().trigger()).isNotNull();
        assertThat(ctx.getValue().trigger().intent()).isEqualTo(ReplyIntent.CALL);
        assertThat(ctx.getValue().trigger().reason()).isEqualTo("正文昵称");

        verify(qaCollector).collect(eq(event), eq("飘雪喵 这个在哪"), any(), eq(0),
                eq("pass"), any(), any(), anyLong(), anyLong(), eq(true));
    }

    @Test
    @DisplayName("引用机器人：先取一次消息，判定与回复共用")
    void quoteCallResolvesOnce() {
        OneBotEvent event = groupEvent("这个呢");
        when(codec.extractReplyId(event)).thenReturn(Optional.of("42"));
        ObjectNode quoted = mapper.createObjectNode();
        quoted.putObject("sender").put("user_id", 999).put("nickname", "飘雪喵");
        when(apiClient.getMsg("42")).thenReturn(quoted);
        when(identity.isSelf(999L)).thenReturn(true);
        when(codec.extractPlainText(any(JsonNode.class))).thenReturn("旧回答");

        router.handle(event);

        verify(apiClient, times(1)).getMsg("42");
        verify(replyTrigger, never()).decide(anyLong(), anyLong(), anyString(), anyBoolean(), anyBoolean());

        ArgumentCaptor<Quote> quote = ArgumentCaptor.forClass(Quote.class);
        verify(chatService).reply(eq(event), eq("这个呢"), quote.capture(), any(),
                any(ReplyMode.class), eq(false), any());
        assertThat(quote.getValue().text()).isEqualTo("旧回答");
        assertThat(quote.getValue().fromSelf()).isTrue();
    }

    @Test
    @DisplayName("Guard 关闭：概率判定不生效，仍按普通回答处理")
    void guardDisabledDisablesChime() {
        when(guardPipeline.isEnabled()).thenReturn(false);
        OneBotEvent event = groupEvent("雾锁的钥匙怎么拿");
        when(replyTrigger.decide(eq(GROUP), eq(USER), eq("雾锁的钥匙怎么拿"), eq(false), eq(false)))
                .thenReturn(TriggerDecision.yes(ReplyIntent.QUESTION, 0.3, 0.1));

        router.handle(event);

        ArgumentCaptor<TurnPlan> plan = ArgumentCaptor.forClass(TurnPlan.class);
        verify(chatService).reply(eq(event), eq("雾锁的钥匙怎么拿"), any(), any(),
                any(ReplyMode.class), eq(false), plan.capture());
        assertThat(plan.getValue().chimeIn()).isFalse();
        verify(qaCollector).collect(eq(event), eq("雾锁的钥匙怎么拿"), any(), eq(0),
                eq("pass"), any(), any(), anyLong(), anyLong(), eq(true));
    }

    private OneBotEvent groupEvent(String text) {
        OneBotEvent event = mock(OneBotEvent.class);
        when(event.isMessageEvent()).thenReturn(true);
        when(event.isGroupMessage()).thenReturn(true);
        when(event.getGroupId()).thenReturn(GROUP);
        when(event.getUserId()).thenReturn(USER);
        when(codec.extractPlainText(event)).thenReturn(text);
        return event;
    }
}
