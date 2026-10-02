package com.example.qqbot.router;

import com.example.qqbot.agent.ChatService;
import com.example.qqbot.agent.ReplyMode;
import com.example.qqbot.command.BotCommand;
import com.example.qqbot.command.CommandMatcher;
import com.example.qqbot.command.CommandRateLimiter;
import com.example.qqbot.command.CommandStore;
import com.example.qqbot.command.VariableRenderer;
import com.example.qqbot.config.AsyncProperties;
import com.example.qqbot.config.CommandProperties;
import com.example.qqbot.guard.Access;
import com.example.qqbot.guard.Outbound;
import com.example.qqbot.guard.RateLimit;
import com.example.qqbot.guard.BlockNotifier;
import com.example.qqbot.guard.BudgetGuard;
import com.example.qqbot.guard.GuardPipeline;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.guard.OutboundFilter;
import com.example.qqbot.guard.OutboundPacer;
import com.example.qqbot.onebot.outbound.OutboundSender;
import com.example.qqbot.trace.KbTrace;
import com.example.qqbot.onebot.BotIdentity;
import com.example.qqbot.onebot.client.OneBotApiClient;
import com.example.qqbot.onebot.codec.MessageCodec;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.plaza.FallbackService;
import com.example.qqbot.qa.QaCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「指令升级」的**安全契约**验收测试。
 *
 * <p>要守住的核心只有一条：
 * <b>会调模型的指令（agent）必须走 Guard 与成本预算；不调模型的（template）才可以秒回。</b>
 *
 * <p>为什么这条最要命：指令原本跑在 Guard <b>之前</b>、绕过限流与预算 ——
 * 那是为 {@code /help} 这种零成本话术设计的。如果直接让 agent 指令沿用这条快路径，
 * 就等于开了一个**不限量、不计费的模型调用入口**，成本会被打穿。
 *
 * <p>所以这里断言的不是"回复好看不好看"，而是**调用链有没有被绕过**：
 * {@code guardPipeline.check()} 和 {@code budgetGuard.check()} 到底有没有被调到。
 */
class MessageRouterCommandTest {

    private static final long GROUP = 100L;
    private static final long USER = 200L;

    private final ObjectMapper mapper = new ObjectMapper();

    private OneBotApiClient apiClient;
    private MessageCodec codec;
    private BotIdentity identity;
    private ChatService chatService;
    private GuardPipeline guardPipeline;
    private OutboundFilter outboundFilter;
    private OutboundPacer outboundPacer;
    private OutboundSender outboundSender;
    private BudgetGuard budgetGuard;
    private QaCollector qaCollector;
    private CommandMatcher commandMatcher;
    private CommandRateLimiter commandRateLimiter;
    private CommandStore commandStore;
    private VariableRenderer variableRenderer;

    private MessageRouter router;

