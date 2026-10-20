package com.example.qqbot.command;

import com.example.qqbot.site.BotPolicy;
import com.example.qqbot.kb.Glossary;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.qa.QaAnalytics;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.time.AppTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变量渲染 —— 把回复模板与命令文本里的 §{变量}§ 替换成实际值。
 *
 * <p>这是"指令能动态获取信息"的核心。变量分五类：
 * <ol>
 *   <li><b>上下文</b> —— {user} {group} {bot}</li>
 *   <li><b>时间</b> —— {time} {date} {weekday} {uptime}</li>
 *   <li><b>统计</b> —— {kb.count} {qa.total} {qa.hitrate} …</li>
 *   <li><b>指令</b> —— {cmd.total} {cmd.list}</li>
 *   <li><b>命令</b> —— {args} {args.N} {arg.名称} {text}</li>
 * </ol>
 *
 * <p><b>三条安全约束</b>：
 * <ol>
 *   <li>**不提供** {config.*} {env.*} {server.*} 这类能读系统信息的变量</li>
 *   <li>变量值**原样输出，不二次解析** —— 防模板注入。
 *       变量现在也会作用在群友输入上，所以这条是硬约束：有人把昵称改成
 *       {cmd.list}，发出去的就是那几个字，不会真的展开一次</li>
 *   <li>未知变量**原样保留**，方便发现拼错</li>
 * </ol>
 *
 * <p><b>语言只有一种</b>：系统变量名固定英文。命令名与参数名由创建者自己起，
 * 中英皆可，但不做中英别名 —— 写 {用户} 会原样保留，不会被猜成 {user}。
 */
@Component
public class VariableRenderer {

    private static final Logger log = LoggerFactory.getLogger(VariableRenderer.class);

    /** §{xxx}§ —— 变量名只允许字母数字点下划线汉字 */
    private static final Pattern VAR = Pattern.compile("\\{([a-zA-Z\\p{IsHan}][a-zA-Z0-9_.\\p{IsHan}]*)}");

    /** §{{§ 和 §}}§ 转义成字面量 */
    private static final String ESC_OPEN = "\u0000LBRACE\u0000";
    private static final String ESC_CLOSE = "\u0000RBRACE\u0000";

    private final CommandPolicy props;
    private final CommandStore store;
    private final KbBlockStore blockStore;
    private final Glossary glossary;
    private final QaAnalytics analytics;
    private final BotPolicy botProps;

    private final Instant startedAt = Instant.now();

    public VariableRenderer(CommandPolicy props, CommandStore store,
                            KbBlockStore blockStore, Glossary glossary, QaAnalytics analytics,
                            BotPolicy botProps) {
        this.props = props;
        this.store = store;
        this.blockStore = blockStore;
        this.glossary = glossary;
        this.analytics = analytics;
        this.botProps = botProps;
    }

    /**
     * 变量说明（给配置界面用）。
     *
     * @param inParam 能不能写在 {@code #名-值#} 的值里。只有 {@code {arg.*}} 与
     *                {@code {text}} 不行 —— 前者会自己引用自己，后者在参数摘完之前还不存在
     * @param inText  能不能写在文本与模板里
     */
    public record VarInfo(String name, String label, String example, boolean advanced,
                          boolean inParam, boolean inText) {

        /** 两个位置都能用的系统变量 */
        static VarInfo value(String name, String label, String example) {
            return new VarInfo(name, label, example, false, true, true);
        }

        static VarInfo value(String name, String label, String example, boolean advanced) {
            return new VarInfo(name, label, example, advanced, true, true);
        }

        /** 只能写在文本里 —— 参数之间不互相引用 */
        static VarInfo textOnly(String name, String label, String example) {
            return new VarInfo(name, label, example, false, false, true);
        }
    }

