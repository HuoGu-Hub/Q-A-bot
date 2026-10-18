package com.example.qqbot.router;

import com.example.qqbot.agent.ReplyMode;
import com.example.qqbot.command.BotCommand;
import com.example.qqbot.command.CommandArgs;
import com.example.qqbot.command.CommandHelp;
import com.example.qqbot.command.CommandMatcher;
import com.example.qqbot.command.CommandParseException;
import com.example.qqbot.command.CommandParser;
import com.example.qqbot.command.CommandPolicy;
import com.example.qqbot.command.CommandRateLimiter;
import com.example.qqbot.command.CommandStore;
import com.example.qqbot.command.RenderContext;
import com.example.qqbot.command.VariableRenderer;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.onebot.outbound.OutboundSender;
import com.example.qqbot.qa.QaCollector;
import com.example.qqbot.trace.KbTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 指令处理 —— 「这条消息是不是指令、要不要就地回」的判断全在这里。
 *
 * <h2>为什么从 MessageRouter 里搬出来</h2>
 * 它原先占 {@code MessageRouter} 一个 72 行的方法与 5 个依赖，而这几样
 * （配置、匹配、限流、存储、模板渲染）只被指令这一件事用到。
 *
 * <h2>三类指令的走法完全不同 —— 这是本类唯一必须记住的事</h2>
 * <ul>
 *   <li>{@code template}（话术）—— 就地渲染、秒回：不调模型、不计费、不走限流；</li>
 *   <li>{@code agent}（智能问答）—— <b>只登记</b>，返回 {@link Outcome#mode()} 与
 *       问题，让调用方继续走 Guard 与成本预算；</li>
 *   <li>{@code action}（动作）—— 交给注册的执行器写库或查库，同样不调模型。</li>
 * </ul>
 *
 * <h2>群里 @ 与否不在这里判</h2>
 * 那件事只有一处实现：准入设置 {@code app.guard.access.require-mention-in-group}。
 * 路由层先按它决定要不要调用本类，所以这里不再自带一份判断 ——
 * 同一个语义留两个开关，就会出现「问答回了、指令没回」这种自相矛盾的状态。
 */
@Component
public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private final CommandPolicy commandProperties;
    private final CommandMatcher commandMatcher;
    private final CommandParser commandParser;
    private final CommandHelp commandHelp;
    private final CommandRateLimiter commandRateLimiter;
    private final CommandStore commandStore;
    private final VariableRenderer variableRenderer;
    private final OutboundSender outboundSender;
    private final QaCollector qaCollector;

    public CommandHandler(CommandPolicy commandProperties,
                          CommandMatcher commandMatcher,
                          CommandParser commandParser,
                          CommandHelp commandHelp,
                          CommandRateLimiter commandRateLimiter,
                          CommandStore commandStore,
                          VariableRenderer variableRenderer,
                          OutboundSender outboundSender,
                          QaCollector qaCollector) {
        this.commandProperties = commandProperties;
        this.commandMatcher = commandMatcher;
        this.commandParser = commandParser;
        this.commandHelp = commandHelp;
        this.commandRateLimiter = commandRateLimiter;
        this.commandStore = commandStore;
        this.variableRenderer = variableRenderer;
        this.outboundSender = outboundSender;
        this.qaCollector = qaCollector;
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
     * @param question agent 指令的自由文本（就是它要问的问题）；非 agent 时为 null
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
     */
    public Outcome tryCommand(OneBotEvent event, String text, long t0) {
        if (!commandProperties.isEnabled()) {
            return Outcome.NOT_COMMAND;
        }
        boolean inGroup = event.isGroupMessage();
        long groupId = event.getGroupId() == null ? 0 : event.getGroupId();
        long userId = event.getUserId() == null ? 0 : event.getUserId();

        CommandMatcher.Match match = commandMatcher.match(text, inGroup, groupId);

        if (!match.hit()) {
            // 看起来像指令但没匹配上 —— 记一笔，用来发现"该加哪些命令"
            if (match.looksLike()) {
                commandStore.logUsage(match.rawTrigger(), groupId, userId, false);
            }
            return Outcome.NOT_COMMAND;
        }

        BotCommand cmd = match.command();

        // 指令级限流（独立于问答限流）
        String key = userId + "@" + groupId;
        if (!commandRateLimiter.allow(key)) {
            log.info("[CMD] 指令被限流：{} 触发 /{}", userId, cmd.trigger());
            return Outcome.HANDLED;   // 静默丢弃，不回复（避免"提示"本身变成刷屏）
        }

        // ---------- 参数解析：先展开变量，再按类型校验 ----------
        String tail = match.restOrEmpty();
        RenderContext base = variableRenderer.contextOf(event, Map.of(), "", tail);
        CommandArgs args;
        String body;
        try {
            CommandParser.Result parsed = commandParser.parse(tail, cmd.params(),
                    value -> variableRenderer.render(value, base));
            args = parsed.args();
            body = parsed.text();
        } catch (CommandParseException e) {
            log.info("[CMD] 参数不合格 /{}：{}", cmd.trigger(), e.getMessage());
            hint(event, cmd, e.getMessage(), tail, t0);
            return Outcome.HANDLED;
        }

        RenderContext ctx = variableRenderer.contextOf(event, args.rawValues(), body, tail);

        // ---------- 单命令帮助：/路径 /help ----------
        if (match.helpRequested()) {
            String help = commandHelp.render(cmd, ctx);
            outboundSender.send(event, help);
            commandStore.logUsage(cmd.trigger(), groupId, userId, true);
            qaCollector.collect(event, text, null, 0, "command", help,
                    KbTrace.disabled(), 0, System.currentTimeMillis() - t0);
            return Outcome.HANDLED;
        }

        // ---------- 智能问答类：登记后继续走 Guard + 预算，不在这里回 ----------
        if (cmd.isAgent()) {
            if (body.isEmpty()) {
                hint(event, cmd, "这次没带问题", tail, t0);
                return Outcome.HANDLED;
            }
            log.info("[CMD] 智能问答指令 /{}（模式={}，问题 {} 字）→ 继续走 Guard + 成本预算",
                    cmd.trigger(), cmd.modeName(), body.length());
            commandStore.logUsage(cmd.trigger(), groupId, userId, true);
            return Outcome.agent(ReplyMode.from(cmd.modeName()), body);
        }

        // ---------- 动作类：交给执行器 ----------
        if (cmd.isAction()) {
            log.warn("[CMD] 动作类命令 /{} 绑定的执行器「{}」还没接上", cmd.trigger(), cmd.action());
            hint(event, cmd, "这条命令的动作还没接上", tail, t0);
            return Outcome.HANDLED;
        }

        // ---------- 话术类：渲染模板就地回 ----------
        String reply = variableRenderer.render(cmd.reply(), ctx);
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

    /** 回一句"怎么用"：带上完整用法，别让群友自己去猜参数名 */
    private void hint(OneBotEvent event, BotCommand cmd, String reason, String tail, long t0) {
        long groupId = event.getGroupId() == null ? 0 : event.getGroupId();
        long userId = event.getUserId() == null ? 0 : event.getUserId();
        String authored = cmd.reply() == null ? "" : cmd.reply().trim();
        // 作者写过的用法提示优先；没写过就用参数表自动生成的
        String usage = authored.isEmpty()
                ? "用法：" + cmd.display()
                : variableRenderer.render(authored, variableRenderer.contextOf(event, Map.of(), "", tail));
        String text = reason + "\n" + usage;
        outboundSender.send(event, text);
        commandStore.logUsage(cmd.trigger(), groupId, userId, true);
        qaCollector.collect(event, text, null, 0, "command", text,
                KbTrace.disabled(), 0, System.currentTimeMillis() - t0);
    }
}
