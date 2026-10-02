package com.example.qqbot.command;

import com.example.qqbot.config.CommandProperties;
import com.example.qqbot.kb.Glossary;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.qa.QaAnalytics;
import com.example.qqbot.onebot.model.OneBotEvent;
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
 * 变量渲染 —— 把回复模板里的 §{变量}§ 替换成实际值。
 *
 * <p>这是"指令能动态获取信息"的核心。变量分四类：
 * <ol>
 *   <li><b>上下文</b> —— {user} {group} {bot}</li>
 *   <li><b>时间</b> —— {time} {date} {weekday} {uptime}</li>
 *   <li><b>统计</b> —— {kb.count} {qa.total} {qa.hitrate} …</li>
 *   <li><b>指令</b> —— {cmd.total} {cmd.list}</li>
 * </ol>
 *
 * <p><b>三条安全约束</b>：
 * <ol>
 *   <li>**不提供** {config.*} {env.*} {server.*} 这类能读系统信息的变量</li>
 *   <li>变量值**原样输出，不二次解析** —— 防止模板注入
 *       （用户昵称里如果写了 {cmd.list}，不会被再解析一次）</li>
 *   <li>未知变量**原样保留**，方便发现拼错</li>
 * </ol>
 */
@Component
public class VariableRenderer {

    private static final Logger log = LoggerFactory.getLogger(VariableRenderer.class);