    /** 全部可用变量 —— 配置界面靠它渲染"点击插入"按钮，变量页靠它列清单 */
    public List<VarInfo> available() {
        List<VarInfo> list = new java.util.ArrayList<>(List.of(
                VarInfo.value("user", "提问者昵称", "示例用户"),
                VarInfo.value("group", "群名称", "示例玩家群"),
                VarInfo.value("bot", "机器人昵称", "示例助手"),
                VarInfo.value("time", "当前时间", "14:32"),
                VarInfo.value("date", "今天日期", "2026-09-25"),
                VarInfo.value("datetime", "完整时间", "2026-09-25 14:32:07"),
                VarInfo.value("weekday", "星期", "星期四"),
                VarInfo.value("uptime", "已运行时长", "3 小时 12 分"),
                // ⚠️ 预览值必须是真实量级：kb.count 原来写 4,238，磁盘实际是 4,131
                VarInfo.value("kb.count", "知识库条目数", "4,131"),
                VarInfo.value("kb.terms", "术语表词条数", "3,359"),
                // 提问 = @ 了机器人 + 有效回应且未被拦截（guard_action='pass'）
                VarInfo.value("qa.total", "累计提问数", "128"),
                VarInfo.value("qa.today", "今日提问数", "12"),
                VarInfo.value("qa.hitrate", "检索命中率", "87.3%"),
                VarInfo.value("cmd.total", "指令总数", "4"),
                VarInfo.value("cmd.list", "指令列表（含说明）", "可用指令：…"),
                // 命令参数：/报名 张三 18 → {args}="张三 18"、{args.1}="张三"、{args.2}="18"
                VarInfo.value("args", "命令尾部原文（全部）", "张三 18"),
                VarInfo.value("args.1", "第 1 个词", "张三"),
                VarInfo.value("args.2", "第 2 个词", "18"),
                // 井号参数：#time-21:30# → {arg.time}="21:30"
                VarInfo.textOnly("arg.名称", "井号参数（按名字）", "21:30"),
                VarInfo.textOnly("text", "摘掉参数后的自由文本", "晚上打本集合")));

        if (props.isAllowUserIds()) {
            list.add(VarInfo.value("user.id", "提问者 QQ 号", "100000005", true));
            list.add(VarInfo.value("group.id", "群号", "100000004", true));
        }
        return list;
    }

    /**
     * 渲染模板。
     *
     * @param template 含 §{变量}§ 的模板
     * @param ctx      渲染上下文
     */
    public String render(String template, RenderContext ctx) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        RenderContext context = ctx == null ? RenderContext.sample() : ctx;

        // 1) 先把 {{ }} 保护起来，避免被当成变量
        String text = template.replace("{{", ESC_OPEN).replace("}}", ESC_CLOSE);

        // 2) 逐个替换
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            String value = resolve(name, context);
            // 未知变量原样保留
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(sb);

        // 3) 还原转义
        String out = sb.toString().replace(ESC_OPEN, "{").replace(ESC_CLOSE, "}");