    @BeforeEach
    void setUp() {
        apiClient = mock(OneBotApiClient.class);
        codec = mock(MessageCodec.class);
        identity = mock(BotIdentity.class);
        chatService = mock(ChatService.class);
        guardPipeline = mock(GuardPipeline.class);
        outboundFilter = mock(OutboundFilter.class);
        outboundPacer = mock(OutboundPacer.class);
        budgetGuard = mock(BudgetGuard.class);
        qaCollector = mock(QaCollector.class);
        commandMatcher = mock(CommandMatcher.class);
        commandRateLimiter = mock(CommandRateLimiter.class);
        commandStore = mock(CommandStore.class);
        variableRenderer = mock(VariableRenderer.class);

        when(identity.isSelf(any())).thenReturn(false);
        when(identity.getSelfId()).thenReturn(999L);
        when(codec.extractImageRefs(any(OneBotEvent.class))).thenReturn(List.of());
        when(codec.extractReplyId(any())).thenReturn(Optional.empty());
        when(codec.isMentioned(any(), anyLong())).thenReturn(true);
        // 把发出的消息内容"折"进 JsonNode，便于断言到底回了什么
        when(codec.atPlusText(anyLong(), anyString()))
                .thenAnswer(inv -> mapper.createObjectNode().put("text", (String) inv.getArgument(1)));
        when(codec.textMessage(anyString()))
                .thenAnswer(inv -> mapper.createObjectNode().put("text", (String) inv.getArgument(0)));

        when(outboundPacer.split(anyString()))
                .thenAnswer(inv -> java.util.Collections.singletonList((String) inv.getArgument(0)));
        when(outboundPacer.reserveGroupSlot(anyLong())).thenReturn(0L);
        when(outboundFilter.filter(anyString())).thenAnswer(inv -> inv.getArgument(0));

        when(commandRateLimiter.allow(anyString())).thenReturn(true);
        when(guardPipeline.check(any())).thenReturn(GuardResult.pass());
        when(budgetGuard.check(anyLong(), anyLong()))
                .thenReturn(new BudgetGuard.Decision(BudgetGuard.Verdict.OK, null));
        when(chatService.reply(any(), anyString(), any(), any(), any(ReplyMode.class)))
                .thenReturn(new ChatService.ReplyResult("模型回答", 12L, KbTrace.disabled()));

        // 真的 OutboundSender（而不是 mock）：这个测试要断言"到底发出去了什么"，
        // 发送逻辑被 mock 掉就没得断了。它内部只用到下面这几个 mock。
        outboundSender = new OutboundSender(apiClient, codec, outboundPacer, identity, new Outbound(), outboundFilter);
        router = new MessageRouter(apiClient, codec, identity, chatService, guardPipeline,
                outboundSender, budgetGuard, new AsyncProperties(), new RateLimit(), new Access(),
                mock(BlockNotifier.class), qaCollector, new CommandProperties(), commandMatcher,
                commandRateLimiter, commandStore, variableRenderer, mock(FallbackService.class));
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

    /** 命中一条指令时，matcher 返回的东西 */
    private void matchCommand(BotCommand cmd, String args) {
        when(commandMatcher.match(anyString(), anyBoolean(), anyBoolean(), anyLong()))
                .thenReturn(new CommandMatcher.Match(cmd, true, cmd.trigger(), args));
    }

    private String sentText() {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(apiClient).sendGroupMsg(eq(GROUP), captor.capture());
        return captor.getValue().path("text").asText();
    }

    // ==================== template：秒回，不碰 Guard / 预算 / 模型 ====================

    @Test
    @DisplayName("★ 话术指令：直接回，不碰 Guard、不碰预算、不调模型")
    void templateCommandStaysOnFastPath() {
        BotCommand ping = new BotCommand(1, "ping", "在的喵～", "看看我在不在",
                "all", List.of(), "member", true, 1, true,
                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB);
        matchCommand(ping, "");
        when(variableRenderer.render(eq("在的喵～"), any(), anyBoolean(), anyString())).thenReturn("在的喵～");

        router.handle(groupEvent("/ping"));

        assertThat(sentText()).isEqualTo("在的喵～");
        verify(guardPipeline, never()).check(any());
        verify(budgetGuard, never()).check(anyLong(), anyLong());
        verify(chatService, never()).reply(any(), anyString(), any(), any(), any(ReplyMode.class));
    }

    @Test
    @DisplayName("话术指令能用 {args} —— 小活动靠它（/报名 张三）")
    void templateCommandReceivesArgs() {
        BotCommand signup = new BotCommand(2, "报名", "已记录：{args}", "活动报名",
                "all", List.of(), "member", true, 2, false,
                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB);
        matchCommand(signup, "张三");
        when(variableRenderer.render(eq("已记录：{args}"), any(), anyBoolean(), eq("张三")))
                .thenReturn("已记录：张三");

        router.handle(groupEvent("/报名 张三"));

        assertThat(sentText()).isEqualTo("已记录：张三");
        verify(variableRenderer).render(eq("已记录：{args}"), any(), anyBoolean(), eq("张三"));
    }

    // ==================== agent：必须走 Guard + 预算 ====================

    @Test
    @DisplayName("★ 智能问答指令：走 Guard、走预算，参数当问题交给模型")
    void agentCommandGoesThroughGuardAndBudget() {
        BotCommand web = new BotCommand(3, "联网", "用法：/联网 问题", "用实时资料回答",
                "all", List.of(), "member", true, 3, false,
                BotCommand.KIND_AGENT, BotCommand.MODE_NONE);
        matchCommand(web, "今天什么版本");

        router.handle(groupEvent("/联网 今天什么版本"));

        verify(guardPipeline, times(1)).check(any());
        verify(budgetGuard, times(1)).check(eq(USER), eq(GROUP));
        // 关键：传给模型的是**参数**（问题），模式是命令配的 none
        verify(chatService).reply(any(), eq("今天什么版本"), any(), any(), eq(ReplyMode.NONE));
        assertThat(sentText()).isEqualTo("模型回答");
    }

    @Test
    @DisplayName("★ 预算用尽时 agent 指令被拦下 —— 模型一次都没调")
    void budgetLimitBlocksAgentCommand() {
        BotCommand web = new BotCommand(4, "联网", "用法：/联网 问题", "说明",
                "all", List.of(), "member", true, 4, false,
                BotCommand.KIND_AGENT, BotCommand.MODE_NONE);
        matchCommand(web, "今天什么版本");
        when(budgetGuard.check(anyLong(), anyLong()))
                .thenReturn(new BudgetGuard.Decision(BudgetGuard.Verdict.USER_LIMIT, "今天的额度用完了"));

        router.handle(groupEvent("/联网 今天什么版本"));

        verify(chatService, never()).reply(any(), anyString(), any(), any(), any(ReplyMode.class));
        assertThat(sentText()).isEqualTo("今天的额度用完了");
    }

    @Test
    @DisplayName("agent 指令不带参数：回一句用法提示，同样不碰 Guard / 预算 / 模型")
    void agentCommandWithoutArgsRepliesUsage() {
        BotCommand web = new BotCommand(5, "联网", "用法：/联网 问题内容", "说明",
                "all", List.of(), "member", true, 5, false,
                BotCommand.KIND_AGENT, BotCommand.MODE_NONE);
        matchCommand(web, "");
        when(variableRenderer.render(eq("用法：/联网 问题内容"), any(), anyBoolean(), anyString()))
                .thenReturn("用法：/联网 问题内容");

        router.handle(groupEvent("/联网"));

        assertThat(sentText()).isEqualTo("用法：/联网 问题内容");
        verify(guardPipeline, never()).check(any());
        verify(budgetGuard, never()).check(anyLong(), anyLong());
        verify(chatService, never()).reply(any(), anyString(), any(), any(), any(ReplyMode.class));
    }

    @Test
    @DisplayName("agent 指令被 Guard 拦下时，不调模型（Guard 真的在它前面）")
    void guardDropPreventsAgentModelCall() {
        BotCommand web = new BotCommand(6, "联网", "用法", "说明",
                "all", List.of(), "member", true, 6, false,
                BotCommand.KIND_AGENT, BotCommand.MODE_KB);
        matchCommand(web, "某个问题");
        when(guardPipeline.check(any())).thenReturn(GuardResult.drop("rate-limit", "太快了"));

        router.handle(groupEvent("/联网 某个问题"));

        verify(chatService, never()).reply(any(), anyString(), any(), any(), any(ReplyMode.class));
        verify(apiClient, never()).sendGroupMsg(anyLong(), any());
    }

    @Test
    @DisplayName("指令被限流时不回复（静默丢弃），也不调模型")
    void commandRateLimitSilencesEverything() {
        BotCommand web = new BotCommand(7, "联网", "用法", "说明",
                "all", List.of(), "member", true, 7, false,
                BotCommand.KIND_AGENT, BotCommand.MODE_KB);
        matchCommand(web, "问题");
        when(commandRateLimiter.allow(anyString())).thenReturn(false);

        router.handle(groupEvent("/联网 问题"));

        verify(apiClient, never()).sendGroupMsg(anyLong(), any());
        verify(chatService, never()).reply(any(), anyString(), any(), any(), any(ReplyMode.class));
    }
}
