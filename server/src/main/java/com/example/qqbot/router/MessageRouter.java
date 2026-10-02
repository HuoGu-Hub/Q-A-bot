package com.example.qqbot.router;

import com.example.qqbot.agent.ChatService;
import com.example.qqbot.agent.ReplyMode;
import com.example.qqbot.config.AsyncProperties;
import com.example.qqbot.config.GuardProperties.Access;
import com.example.qqbot.config.GuardProperties.RateLimit;
import com.example.qqbot.guard.BlockNotifier;
import com.example.qqbot.guard.BudgetGuard;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardPipeline;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.onebot.outbound.OutboundSender;
import com.example.qqbot.onebot.BotIdentity;
import com.example.qqbot.onebot.client.OneBotApiClient;
import com.example.qqbot.onebot.codec.MessageCodec;
import com.example.qqbot.command.BotCommand;
import com.example.qqbot.command.CommandPolicy;
import com.example.qqbot.command.CommandMatcher;
import com.example.qqbot.command.CommandRateLimiter;
import com.example.qqbot.command.CommandStore;
import com.example.qqbot.command.VariableRenderer;
import com.example.qqbot.plaza.FallbackService;
import com.example.qqbot.trace.KbTrace;
import com.example.qqbot.onebot.model.ImageRef;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.qa.QaCollector;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 路由：把一条事件走完「排队检查 → 安全判定 → 生成回复 → 发出去」。
 *
 * <pre>
 *   事件 ─▶ 排队太久？──是──▶ 丢弃 + 回一句「刚才太挤了」
 *              │否
 *              ▼
 *         GuardPipeline ──┬─ Drop  ─▶ 什么都不做
 *                         ├─ Reply ─▶ 直接发固定话术（不调模型）
 *                         └─ Pass  ─▶ 解析引用 ─▶ ChatService ─▶ 出站过滤 ─▶ 发送
 * </pre>
 */
