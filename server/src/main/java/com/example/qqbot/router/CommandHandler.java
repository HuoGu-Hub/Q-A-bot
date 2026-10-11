package com.example.qqbot.router;

import com.example.qqbot.agent.ReplyMode;
import com.example.qqbot.command.BotCommand;
import com.example.qqbot.command.CommandMatcher;
import com.example.qqbot.command.CommandPolicy;
import com.example.qqbot.command.CommandRateLimiter;
import com.example.qqbot.command.CommandStore;
import com.example.qqbot.command.VariableRenderer;
import com.example.qqbot.onebot.BotIdentity;
import com.example.qqbot.onebot.codec.MessageCodec;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.onebot.outbound.OutboundSender;
import com.example.qqbot.qa.QaCollector;
import com.example.qqbot.trace.KbTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 指令处理 —— 「这条消息是不是指令、要不要就地回」的判断全在这里。
 *
 * <h2>为什么从 MessageRouter 里搬出来</h2>
 * 它原先占 {@code MessageRouter} 一个 72 行的方法与 5 个依赖，而这几样
 * （配置、匹配、限流、存储、模板渲染）只被指令这一件事用到。
 * 搬出来之后路由那一层只剩"顺序"，改指令不必再读整个路由。
 *
 * <h2>两类指令的走法完全不同 —— 这是本类唯一必须记住的事</h2>
 * <ul>
 *   <li>{@code template}（话术）—— 就地渲染、秒回：不调模型、不计费、不走限流；</li>
 *   <li>{@code agent}（智能问答）—— <b>只登记</b>，返回 {@link Outcome#mode()} 与
 *       问题，让调用方继续走 Guard 与成本预算。它会真的调模型，
 *       绝不能像话术那样在这里就地回完。</li>
 * </ul>
 */
@Component
public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private final CommandPolicy commandProperties;
    private final CommandMatcher commandMatcher;
    private final CommandRateLimiter commandRateLimiter;
    private final CommandStore commandStore;
    private final VariableRenderer variableRenderer;
    private final OutboundSender outboundSender;
    private final QaCollector qaCollector;
    private final MessageCodec codec;
    private final BotIdentity identity;

    public CommandHandler(CommandPolicy commandProperties,
                          CommandMatcher commandMatcher,
                          CommandRateLimiter commandRateLimiter,
                          CommandStore commandStore,
                          VariableRenderer variableRenderer,
                          OutboundSender outboundSender,
                          QaCollector qaCollector,
                          MessageCodec codec,
                          BotIdentity identity) {
        this.commandProperties = commandProperties;
        this.commandMatcher = commandMatcher;
        this.commandRateLimiter = commandRateLimiter;
        this.commandStore = commandStore;
        this.variableRenderer = variableRenderer;
        this.outboundSender = outboundSender;
        this.qaCollector = qaCollector;
        this.codec = codec;
        this.identity = identity;
    }

    /**
     * 尝试把消息当作指令处理的结果 —— 三种走向。
     *
     * <p><b>为什么不是 boolean</b>：智能问答类指令（agent）**不能**在这里回完就返回。
     * 它会真的调模型，所以必须继续往下走 Guard 与成本预算 ——
     * 原来那套"指令跑在 Guard 之前、绕过限流和预算"是为 {@code /help} 这种
     * 零成本话术设计的，套到调模型的指令上等于开了一个不限量的免费额度。
     *
     * @param handled  已经处理完（不论有没有回话，调用方都该到此为止）
     * @param mode     agent 指令的回答模式；非 agent 时无意义
     * @param question agent 指令的参数（就是它要问的问题）；非 agent 时为 null
     */
    public record Outcome(boolean handled, ReplyMode mode, String question) {

        /** 不是指令 */
        static final Outcome NOT_COMMAND = new Outcome(false, ReplyMode.KB, null);
        /** 已经在这里回完了 */
        static final Outcome HANDLED = new Outcome(true, ReplyMode.KB, null);

        /** agent 指令：带上回答模式与"问题"，继续走 Guard + 预算 */
        static Outcome agent(ReplyMode mode, String question) {
            return new Outcome(false, mode, question);
        }
    }

    /**
     * 尝试把消息当作指令处理。
     *
     * @param t0 事件开始处理的时间戳，用于问答记录里的耗时
     * @return 见 {@link Outcome}
     */
    public Outcome tryCommand(OneBotEvent event, String text, long t0) {
        if (!commandProperties.isEnabled()) {
            return Outcome.NOT_COMMAND;
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
            return Outcome.NOT_COMMAND;
        }

        BotCommand cmd = match.command();
        String args = match.argsOrEmpty();

        // 指令级限流（独立于问答限流）
        String key = userId + "@" + groupId;
        if (!commandRateLimiter.allow(key)) {
            log.info("[CMD] 指令被限流：{} 触发 /{}", userId, cmd.trigger());
            return Outcome.HANDLED;   // 静默丢弃，不回复（避免"提示"本身变成刷屏）
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
                return Outcome.HANDLED;
            }
            log.info("[CMD] 智能问答指令 /{}（模式={}，参数 {} 字）→ 继续走 Guard + 成本预算",
                    cmd.trigger(), cmd.modeName(), args.length());
            commandStore.logUsage(cmd.trigger(), groupId, userId, true);
            return Outcome.agent(ReplyMode.from(cmd.modeName()), args);
        }

        // ---------- 话术类：渲染模板就地回 ----------
        String reply = variableRenderer.render(cmd.reply(), event, false, args);
        if (reply.isBlank()) {
            log.warn("[CMD] 指令 /{} 渲染后为空，跳过", cmd.trigger());
            return Outcome.HANDLED;
        }

        log.info("[CMD] 触发指令 /{}（群 {} 用户 {}），回复 {} 字",
                cmd.trigger(), groupId, userId, reply.length());
        outboundSender.send(event, reply);
        commandStore.logUsage(cmd.trigger(), groupId, userId, true);

        // 指令不算问答检索，但记一笔便于统计"指令用了多少次"
        qaCollector.collect(event, text, null, 0, "command", reply,
                KbTrace.disabled(), 0, System.currentTimeMillis() - t0);
        return Outcome.HANDLED;
    }
}