    /** §{xxx}§ —— 变量名只允许字母数字点下划线 */
    private static final Pattern VAR = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_.]*)}");

    /** §{{§ 和 §}}§ 转义成字面量 */
    private static final String ESC_OPEN = "\\u0000LBRACE\\u0000";
    private static final String ESC_CLOSE = "\\u0000RBRACE\\u0000";

    private final CommandProperties props;
    private final CommandStore store;
    private final KbBlockStore blockStore;
    private final Glossary glossary;
    private final QaAnalytics analytics;
    private final com.example.qqbot.config.BotProperties botProps;

    private final Instant startedAt = Instant.now();

    public VariableRenderer(CommandProperties props, CommandStore store,
                            KbBlockStore blockStore, Glossary glossary, QaAnalytics analytics,
                            com.example.qqbot.config.BotProperties botProps) {
        this.props = props;
        this.store = store;
        this.blockStore = blockStore;
        this.glossary = glossary;
        this.analytics = analytics;
        this.botProps = botProps;
    }

    /** 变量说明（给配置界面用） */
    public record VarInfo(String name, String label, String example, boolean advanced) {
    }

    /** 全部可用变量 —— 配置界面靠它渲染"点击插入"按钮 */
    public List<VarInfo> available() {
        List<VarInfo> list = new java.util.ArrayList<>(List.of(
                new VarInfo("user", "提问者昵称", "示例用户", false),
                new VarInfo("group", "群名称", "示例玩家群", false),
                new VarInfo("bot", "机器人昵称", "示例助手", false),
                new VarInfo("time", "当前时间", "14:32", false),
                new VarInfo("date", "今天日期", "2026-09-25", false),
                new VarInfo("datetime", "完整时间", "2026-09-25 14:32:07", false),
                new VarInfo("weekday", "星期", "星期四", false),
                new VarInfo("uptime", "已运行时长", "3 小时 12 分", false),
                // ⚠️ 预览值必须是真实量级：kb.count 原来写 4,238，磁盘实际是 4,131
                new VarInfo("kb.count", "知识库条目数", "4,131", false),
                new VarInfo("kb.terms", "术语表词条数", "3,359", false),
                // 提问 = @ 了机器人 + 有效回应且未被拦截（guard_action='pass'）
                new VarInfo("qa.total", "累计提问数", "128", false),
                new VarInfo("qa.today", "今日提问数", "12", false),
                new VarInfo("qa.hitrate", "检索命中率", "87.3%", false),
                new VarInfo("cmd.total", "指令总数", "4", false),
                new VarInfo("cmd.list", "指令列表（含说明）", "可用指令：…", false),
                // 命令参数：/报名 张三 18 → {args}="张三 18"、{args.1}="张三"、{args.2}="18"
                new VarInfo("args", "命令参数（全部）", "张三 18", false),
                new VarInfo("args.1", "第 1 个参数", "张三", false),
                new VarInfo("args.2", "第 2 个参数", "18", false)));

        if (props.isAllowUserIds()) {
            list.add(new VarInfo("user.id", "提问者 QQ 号", "100000005", true));
            list.add(new VarInfo("group.id", "群号", "100000004", true));
        }
        return list;
    }

    /**
     * 渲染模板。
     *
     * @param template 含 §{变量}§ 的模板
     * @param event    当前消息事件（可能为 null —— 预览场景）
     * @param preview  预览模式：用占位值渲染，不查真实统计
     */
    public String render(String template, OneBotEvent event, boolean preview) {
        return render(template, event, preview, "");
    }

    /**
     * 渲染模板（带命令参数）。
     *
     * @param args {@code /命令} 后面的全部内容 —— 供 {@code {args}} / {@code {args.N}} 取用
     */
    public String render(String template, OneBotEvent event, boolean preview, String args) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        // 1) 先把 {{ }} 保护起来，避免被当成变量
        String text = template.replace("{{", ESC_OPEN).replace("}}", ESC_CLOSE);

        // 2) 逐个替换
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            String value = resolve(name, event, preview, args);
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

    /** 解析一个变量；返回 null 表示"未知变量，原样保留" */
    private String resolve(String name, OneBotEvent event, boolean preview, String args) {
        // 高级变量：未开启时给出明确提示，而不是静默失败
        if (!props.isAllowUserIds() && (name.equals("user.id") || name.equals("group.id"))) {
            return "（未启用）";
        }

        // 命令参数：{args} 取全部，{args.N} 取第 N 个词（小活动靠它）
        if (name.equals("args")) {
            return preview ? "张三 18" : argsTrimmed(args);
        }
        if (name.startsWith("args.")) {
            int idx = parseIndex(name.substring("args.".length()));
            if (idx <= 0) {
                // "{args.x}" / "{args.0}" 不是合法序号 → 当未知变量，**原样保留**，
                // 方便在群里一眼看出拼错了（返回空串会让它静默消失，最难查）
                return null;
            }
            if (preview) {
                return idx == 1 ? "张三" : idx == 2 ? "18" : "参数" + idx;
            }
            String[] words = argsTrimmed(args).split("\\s+");
            return idx <= words.length && !words[idx - 1].isBlank() ? words[idx - 1] : "";
        }

        return switch (name) {
            case "user" -> preview ? "示例用户" : nickname(event);
            case "group" -> preview ? "示例玩家群" : groupName(event);
            case "bot" -> preview ? "示例助手" : botName(event);
            case "user.id" -> String.valueOf(event == null ? 100000005L : orZero(event.getUserId()));
            case "group.id" -> String.valueOf(event == null ? 100000004L : orZero(event.getGroupId()));

            case "time" -> now("HH:mm");
            case "date" -> now("yyyy-MM-dd");
            case "datetime" -> now("yyyy-MM-dd HH:mm:ss");
            case "weekday" -> weekday();
            case "uptime" -> uptime();

            case "kb.count" -> preview ? "4,131" : num(blockStore.isAvailable() ? blockStore.count() : 0);
            case "kb.terms" -> preview ? "3,359" : num(glossary.size());

            case "qa.total" -> preview ? "128" : num(totalQa(0));
            case "qa.today" -> preview ? "12" : num(totalQa(1));
            case "qa.hitrate" -> preview ? "87.3%" : hitRate();

            case "cmd.total" -> preview ? "4" : num(store.listEnabled().size());
            case "cmd.list" -> cmdList(preview);

            default -> null;
        };
    }

    // ==================== 各项取值 ====================

    private String nickname(OneBotEvent event) {
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

    private String groupName(OneBotEvent event) {
        if (event == null) {
            return "本群";
        }
        // 群名称不在消息事件里，用群号代替（避免为了显示名字多调一次 API）
        return event.isGroupMessage() ? "本群(" + orZero(event.getGroupId()) + ")" : "私聊";
    }

    private String botName(OneBotEvent event) {
        return botProps.getName();
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
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

    private String now(String pattern) {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern));
    }

    private String weekday() {
        String[] names = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        return names[LocalDateTime.now().getDayOfWeek().getValue() - 1];
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
     * 所以你可以自由调整文案，比如前面加一句「我是飘雪喵，可以这样用：」。
     */
    private String cmdList(boolean preview) {
        List<BotCommand> cmds = preview
                ? List.of(
                        new BotCommand(1, "help", "", "查看所有指令", "all", List.of(), "member", true, 1, true,
                                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB),
                        new BotCommand(2, "list", "", "列出所有指令", "all", List.of(), "member", true, 2, true,
                                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB),
                        new BotCommand(3, "ping", "", "看看我在不在", "all", List.of(), "member", true, 3, true,
                                BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB))
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

    /** 供配置界面做"实时预览" */
    public Map<String, String> previewAll() {
        Map<String, String> out = new LinkedHashMap<>();
        for (VarInfo v : available()) {
            out.put(v.name(), render("{" + v.name() + "}", null, true));
        }
        return out;
    }
}