@Component
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final OneBotApiClient apiClient;
    private final MessageCodec codec;
    private final BotIdentity identity;
    private final ChatService chatService;
    private final GuardPipeline guardPipeline;
    private final OutboundSender outboundSender;
    private final BudgetGuard budgetGuard;
    private final AsyncProperties asyncProperties;
    private final RateLimit guardRateLimit;
    private final Access guardAccess;
    private final BlockNotifier blockNotifier;
    private final QaCollector qaCollector;
    private final CommandPolicy commandProperties;
    private final CommandMatcher commandMatcher;
    private final CommandRateLimiter commandRateLimiter;
    private final CommandStore commandStore;
    private final VariableRenderer variableRenderer;
    private final FallbackService fallbackService;

    public MessageRouter(OneBotApiClient apiClient,
                         MessageCodec codec,
                         BotIdentity identity,
                         ChatService chatService,
                         GuardPipeline guardPipeline,
                         OutboundSender outboundSender,
                         BudgetGuard budgetGuard,
                         AsyncProperties asyncProperties,
                         RateLimit guardRateLimit,
                         Access guardAccess,
                         BlockNotifier blockNotifier,
                         QaCollector qaCollector,
                         CommandPolicy commandProperties,
                         CommandMatcher commandMatcher,
                         CommandRateLimiter commandRateLimiter,
                         CommandStore commandStore,
                         VariableRenderer variableRenderer,
                         FallbackService fallbackService) {
        this.apiClient = apiClient;
        this.codec = codec;
        this.identity = identity;
        this.chatService = chatService;
        this.guardPipeline = guardPipeline;
        this.outboundSender = outboundSender;
        this.budgetGuard = budgetGuard;
        this.asyncProperties = asyncProperties;
        this.guardRateLimit = guardRateLimit;
        this.guardAccess = guardAccess;
        this.blockNotifier = blockNotifier;
        this.qaCollector = qaCollector;
        this.commandProperties = commandProperties;
        this.commandMatcher = commandMatcher;
        this.commandRateLimiter = commandRateLimiter;
        this.commandStore = commandStore;
        this.variableRenderer = variableRenderer;
        this.fallbackService = fallbackService;
    }

    /**
     * 被引用消息的内容。
     *
     * <p>注意：**引用消息里的图片同样要取出来**。
     * 群里很常见的用法是「引用一张图 + 打字提问」，被引用的消息本身没有任何文字，
     * 如果只提取文字就会得到空值，图片被完全丢掉。
     */
    private record QuotedContent(String text, List<ImageRef> imageRefs) {
        static final QuotedContent EMPTY = new QuotedContent(null, List.of());
    }

    /**
     * 异步处理入口。
     *
     * @param enqueuedAt 任务**提交前**的时间戳（由 Controller 传进来）
     */
    @Async("botExecutor")
    public void handleAsync(OneBotEvent event, long enqueuedAt) {
        long waitedMs = System.currentTimeMillis() - enqueuedAt;
        long limitMs = asyncProperties.getMaxQueueWaitSeconds() * 1000L;
        if (waitedMs > limitMs) {
            log.warn("[ASYNC] 事件在队列里等了 {} ms（上限 {} s），已丢弃：{}",
                    waitedMs, asyncProperties.getMaxQueueWaitSeconds(), event);
            notifyQueueTimeout(event);
            return;
        }
        try {
            handle(event);
        } catch (Exception e) {
            log.error("处理事件失败：{}", event, e);
        }
    }

    /** 排队超时被丢弃时，礼貌地告诉用户一声（同样受提示限流保护） */
    private void notifyQueueTimeout(OneBotEvent event) {
        if (!event.isMessageEvent() || identity.isSelf(event.getUserId())) {
            return;
        }
        if (!blockNotifier.allow(event.getUserId(), event.getGroupId())) {
            return;
        }
        outboundSender.send(event, guardRateLimit.getQueueTimeoutText());
    }

    /**
     * 准入控制：只看黑名单，不看别的东西。
     *
     * <p>单独抽出来是因为**指令必须在 Guard 之前跑**，但又不能完全绕开黑名单 ——
     * 被拉黑的人不该能触发指令。
     */
    private boolean accessAllowed(OneBotEvent event) {
        Access access = guardAccess;
        if (event.isGroupMessage()) {
            long gid = event.getGroupId() == null ? 0 : event.getGroupId();
            if (access.getGroupBlacklist() != null && access.getGroupBlacklist().contains(gid)) {
                return false;
            }
        }
        long uid = event.getUserId() == null ? 0 : event.getUserId();
        return access.getUserBlacklist() == null || !access.getUserBlacklist().contains(uid);
    }

    /**
     * {@link #tryCommand} 的结果 —— 三种走向。
     *
     * <p><b>为什么不是 boolean</b>：智能问答类指令（agent）**不能**在这里回完就返回。
     * 它会真的调模型，所以必须继续往下走 Guard 与成本预算 ——
     * 原来那套"指令跑在 Guard 之前、绕过限流和预算"是为 {@code /help} 这种
     * 零成本话术设计的，套到调模型的指令上等于开了一个不限量的免费额度。
     */
    private record CommandOutcome(boolean handled, ReplyMode mode, String question) {

        /** 不是指令 */
        static final CommandOutcome NOT_COMMAND = new CommandOutcome(false, ReplyMode.KB, null);
        /** 已经在这里回完了 */
        static final CommandOutcome HANDLED = new CommandOutcome(true, ReplyMode.KB, null);

        /** agent 指令：带上回答模式与"问题"，继续走 Guard + 预算 */
        static CommandOutcome agent(ReplyMode mode, String question) {
            return new CommandOutcome(false, mode, question);
        }
    }

    /**
     * 尝试把消息当作指令处理。
     *
     * <p>两类走法完全不同：
     * <ul>
     *   <li>{@code template}（话术）—— 就地渲染回复、秒回，不调模型、不计费；</li>
     *   <li>{@code agent}（智能问答）—— 只登记，返回"带着模式和问题继续往下走"。</li>
     * </ul>
     *
     * @return 见 {@link CommandOutcome}
     */
    private CommandOutcome tryCommand(OneBotEvent event, String text, long t0) {
        if (!commandProperties.isEnabled()) {
            return CommandOutcome.NOT_COMMAND;
        }
        boolean inGroup = event.isGroupMessage();
        long groupId = event.getGroupId() == null ? 0 : event.getGroupId();
        long userId = event.getUserId() == null ? 0 : event.getUserId();

        // 群里是否 @ 了机器人
        boolean mentioned = !inGroup || codec.isMentioned(event, identity.getSelfId());

        CommandMatcher.Match match = commandMatcher.match(text, mentioned, inGroup, groupId);

        if (!match.hit()) {
            // 看起来像指令但没匹配上 —— 记一笔，用来发现"该加哪些命令"
            if (match.looksLike()) {
                // 群里的统计只记 @ 过的（否则正常聊天里的斜杠也会被记进来）
                if (!inGroup || mentioned) {
                    commandStore.logUsage(match.rawTrigger(), groupId, userId, false);
                }
            }
            return CommandOutcome.NOT_COMMAND;
        }

        BotCommand cmd = match.command();
        String args = match.argsOrEmpty();

        // 指令级限流（独立于问答限流）
        String key = userId + "@" + groupId;
        if (!commandRateLimiter.allow(key)) {
            log.info("[CMD] 指令被限流：{} 触发 /{}", userId, cmd.trigger());
            return CommandOutcome.HANDLED;   // 静默丢弃，不回复（避免"提示"本身变成刷屏）
        }

        // ---------- 智能问答类：登记后继续走 Guard + 预算，不在这里回 ----------
        if (cmd.isAgent()) {
            if (args.isEmpty()) {
                // 没带参数：用配置的 reply 当"用法提示"秒回（不调模型、不计费）
                String hint = variableRenderer.render(cmd.reply(), event, false, "");
                if (hint.isBlank()) {
                    hint = "用法：" + cmd.display();
                }
                outboundSender.send(event, hint);
                commandStore.logUsage(cmd.trigger(), groupId, userId, true);
                qaCollector.collect(event, text, null, 0, "command", hint,
                        KbTrace.disabled(), 0, System.currentTimeMillis() - t0);
                return CommandOutcome.HANDLED;
            }
            log.info("[CMD] 智能问答指令 /{}（模式={}，参数 {} 字）→ 继续走 Guard + 成本预算",
                    cmd.trigger(), cmd.modeName(), args.length());
            commandStore.logUsage(cmd.trigger(), groupId, userId, true);
            return CommandOutcome.agent(ReplyMode.from(cmd.modeName()), args);
        }

        // ---------- 话术类：渲染模板就地回 ----------
        String reply = variableRenderer.render(cmd.reply(), event, false, args);
        if (reply.isBlank()) {
            log.warn("[CMD] 指令 /{} 渲染后为空，跳过", cmd.trigger());
            return CommandOutcome.HANDLED;
        }

        log.info("[CMD] 触发指令 /{}（群 {} 用户 {}），回复 {} 字",
                cmd.trigger(), groupId, userId, reply.length());
        outboundSender.send(event, reply);
        commandStore.logUsage(cmd.trigger(), groupId, userId, true);

        // 指令不算问答检索，但记一笔便于统计"指令用了多少次"
        qaCollector.collect(event, text, null, 0, "command", reply,
                KbTrace.disabled(), 0, System.currentTimeMillis() - t0);
        return CommandOutcome.HANDLED;
    }

    /**
     * 处理广场的「求助」——
     *
     * <p>用户在公开站点了「求助大佬」，网页给出一句「求助：xxx」，
     * 他复制到群里 @ 机器人，我们就在**这个群**里把问题发出来。
     *
     * <p>为什么绕这么一圈：网页上填群号是不可信的（能填任意群 = 任意群广播漏洞）。
     * 从群聊上下文取群号，天然保证「只发到他自己所在的群」。
     *
     * @return true = 已处理
     */
    private boolean tryHelpRequest(OneBotEvent event, String text) {
        if (!event.isGroupMessage() || text == null) {
            return false;
        }
        String t = text.trim();
        // 允许前置 @机器人
        t = t.replaceAll("^@\\S+\\s*", "").trim();
        if (!t.startsWith("求助：") && !t.startsWith("求助:")) {
            return false;
        }
        String question = t.substring(3).trim();
        if (question.isEmpty()) {
            return false;
        }
        long groupId = event.getGroupId() == null ? 0 : event.getGroupId();
        long userId = event.getUserId() == null ? 0 : event.getUserId();
        if (groupId <= 0) {
            return false;
        }

        try {
            Map<String, Object> result = fallbackService.confirmAndSendHelp(
                    groupId, userId, question);
            if (Boolean.TRUE.equals(result.get("ok"))) {
                log.info("[PLAZA] 群 {} 的求助已发出：{}", groupId, question);
            } else {
                log.info("[PLAZA] 群 {} 的求助被拒：{}", groupId, result.get("error"));
                outboundSender.send(event, String.valueOf(result.get("error")));
            }
            return true;
        } catch (Exception e) {
            log.warn("[PLAZA] 处理求助失败：{}", e.getMessage());
            return false;
        }
    }

    /** 同步处理逻辑，单独抽出来是为了方便写单元测试 */
    public void handle(OneBotEvent event) {
        if (!event.isMessageEvent()) {
            return;
        }
        // 忽略机器人自己发的消息，否则会自己跟自己说话，形成无限循环
        if (identity.isSelf(event.getUserId())) {
            return;
        }

        long t0 = System.currentTimeMillis();
        String text = codec.extractPlainText(event);
        List<ImageRef> ownImages = codec.extractImageRefs(event);
        int imageCount = ownImages.size();
        boolean hasQuote = codec.extractReplyId(event).isPresent();

        // ① 准入控制（黑名单）—— 唯一在指令之前跑的一层。
        //    被拉黑的人不该能触发指令，但除此之外指令不该受任何 Guard 限制。
        if (!accessAllowed(event)) {
            qaCollector.collect(event, text, null, imageCount, "drop", null,
                    KbTrace.disabled(), 0, System.currentTimeMillis() - t0);
            return;
        }

        // ② 指令匹配（★ 在 Guard 之前，在模型之前）
        //    · 话术类命中就秒回：不走限流、不走敏感词、不调模型。
        //      理由：/help 不该等模型 3 秒，也不该受"每分钟只能问一次"的限流。
        //    · 智能问答类**只登记**，继续往下走 Guard 与成本预算（它会真的调模型）。
        CommandOutcome cmd = tryCommand(event, text, t0);
        if (cmd.handled()) {
            return;
        }
        ReplyMode replyMode = cmd.mode();
        // agent 指令把「参数」当问题；其余情况用整条消息
        String question = cmd.question() != null ? cmd.question() : text;

        // ②.5 广场求助确认：群里发「求助：xxx」→ 发到本群
        //      ★ 关键：群号取自**群聊上下文**，不是网页传的 ——
        //        这样网页访客无法向任意群发消息。
        if (tryHelpRequest(event, text)) {
            return;
        }

        // ③ 其余 Guard 五层
        GuardContext ctx = new GuardContext(event, text,
                ownImages.stream().map(ImageRef::url).toList(), hasQuote);
        GuardResult result = guardPipeline.check(ctx);
        if (result instanceof GuardResult.Drop) {
            // 被 Guard 拦下的也记一笔 —— "多少消息被限流/拦下"本身就是运营数据
            qaCollector.collect(event, text, null, imageCount, "drop", null,
                    KbTrace.disabled(), 0, System.currentTimeMillis() - t0);
            return;
        }
        if (result instanceof GuardResult.Reply reply) {
            log.info("走固定话术（stage 拦截）：{}", reply.reason());
            outboundSender.send(event, reply.text());
            qaCollector.collect(event, text, null, imageCount, "fixed_reply", reply.text(),
                    KbTrace.disabled(), 0, System.currentTimeMillis() - t0);
            return;
        }

        // ③.5 成本预算（D6）：限流管「多快」，这里管「一天总共多少」。
        //      必须放在调模型【之前】—— 预算的意义就是别把 token 花出去。
        BudgetGuard.Decision budget = budgetGuard.check(event.getUserId(), event.getGroupId());
        if (budget.verdict() == BudgetGuard.Verdict.USER_LIMIT) {
            outboundSender.send(event, budget.text());
            qaCollector.collect(event, text, null, imageCount, "fixed_reply", budget.text(),
                    KbTrace.disabled(), 0, System.currentTimeMillis() - t0);
            return;
        }
        if (budget.verdict() != BudgetGuard.Verdict.OK) {
            // 群级超限 = 该群静默；全局熔断 = 谁都不回（BudgetGuard 里已经 ERROR 告警过）
            log.info("[BUDGET] 丢弃消息：verdict={} group={} user={}",
                    budget.verdict(), event.getGroupId(), event.getUserId());
            qaCollector.collect(event, text, null, imageCount, "drop", null,
                    KbTrace.disabled(), 0, System.currentTimeMillis() - t0);
            return;
        }
        budgetGuard.recordCall(event.getUserId(), event.getGroupId());

        // 解析引用（QQ 的引用段只有 ID，正文和图片都要另外调 get_msg 取）
        QuotedContent quote = resolveQuote(event);

        // 消息自带的图片 + 被引用消息里的图片，一起交给模型
        List<ImageRef> allImages = new ArrayList<>(ownImages);
        allImages.addAll(quote.imageRefs());

        ChatService.ReplyResult result2 = chatService.reply(event, question, quote.text(), allImages, replyMode);
        // 出站敏感词过滤现在在 OutboundSender 内部 —— 单一出口，任何发送路径都绕不过。
        // 放在这里的话，广场那条直接发送的路就漏掉了（实测漏洞）。
        // 用返回值而不是自己再过滤一遍：记录的就是**实际发出去的那段文本**。
        String safe = outboundSender.send(event, result2.text());

        // 异步记一笔：不阻塞、不抛异常（记录系统挂了也要照常回答）
        // ⚠️ 记的是 question 不是 text：agent 指令的原始文本是 "/联网 …"，
        //    记进去会让「未命中·该补什么」里全是命令前缀。
        qaCollector.collect(event, question, quote.text(), imageCount, "pass", safe,
                result2.kb(), result2.llmMs(), System.currentTimeMillis() - t0);
    }

    /** 取出被引用消息的文字和图片。失败不影响正常回复。 */
    private QuotedContent resolveQuote(OneBotEvent event) {
        Optional<String> replyId = codec.extractReplyId(event);
        if (replyId.isEmpty()) {
            return QuotedContent.EMPTY;
        }
        try {
            JsonNode quoted = apiClient.getMsg(replyId.get());
            JsonNode message = quoted.path("message");

            String quotedText = codec.extractPlainText(message);
            if (!StringUtils.hasText(quotedText)) {
                quotedText = quoted.path("raw_message").asText("");
            }
            List<ImageRef> quotedImages = codec.extractImageRefs(message);

            log.debug("引用消息解析完成：文字 {} 字，图片 {} 张（其中 {} 张有缓存键）",
                    quotedText.length(), quotedImages.size(),
                    quotedImages.stream().filter(ImageRef::hasKey).count());
            return new QuotedContent(StringUtils.hasText(quotedText) ? quotedText : null, quotedImages);
        } catch (Exception e) {
            log.warn("取引用消息失败（不影响正常回复）：{}", e.getMessage());
        }
        return QuotedContent.EMPTY;
    }

}