        // 4) 长度保护
        int max = props.getMaxReplyLength();
        if (max > 0 && out.length() > max) {
            out = out.substring(0, max) + "…";
        }
        return out;
    }

    /**
     * 老签名的适配层：从入站事件构造上下文。
     *
     * <p>保留它只是为了让配置界面的预览与既有测试不必一起改；
     * 真正做事的只有上面那个 {@link #render(String, RenderContext)}。
     */
    public String render(String template, OneBotEvent event, boolean preview) {
        return render(template, event, preview, "");
    }

    public String render(String template, OneBotEvent event, boolean preview, String args) {
        return render(template, contextOf(event, Map.of(), "", args, preview));
    }

    /** 从入站事件构造上下文 —— 事件形状只在 {@link VariableRenderer} 里出现一次 */
    public RenderContext contextOf(OneBotEvent event, Map<String, String> namedArgs, String text,
                                   String argsText) {
        return contextOf(event, namedArgs, text, argsText, false);
    }

    public RenderContext contextOf(OneBotEvent event, Map<String, String> namedArgs, String text,
                                   String argsText, boolean preview) {
        if (event == null) {
            // 预览也要保留管理员填的示例参数：{args} / {args.N} / {arg.名字} 才有东西可看
            return preview ? RenderContext.preview(argsText, namedArgs)
                    : RenderContext.scheduled(0L, 0L, false, "朋友", argsText, namedArgs, text,
                            Instant.now());
        }
        return new RenderContext(
                event.getUserId(), event.getGroupId(), event.isGroupMessage(),
                nickname(event), argsText, namedArgs, text, preview, Instant.now());
    }

    /** 解析一个变量；返回 null 表示"未知变量，原样保留" */
    private String resolve(String name, RenderContext ctx) {
        // 高级变量：未开启时给出明确提示，而不是静默失败
        if (!props.isAllowUserIds() && (name.equals("user.id") || name.equals("group.id"))) {
            return "（未启用）";
        }

        // 命令参数：{args} 取全部原文，{args.N} 取第 N 个词（老命令靠它）
        if (name.equals("args")) {
            String raw = argsTrimmed(ctx.argsText());
            // 预览没填示例参数时才用占位值，填了就按填的来
            return raw.isEmpty() && ctx.preview() ? "张三 18" : raw;
        }
        if (name.startsWith("args.")) {
            int idx = parseIndex(name.substring("args.".length()));
            if (idx <= 0) {
                // "{args.x}" / "{args.0}" 不是合法序号 → 当未知变量，**原样保留**，
                // 方便在群里一眼看出拼错了（返回空串会让它静默消失，最难查）
                return null;
            }
            String raw = argsTrimmed(ctx.argsText());
            if (raw.isEmpty() && ctx.preview()) {
                return idx == 1 ? "张三" : idx == 2 ? "18" : "参数" + idx;
            }
            String[] words = raw.split("\\s+");
            return idx <= words.length && !words[idx - 1].isBlank() ? words[idx - 1] : "";
        }

        // 井号参数：按名字取，取不到当未知变量（不静默变空）
        if (name.startsWith("arg.")) {
            String key = name.substring("arg.".length());
            String value = ctx.namedArgs().get(key);
            if (value != null) {
                return value;
            }
            // 大小写不敏感地再找一遍：参数名中英皆可，{arg.Time} 也该认
            for (Map.Entry<String, String> e : ctx.namedArgs().entrySet()) {
                if (e.getKey().toLowerCase(Locale.ROOT).equals(key)) {
                    return e.getValue();
                }
            }
            // 取不到就原样保留；预览也不猜，免得把拼错的参数名藏起来
            return null;
        }

        if (name.equals("text")) {
            if (ctx.text() != null) {
                return ctx.text();
            }
            // text 为 null 表示"还没算出来"（正在展开参数值的那一趟）。
            // 这时返回 null 让它原样保留，而不是静默变成空串 —— 后者最难查。
            return ctx.preview() ? "示例文本" : null;
        }

        return switch (name) {
            case "user" -> ctx.preview() ? "示例用户"
                    : (ctx.nickname() == null || ctx.nickname().isBlank() ? "朋友" : ctx.nickname());
            case "group" -> ctx.preview() ? "示例玩家群" : groupName(ctx);
            case "bot" -> ctx.preview() ? "示例助手" : botProps.getName();
            case "user.id" -> String.valueOf(ctx.userId() == null ? 0L : ctx.userId());
            case "group.id" -> String.valueOf(ctx.groupId() == null ? 0L : ctx.groupId());

            case "time" -> clock(ctx, "HH:mm");
            case "date" -> clock(ctx, "yyyy-MM-dd");
            case "datetime" -> clock(ctx, "yyyy-MM-dd HH:mm:ss");
            case "weekday" -> weekday(ctx);
            case "uptime" -> uptime();

            case "kb.count" -> ctx.preview() ? "4,131" : num(blockStore.isAvailable() ? blockStore.count() : 0);
            case "kb.terms" -> ctx.preview() ? "3,359" : num(glossary.size());

            case "qa.total" -> ctx.preview() ? "128" : num(totalQa(0));
            case "qa.today" -> ctx.preview() ? "12" : num(totalQa(1));
            case "qa.hitrate" -> ctx.preview() ? "87.3%" : hitRate();

            case "cmd.total" -> ctx.preview() ? "4" : num(store.listEnabled().size());
            case "cmd.list" -> cmdList(ctx.preview());

            default -> null;
        };
    }

    // ==================== 各项取值 ====================

    private static String nickname(OneBotEvent event) {
        if (event == null || event.getSender() == null) {
            return "朋友";
        }
        var sender = event.getSender();
        String card = sender.path("card").asText("");
        if (!card.isBlank()) {
            return card;
        }
        String nick = sender.path("nickname").asText("");
        return nick.isBlank() ? "朋友" : nick;
    }

    /** 群名称不在消息事件里，用群号代替（避免为了显示名字多调一次 API） */
    private static String groupName(RenderContext ctx) {
        if (!ctx.group()) {
            return "私聊";
        }
        return "本群(" + (ctx.groupId() == null ? 0L : ctx.groupId()) + ")";
    }

    private static String argsTrimmed(String args) {
        return args == null ? "" : args.trim();
    }

    /** "{args.2}" 里的序号；不是数字就返回 -1（当未知变量处理） */
    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 当前时刻按东八区格式化。
     *
     * <p>取的是<b>上下文里的时刻</b>而不是 {@code LocalDateTime.now()}：定时发送渲染时，
     * 后者只是"碰巧相等"，而且一旦想让测试喂一个确定的时刻就写不出来了。
     */
    private static String clock(RenderContext ctx, String pattern) {
        return LocalDateTime.ofInstant(ctx.now(), AppTime.ZONE)
                .format(DateTimeFormatter.ofPattern(pattern));
    }

    private static String weekday(RenderContext ctx) {
        String[] names = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        return names[LocalDateTime.ofInstant(ctx.now(), AppTime.ZONE).getDayOfWeek().getValue() - 1];
    }

    private String uptime() {
        Duration d = Duration.between(startedAt, Instant.now());
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long minutes = d.toMinutes() % 60;
        if (days > 0) {
            return days + " 天 " + hours + " 小时";
        }
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分";
        }
        return Math.max(1, minutes) + " 分钟";
    }

    /**
     * 累计提问数。
     *
     * ⚠️ 原来取的是 {@code overview().total()}，即【全部群消息】—— 含没 @ 机器人的
     * 闲聊、含被拦的。群里 {@code {qa.total}} 回出来的数字因此大了一个数量级。
     * 提问的定义：@ 了机器人 + 机器人有效回应且未被拦截，即 {@code guard_action='pass'}。
     */
    private long totalQa(int days) {
        try {
            return analytics.overview(days).questions();
        } catch (Exception e) {
            return 0;
        }
    }

    private String hitRate() {
        try {
            double rate = analytics.overview(0).hitRate();
            return String.format("%.1f%%", rate * 100);
        } catch (Exception e) {
            return "—";
        }
    }

    /**
     * §{cmd.list}§ —— 渲染所有启用的指令。
     *
     * <p>这是 §/help§ 和 §/list§ 的实现方式（**不写死在代码里**），
     * 所以你可以自由调整文案，比如前面加一句「我是示例助手，可以这样用：」。
     */
    private String cmdList(boolean preview) {
        List<BotCommand> cmds = preview
                ? List.of(
                        new BotCommand(1, "help", "", "查看所有指令", "all", List.of(), "member", true, 1, true,
                                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB, List.of(), null, null),
                        new BotCommand(2, "list", "", "列出所有指令", "all", List.of(), "member", true, 2, true,
                                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB, List.of(), null, null),
                        new BotCommand(3, "ping", "", "看看我在不在", "all", List.of(), "member", true, 3, true,
                                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB, List.of(), null, null))
                : store.listEnabled();

        if (cmds.isEmpty()) {
            return "（还没有配置任何指令）";
        }
        StringBuilder sb = new StringBuilder();
        for (BotCommand c : cmds) {
            sb.append(c.display());
            if (c.description() != null && !c.description().isBlank()) {
                sb.append(" — ").append(c.description());
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String num(long v) {
        return String.format("%,d", v);
    }

    /**
     * 当前真实值 —— 变量页的「取当前值」按钮用。
     *
     * <p>管理端没有群会话，所以 {@code {user}} {@code {group}} 这类只能是占位值，
     * 而时刻、统计、机器人昵称是真的。这一点必须让看的人知道，
     * 所以变量页把「来源」列出来。
     */
    public Map<String, String> currentValues() {
        RenderContext ctx = RenderContext.scheduled(0L, 0L, false, "", "", Map.of(), "", Instant.now());
        Map<String, String> out = new LinkedHashMap<>();
        for (VarInfo v : available()) {
            out.put(v.name(), render("{" + v.name() + "}", ctx));
        }
        return out;
    }

    /** 供配置界面做"实时预览" */
    public Map<String, String> previewAll() {
        Map<String, String> out = new LinkedHashMap<>();
        for (VarInfo v : available()) {
            out.put(v.name(), render("{" + v.name() + "}", RenderContext.sample()));
        }
        return out;
    }
}
