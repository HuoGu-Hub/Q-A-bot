package com.example.qqbot.router;

import com.example.qqbot.agent.ChatService;
import com.example.qqbot.agent.ReplyMode;
import com.example.qqbot.guard.Access;
import com.example.qqbot.guard.RateLimit;
import com.example.qqbot.guard.BlockNotifier;
import com.example.qqbot.guard.BudgetGuard;
import com.example.qqbot.guard.GuardContext;
import com.example.qqbot.guard.GuardPipeline;
import com.example.qqbot.guard.GuardResult;
import com.example.qqbot.onebot.outbound.OutboundSender;
import com.example.qqbot.voice.MediaOutbox;
import com.example.qqbot.voice.VoiceDelivery;
import com.example.qqbot.voice.VoiceTriggerService;
import com.example.qqbot.onebot.BotIdentity;
import com.example.qqbot.onebot.client.OneBotApiClient;
import com.example.qqbot.onebot.codec.MessageCodec;
import com.example.qqbot.trace.KbTrace;
import com.example.qqbot.intent.IntentPlanner;
import com.example.qqbot.intent.TurnPlan;
import com.example.qqbot.onebot.model.ImageRef;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.qa.QaCollector;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
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
    private final AsyncPolicy asyncProperties;
    private final RateLimit guardRateLimit;
    private final Access guardAccess;
    private final BlockNotifier blockNotifier;
    private final QaCollector qaCollector;
    /** 指令那一路 —— 它自己的配置、匹配、限流、存储、渲染都在里面 */
    private final CommandHandler commandHandler;
    /** 待发语音的投递器。测试构造时可以为 null（不投递语音） */
    private final VoiceDelivery voiceDelivery;
    /** 关键词触发。测试构造时可以为 null（不触发） */
    private final VoiceTriggerService voiceTrigger;
    /** 意图判定。测试构造时可以为 null（一律按默认处理） */
    private final IntentPlanner intentPlanner;

    /** 不带语音投递的构造 —— 现有测试与老调用方用 */
    public MessageRouter(OneBotApiClient apiClient,
                         MessageCodec codec,
                         BotIdentity identity,
                         ChatService chatService,
                         GuardPipeline guardPipeline,
                         OutboundSender outboundSender,
                         BudgetGuard budgetGuard,
                         AsyncPolicy asyncProperties,
                         RateLimit guardRateLimit,
                         Access guardAccess,
                         BlockNotifier blockNotifier,
                         QaCollector qaCollector,
                         CommandHandler commandHandler) {
        this(apiClient, codec, identity, chatService, guardPipeline, outboundSender, budgetGuard,
                asyncProperties, guardRateLimit, guardAccess, blockNotifier, qaCollector,
                commandHandler, null, null, null);
    }

    /** Spring 用这个：语音投递也接进来 */
    @org.springframework.beans.factory.annotation.Autowired
    public MessageRouter(OneBotApiClient apiClient,
                         MessageCodec codec,
                         BotIdentity identity,
                         ChatService chatService,
                         GuardPipeline guardPipeline,
                         OutboundSender outboundSender,
                         BudgetGuard budgetGuard,
                         AsyncPolicy asyncProperties,
                         RateLimit guardRateLimit,
                         Access guardAccess,
                         BlockNotifier blockNotifier,
                         QaCollector qaCollector,
                         CommandHandler commandHandler,
                         VoiceDelivery voiceDelivery,
                         VoiceTriggerService voiceTrigger,
                         IntentPlanner intentPlanner) {
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
        this.commandHandler = commandHandler;
        this.voiceDelivery = voiceDelivery;
        this.voiceTrigger = voiceTrigger;
        this.intentPlanner = intentPlanner;
    }

    /**
     * 摘掉手打 / 复制出来的「@机器人」前缀。
     *
     * <p>只认「@昵称」「＠昵称」「@QQ号」，而且名字后面必须是空白或斜杠 ——
     * 否则「@机器人酱」会被误当成在叫机器人。认出来时返回摘掉前缀的文本，
     * 没认出来原样返回，调用方据此判断这是不是一次「文本 @」。
     */
    private String stripTextMention(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        List<String> names = new ArrayList<>(2);
        String selfName = identity.getSelfName();
        if (StringUtils.hasText(selfName)) {
            names.add(selfName.trim());
        }
        long selfId = identity.getSelfId();
        if (selfId > 0) {
            names.add(String.valueOf(selfId));
        }
        for (String name : names) {
            for (String at : new String[] {"@", "＠"}) {
                String prefix = at + name;
                if (!text.startsWith(prefix)) {
                    continue;
                }
                int end = prefix.length();
                boolean boundary = text.length() == end
                        || Character.isWhitespace(text.charAt(end))
                        || text.charAt(end) == '/';
                if (!boundary) {
                    continue;
                }
                return text.substring(end).trim();
            }
        }
        return text;
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
    /**
     * 处理一条**已经排到队**的事件。
     *
     * <p>这里不再自己开线程：{@link EventDispatcher} 已经按类型把它投进了问答池或命令池。
     * 队列等待超时的判断留在这里，因为它需要知道"提交前"的时间戳。
     */
    public void processQueued(OneBotEvent event, long enqueuedAt) {
        long waitedMs = System.currentTimeMillis() - enqueuedAt;
        long limitMs = asyncProperties.getMaxQueueWaitSeconds() * 1000L;
        if (waitedMs > limitMs) {
            log.warn("[ASYNC] 事件在队列里等了 {} ms（上限 {} s），已丢弃：{}",
                    waitedMs, asyncProperties.getMaxQueueWaitSeconds(), event);
            notifyOverload(event);
            return;
        }
        try {
            handle(event);
        } catch (Exception e) {
            log.error("处理事件失败：{}", event, e);
        }
    }

    /**
     * 被丢弃时礼貌地告诉用户一声（同样受提示限流保护）。
     *
     * <p>两个来源都走它：排太久被丢，和池子满了直接被拒。
     */
    public void notifyOverload(OneBotEvent event) {
        if (!event.isMessageEvent() || identity.isSelf(event.getUserId())) {
            return;
        }
        if (!blockNotifier.allow(event.getUserId(), event.getGroupId())) {
            return;
        }
        outboundSender.send(event, guardRateLimit.getQueueTimeoutText());
    }

    /**
     * 准入控制 —— <b>判定本身在 {@link Access#denial}</b>，与 Guard 的 access 阶段同一份实现。
     *
     * <p>单独抽出来是因为**指令必须在 Guard 之前跑**，但又不能绕开准入：
     * 被拉黑的人不该能触发指令，私聊没开放时同样不该有指令响应。
     * 以前这里自己读了那两张黑名单，于是同一条消息在两处能得出不同结论
     * （私聊策略 off 时话术指令照回、普通消息被拦）—— 现在不会了。
     */
    private boolean accessAllowed(OneBotEvent event) {
        Optional<String> denial = guardAccess.denial(event);
        if (denial.isPresent()) {
            log.info("[ROUTER] 准入拒绝（{}），不响应：群 {} 用户 {}",
                    denial.get(), event.getGroupId(), event.getUserId());
            return false;
        }
        return true;
    }

    /**
     * 一条消息在本次处理里的上下文 —— 在 {@link #handle} 的各步骤之间传递。
     *
     * <p>为什么要有它：拆成步骤之后，每一步都要 event / text / 图片 / 起始时间戳，
     * 逐个当参数传就是 5~6 个参数的长签名；而问答记录那一笔又同时需要这几样。
     * 收成一个记录，步骤方法只接一个参数。
     */
    private record RoutedRequest(OneBotEvent event, String text, List<ImageRef> ownImages,
                                 int imageCount, boolean hasQuote, long t0) {

        static RoutedRequest of(OneBotEvent event, String text, List<ImageRef> ownImages,
                                boolean hasQuote, long t0) {
            return new RoutedRequest(event, text, ownImages, ownImages.size(), hasQuote, t0);
        }

        /** 所有记录点都从这里算耗时 */
        long elapsedMs() {
            return System.currentTimeMillis() - t0;
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
        String rawText = codec.extractPlainText(event);
        // QQ 的 @ 是消息段；用户手打或复制出来的「@昵称」只是普通文字。
        // 这种消息意图明确，按 @ 处理并把前缀摘掉，否则会被准入层静默丢弃。
        String text = event.isGroupMessage() ? stripTextMention(rawText) : rawText;
        boolean textMention = event.isGroupMessage() && !text.equals(rawText);
        List<ImageRef> ownImages = codec.extractImageRefs(event);
        boolean hasQuote = codec.extractReplyId(event).isPresent();
        RoutedRequest req = RoutedRequest.of(event, text, ownImages, hasQuote, t0);

        // ① 准入控制（黑名单 / 私聊策略）—— 唯一在指令之前跑的一层。
        //    被拉黑的人不该能触发指令，但除此之外指令不该受任何 Guard 限制。
        if (!accessAllowed(event)) {
            record(req, "drop", null);
            return;
        }

        // ② 指令匹配（★ 在 Guard 之前，在模型之前）
        //    · 话术类命中就秒回：不走限流、不走敏感词、不调模型。
        //      理由：/help 不该等模型 3 秒，也不该受"每分钟只能问一次"的限流。
        //    · 智能问答类**只登记**，继续往下走 Guard 与成本预算（它会真的调模型）。
        //
        //    ⚠️ @ 判定复用准入那一份设置，指令不再自带 require-mention：
        //    同一个语义留两个开关，会出现「问答回了、指令没回」这种自相矛盾的状态。
        boolean mentioned = !event.isGroupMessage()
                || textMention
                || codec.isMentioned(event, identity.getSelfId());
        CommandHandler.Outcome cmd = guardAccess.mentionSatisfied(event.isGroupMessage(), mentioned)
                ? commandHandler.tryCommand(event, text, t0)
                : CommandHandler.Outcome.NOT_COMMAND;
        if (cmd.handled()) {
            return;
        }
        ReplyMode replyMode = cmd.mode();
        // agent 指令把「参数」当问题；其余情况用整条消息
        String question = cmd.question() != null ? cmd.question() : text;

        // ③ Guard 五层 + 成本预算。顺序有硬约束，见 guardAndBudget 的注释。
        if (guardAndBudget(req, mentioned)) {
            return;
        }

        // ④ 真正生成回复并发出
        reply(req, question, replyMode);
    }

    /**
     * Guard 五层 + 成本预算。
     *
     * <p>顺序是有约束的：
     * <ul>
     *   <li>Guard 在调模型之前 —— 攻击者不能用敏感内容或注入话术烧 token；</li>
     *   <li>预算也在调模型之前 —— 预算的意义就是别把 token 花出去。</li>
     * </ul>
     *
     * @return {@code true} = 本次到此为止（被拦、回了固定话术、或超预算），不要再调模型
     */
    private boolean guardAndBudget(RoutedRequest req, boolean mentioned) {
        OneBotEvent event = req.event();

        GuardContext ctx = new GuardContext(event, req.text(),
                req.ownImages().stream().map(ImageRef::url).toList(), req.hasQuote(), mentioned);
        GuardResult result = guardPipeline.check(ctx);
        if (result instanceof GuardResult.Drop) {
            // 被 Guard 拦下的也记一笔 —— "多少消息被限流/拦下"本身就是运营数据
            record(req, "drop", null);
            return true;
        }
        if (result instanceof GuardResult.Reply reply) {
            log.info("走固定话术（stage 拦截）：{}", reply.reason());
            outboundSender.send(event, reply.text());
            record(req, "fixed_reply", reply.text());
            return true;
        }

        // 成本预算（D6）：限流管「多快」，这里管「一天总共多少」
        BudgetGuard.Decision budget = budgetGuard.check(event.getUserId(), event.getGroupId());
        if (budget.verdict() == BudgetGuard.Verdict.USER_LIMIT) {
            outboundSender.send(event, budget.text());
            record(req, "fixed_reply", budget.text());
            return true;
        }
        if (budget.verdict() != BudgetGuard.Verdict.OK) {
            // 群级超限 = 该群静默；全局熔断 = 谁都不回（BudgetGuard 里已经 ERROR 告警过）
            log.info("[BUDGET] 丢弃消息：verdict={} group={} user={}",
                    budget.verdict(), event.getGroupId(), event.getUserId());
            record(req, "drop", null);
            return true;
        }
        budgetGuard.recordCall(event.getUserId(), event.getGroupId());
        return false;
    }

    /**
     * 生成回复并发出。
     *
     * <p>出站敏感词过滤在 {@link OutboundSender} 内部 —— 单一出口，任何发送路径都绕不过。
     * （放在调用方就得靠每个人记得调，旁路直接发送就漏掉过。）
     * 用它的返回值而不是自己再过滤一遍：记录的就是<b>实际发出去的那段文本</b>。
     */
    private void reply(RoutedRequest req, String question, ReplyMode replyMode) {
        OneBotEvent event = req.event();

        // 意图判定：第一次提交只记账，不改变行为 —— 用真实流量校准规则精度
        TurnPlan plan = intentPlanner == null ? TurnPlan.standard() : intentPlanner.plan(question);
        log.info("[INTENT] move={} retrieve={} reason={} | {}", plan.move(), plan.retrieve(),
                plan.reason(), question == null ? "" : question.strip());

        // 解析引用（QQ 的引用段只有 ID，正文和图片都要另外调 get_msg 取）
        QuotedContent quote = resolveQuote(event);

        // 消息自带的图片 + 被引用消息里的图片，一起交给模型
        List<ImageRef> allImages = new ArrayList<>(req.ownImages());
        allImages.addAll(quote.imageRefs());

        // 关键词只决定"这条必须用语音回"，回什么仍交给模型
        boolean voiceRequired = voiceTrigger != null
                && voiceTrigger.requiresVoice(event.getGroupId(), question);

        ChatService.ReplyResult result = voiceRequired
                ? chatService.reply(event, question, quote.text(), allImages, replyMode, true)
                : chatService.reply(event, question, quote.text(), allImages, replyMode);

        // 语音与文字谁先冒出来：一次随机，避免每条回复都像模板。
        // 有语音时必须同步发文字 —— 走异步队列的话，文字可能被发送线程推到语音后面。
        String safe;
        List<MediaOutbox.Intent> media = result.media();
        if (media.isEmpty() || voiceDelivery == null) {
            safe = outboundSender.send(event, result.text());
        } else {
            boolean voiceFirst = java.util.concurrent.ThreadLocalRandom.current().nextBoolean();
            if (voiceFirst) {
                voiceDelivery.deliver(event, media);
            }
            safe = outboundSender.sendNow(event, result.text());
            if (!voiceFirst) {
                voiceDelivery.deliver(event, media);
            }
        }

        // 异步记一笔：不阻塞、不抛异常（记录系统挂了也要照常回答）
        // ⚠️ 记的是 question 不是 text：agent 指令的原始文本是 "/联网 …"，
        //    记进去会让「未命中·该补什么」里全是命令前缀。
        qaCollector.collect(event, question, quote.text(), req.imageCount(), "pass", safe,
                result.kb(), result.llmMs(), req.elapsedMs());
    }

    /**
     * 问答记录的<b>唯一出口</b>。
     *
     * <p>以前 "drop" 写了三遍、"fixed_reply" 写了两遍，每一处都把同样的 9 个参数抄一次 ——
     * 加一个统计字段要改五处，漏一处就是"某条路径没记账"。
     */
    private void record(RoutedRequest req, String kind, String replyText) {
        qaCollector.collect(req.event(), req.text(), null, req.imageCount(), kind, replyText,
                KbTrace.disabled(), 0, req.elapsedMs());
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
