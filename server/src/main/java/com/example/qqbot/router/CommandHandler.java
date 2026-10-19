package com.example.qqbot.router;

import com.example.qqbot.agent.ReplyMode;
import com.example.qqbot.command.ActionContext;
import com.example.qqbot.command.ActionException;
import com.example.qqbot.command.ActionRegistry;
import com.example.qqbot.command.BotCommand;
import com.example.qqbot.command.CommandAction;
import com.example.qqbot.command.CommandArgs;
import com.example.qqbot.command.CommandHelp;
import com.example.qqbot.command.CommandMatcher;
import com.example.qqbot.command.CommandParseException;
import com.example.qqbot.command.CommandParser;
import com.example.qqbot.command.CommandPolicy;
import com.example.qqbot.command.CommandRateLimiter;
import com.example.qqbot.command.CommandRole;
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
 *   <li>{@code template}（话术）—— 就地渲染、秒回：不调模型、不计费；</li>
 *   <li>{@code agent}（智能问答）—— <b>只登记</b>，返回 {@link Outcome#mode()} 与
 *       问题，让调用方继续走 Guard 与成本预算；</li>
 *   <li>{@code action}（动作）—— 交给 {@link ActionRegistry} 里的执行器写库或查库。</li>
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
    private final ActionRegistry actionRegistry;
    private final CommandRateLimiter commandRateLimiter;
    private final CommandStore commandStore;
    private final VariableRenderer variableRenderer;
    private final OutboundSender outboundSender;
    private final QaCollector qaCollector;

    public CommandHandler(CommandPolicy commandProperties,
                          CommandMatcher commandMatcher,
                          CommandParser commandParser,
                          CommandHelp commandHelp,
                          ActionRegistry actionRegistry,
                          CommandRateLimiter commandRateLimiter,
                          CommandStore commandStore,
                          VariableRenderer variableRenderer,
                          OutboundSender outboundSender,
                          QaCollector qaCollector) {
        this.commandProperties = commandProperties;
        this.commandMatcher = commandMatcher;
        this.commandParser = commandParser;
        this.commandHelp = commandHelp;
        this.actionRegistry = actionRegistry;
        this.commandRateLimiter = commandRateLimiter;
        this.commandStore = commandStore;
        this.variableRenderer = variableRenderer;
        this.outboundSender = outboundSender;
        this.qaCollector = qaCollector;
    }

    /**
     * 尝试把消息当作指令处理的结果 —— 三种走向。
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
        String tail = match.restOrEmpty();

        // 指令级限流（独立于问答限流）
        String key = userId + "@" + groupId;
        if (!commandRateLimiter.allow(key)) {
            log.info("[CMD] 指令被限流：{} 触发 /{}", userId, cmd.trigger());
            return Outcome.HANDLED;   // 静默丢弃，不回复（避免"提示"本身变成刷屏）
        }

        // ---------- 权限：min_role 原先只存不判，这里补上 ----------
        CommandRole role = CommandRole.ofSender(event);
        CommandRole required = CommandRole.ofMinRole(cmd.minRole());
        if (!role.atLeast(required)) {
            log.info("[CMD] 权限不足：用户 {} 在群 {} 触发 /{}（需要 {}，实为 {}）",
                    userId, groupId, cmd.trigger(), required, role);
            reply(event, cmd, tail, required.describe(inGroup), t0);
            return Outcome.HANDLED;
        }

        // ---------- 参数解析：先展开变量，再按类型校验 ----------
        // text 传 null 而不是 ""：这一趟只是给参数值展开变量，
        // 自由文本还没算出来，{text} 必须原样保留而不是变成空串
        RenderContext base = variableRenderer.contextOf(event, Map.of(), null, tail);
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
            reply(event, cmd, tail, commandHelp.render(cmd, ctx), t0);
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

        // ---------- 动作类：交给注册表里的执行器 ----------
        if (cmd.isAction()) {
            ActionContext actx = new ActionContext(userId, groupId, inGroup, role,
                    ctx.nickname(), cmd.trigger(), body);
            runAction(event, cmd, actx, args, tail, groupId, userId, t0);
            return Outcome.HANDLED;
        }

        // ---------- 话术类：渲染模板就地回 ----------
        String rendered = variableRenderer.render(cmd.reply(), ctx);
        if (rendered.isBlank()) {
            log.warn("[CMD] 指令 /{} 渲染后为空，跳过", cmd.trigger());
            return Outcome.HANDLED;
        }
        log.info("[CMD] 触发指令 /{}（群 {} 用户 {}），回复 {} 字",
                cmd.trigger(), groupId, userId, rendered.length());
        reply(event, cmd, text, rendered, t0);
        return Outcome.HANDLED;
    }

    /**
     * 执行一个动作。
     *
     * <p>三条失败路径都要回人话，且都<b>不回执成错误堆栈</b>：
     * 没绑执行器、执行器报预期错误、执行器返回空。
     */
    private void runAction(OneBotEvent event, BotCommand cmd, ActionContext actx, CommandArgs args,
                           String tail, long groupId, long userId, long t0) {
        CommandAction action = actionRegistry.find(cmd.action());
        if (action == null) {
            // 命令还在，绑的动作被删了 —— 当作停用并告警，而不是在线上抛异常
            log.warn("[CMD] 命令 /{} 绑定的动作「{}」不存在，可用：{}",
                    cmd.trigger(), cmd.action(), actionRegistry.all().size());
            hint(event, cmd, "这条命令的动作还没接上", tail, t0);
            return;
        }

        String out;
        try {
            out = action.execute(actx, args);
        } catch (ActionException e) {
            // 动作自己写的消息已经是一句完整人话，不必再加命令路径前缀
            log.info("[CMD] 动作 {} 拒绝执行 /{}：{}", action.id(), cmd.trigger(), e.getMessage());
            reply(event, cmd, tail, e.getMessage(), t0);
            return;
        } catch (RuntimeException e) {
            // 动作自己的 bug 不该把整条消息链路带崩，但要让日志能定位
            log.error("[CMD] 动作 {} 执行 /{} 失败", action.id(), cmd.trigger(), e);
            reply(event, cmd, tail, "这条命令刚才没执行成功，已经记下日志", t0);
            return;
        }

        if (out == null || out.isBlank()) {
            log.info("[CMD] 动作 {} 执行 /{} 完成，无需回执", action.id(), cmd.trigger());
            commandStore.logUsage(cmd.trigger(), groupId, userId, true);
            return;
        }
        log.info("[CMD] 动作 {} 执行 /{} 完成，回执 {} 字", action.id(), cmd.trigger(), out.length());
        reply(event, cmd, tail, out, t0);
    }

    /**
     * 指令路径上的唯一出口：发一句话，然后记使用与问答各一笔。
     *
     * <p>以前 send + logUsage + collect 这三件套在五个分支里各抄一遍，
     * 加一个统计字段要改五处，漏一处就是"某条路径没记账"。
     *
     * @param original 触发这次指令的原文，进问答记录
     * @param outgoing 实际要发出去的文本
     */
    private void reply(OneBotEvent event, BotCommand cmd, String original, String outgoing, long t0) {
        String safe = outboundSender.send(event, outgoing);
        long groupId = event.getGroupId() == null ? 0 : event.getGroupId();
        long userId = event.getUserId() == null ? 0 : event.getUserId();
        commandStore.logUsage(cmd.trigger(), groupId, userId, true);
        qaCollector.collect(event, original, null, 0, "command", safe,
                KbTrace.disabled(), 0, System.currentTimeMillis() - t0);
    }

    /** 回一句"怎么用"：先说清哪一项不对，再附完整用法 */
    private void hint(OneBotEvent event, BotCommand cmd, String reason, String tail, long t0) {
        String authored = cmd.reply() == null ? "" : cmd.reply().trim();
        // 作者写过的用法提示优先；没写过就用参数表自动生成的
        String usage = authored.isEmpty()
                ? "用法：" + cmd.display()
                : variableRenderer.render(authored, variableRenderer.contextOf(event, Map.of(), "", tail));
        reply(event, cmd, tail, reason + "\n" + usage, t0);
    }
}
