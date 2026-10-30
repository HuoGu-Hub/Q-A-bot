package com.example.qqbot.prompt;

import com.example.qqbot.command.AccessRules;
import com.example.qqbot.command.AclStore;
import com.example.qqbot.command.BotCommand;
import com.example.qqbot.command.CommandAccess;
import com.example.qqbot.command.CommandRole;
import com.example.qqbot.command.CommandCatalog;
import com.example.qqbot.command.CommandStore;
import com.example.qqbot.onebot.model.OneBotEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 补充提示命令：{@code /prompt /create 文本}。
 *
 * <p>默认按当前群保存；显式写 {@code #user_id-QQ号#} 时才按 QQ 号保存。
 * 它不经过命令表，也不走通用参数解析：提示内容里的 # 与空格都要原样保留。
 */
@Component
public class PromptCommand {

    private static final Logger log = LoggerFactory.getLogger(PromptCommand.class);
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern USER_ID = Pattern.compile(
            "#\\s*(?:user_id|用户id|qq)\\s*[-－—]\\s*(\\d+)\\s*#",
            Pattern.CASE_INSENSITIVE);

    private final PromptStore prompts;
    private final CommandStore commandStore;
    private final AclStore aclStore;
    private final CommandCatalog catalog;
    private final com.example.qqbot.command.SystemReplyStore replies;

    /** 兼容旧测试：没有目录时 /prompt /list 回退成用法 */
    public PromptCommand(PromptStore prompts, CommandStore commandStore, AclStore aclStore) {
        this(prompts, commandStore, aclStore, null, null);
    }

    public PromptCommand(PromptStore prompts, CommandStore commandStore, AclStore aclStore,
                         CommandCatalog catalog) {
        this(prompts, commandStore, aclStore, catalog, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PromptCommand(PromptStore prompts, CommandStore commandStore, AclStore aclStore,
                         CommandCatalog catalog, com.example.qqbot.command.SystemReplyStore replies) {
        this.prompts = prompts;
        this.commandStore = commandStore;
        this.aclStore = aclStore;
        this.catalog = catalog;
        this.replies = replies;
    }

    public record Reply(String text) {
    }

    public Optional<Reply> tryRun(OneBotEvent event, String body) {
        String text = normalize(body);
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.equals("/prompt") && !lower.startsWith("/prompt ")) {
            return Optional.empty();
        }

        long userId = event.getUserId() == null ? 0L : event.getUserId();
        long groupId = event.getGroupId() == null ? 0L : event.getGroupId();
        BotCommand cmd = commandStore.findByTrigger("prompt");
        if (cmd == null) {
            return Optional.of(new Reply("补充提示暂时用不了"));
        }
        if (!cmd.enabled()) {
            return Optional.of(new Reply("补充提示已停用"));
        }
        // 与其他命令同一套判定：单命令名单优先，其次全局名单，最后看档位
        if (!AccessRules.allows(CommandAccess.of(cmd.access()), CommandRole.ofSender(event),
                userId, groupId, aclStore.ofCommand(cmd.id()), aclStore.global())) {
            return Optional.of(new Reply("这条命令你暂时用不了"));
        }
        commandStore.logUsage("prompt", groupId, userId, true);
        if (!prompts.isAvailable()) {
            return Optional.of(new Reply("补充提示暂时用不了"));
        }

        String rest = text.substring("/prompt".length()).trim();
        // 不带动作 = 查看这个板块当前的数据；/help、/list 各自是命令
        if (rest.isEmpty()) {
            return Optional.of(new Reply(list(groupId, userId)));
        }
        if ("/help".equalsIgnoreCase(rest)) {
            return Optional.of(new Reply(help(cmd)));
        }

        String[] parts = rest.split("\\s+", 2);
        String action = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        try {
            return Optional.of(new Reply(switch (action) {
                case "/create" -> create(event, userId, groupId, arg);
                case "/list" -> listCommand(cmd);
                case "/delete" -> delete(event, userId, groupId, arg);
                case "/clear" -> clear(event, userId, groupId);
                default -> usage();
            }));
        } catch (PromptStore.PromptException e) {
            return Optional.of(new Reply(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("[PROMPT] 处理失败：{}", e.getMessage());
            return Optional.of(new Reply("刚才没处理成功，稍后再试"));
        }
    }

    private String create(OneBotEvent event, long userId, long groupId, String arg) {
        String text = arg == null ? "" : arg;
        Matcher matcher = USER_ID.matcher(text);
        Long targetUserId = null;
        if (matcher.find()) {
            targetUserId = Long.valueOf(matcher.group(1));
            text = (text.substring(0, matcher.start()) + " " + text.substring(matcher.end())).trim();
        }

        PromptStore.Scope scope;
        long ownerUser = 0L;
        long ownerGroup = 0L;

        if (targetUserId != null) {
            if (targetUserId != userId && !canManageGroup(event)) {
                return "只有群主或管理员才能给别人设置提示";
            }
            scope = PromptStore.Scope.USER;
            ownerUser = targetUserId;
        } else if (event.isGroupMessage() && groupId > 0) {
            if (!canManageGroup(event)) {
                return "本群提示只有群主或管理员能设置";
            }
            scope = PromptStore.Scope.GROUP;
            ownerGroup = groupId;
        } else {
            scope = PromptStore.Scope.USER;
            ownerUser = userId;
        }

        PromptStore.Prompt prompt = prompts.create(scope, ownerGroup, ownerUser, text);
        String scopeName = scope == PromptStore.Scope.GROUP ? "本群提示" : "个人提示";
        return "已添加" + scopeName + " #" + prompt.id() + "：" + shorten(prompt.content())
                + "\n下次 @ 我时生效。";
    }

    /** /prompt /list：这个板块自己的命令清单 */
    private String listCommand(BotCommand cmd) {
        if (cmd.listReply() != null && !cmd.listReply().isBlank()) {
            return cmd.listReply();
        }
        return catalog == null ? usage() : catalog.promptModuleList();
    }

    private String help(BotCommand cmd) {
        if (cmd.help() != null && !cmd.help().isBlank()) {
            return cmd.help();
        }
        String custom = replies == null ? "" : replies.get(com.example.qqbot.command.SystemReplyStore.HELP);
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        return catalog == null ? usage() : catalog.helpText();
    }

    /** 直接 /prompt：当前生效的提示词，按序号展示 */
    private String list(long groupId, long userId) {
        List<PromptStore.Prompt> items = prompts.listFor(groupId, userId);
        if (items.isEmpty()) {
            return "当前没有生效的提示词。";
        }
        StringBuilder sb = new StringBuilder("当前生效的提示词为：");
        int index = 1;
        for (PromptStore.Prompt item : items) {
            sb.append('\n').append(index++).append(". ").append(shorten(item.content()));
        }
        return sb.toString();
    }

    private String delete(OneBotEvent event, long userId, long groupId, String arg) {
        long id = firstNumber(arg);
        if (id <= 0) {
            return "要删哪一条？例：/prompt /delete 3";
        }
        PromptStore.Prompt prompt = prompts.find(id);
        if (prompt == null) {
            return "没有 #" + id + " 这一条";
        }
        if (prompt.scope() == PromptStore.Scope.GROUP) {
            if (!canManageGroup(event)) {
                return "本群提示只有群主或管理员能删除";
            }
            if (prompt.groupId() != groupId) {
                return "这条提示不属于当前群";
            }
        } else if (prompt.userId() != userId) {
            return "只能删自己的提示";
        }
        return prompts.delete(id) ? "已删除 #" + id : "没有 #" + id + " 这一条";
    }

    private String clear(OneBotEvent event, long userId, long groupId) {
        boolean group = event.isGroupMessage() && groupId > 0;
        if (group && !canManageGroup(event)) {
            int n = prompts.clearUser(userId);
            return n == 0
                    ? "本群提示只有群主或管理员能清空"
                    : "已清空 " + n + " 条个人提示；本群提示只有群主或管理员能清空";
        }
        int n = 0;
        if (group) {
            n += prompts.clearGroup(groupId);
        }
        n += prompts.clearUser(userId);
        return n == 0 ? "没有可清空的提示" : "已清空 " + n + " 条补充提示";
    }

    private static boolean canManageGroup(OneBotEvent event) {
        return CommandRole.ofSender(event).atLeast(CommandRole.ADMIN);
    }

    private static long firstNumber(String arg) {
        Matcher m = NUMBER.matcher(arg == null ? "" : arg);
        return m.find() ? Long.parseLong(m.group()) : 0L;
    }

    private static String normalize(String body) {
        String text = body == null ? "" : body.trim();
        if (text.startsWith("@")) {
            int space = text.indexOf(' ');
            if (space > 0) {
                text = text.substring(space + 1).trim();
            }
        }
        return text;
    }

    private static String shorten(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }

    private static String usage() {
        return """
                /prompt              查看当前生效的提示
                /prompt /create 文本  默认加到本群
                /prompt /create #user_id-QQ号# 文本   只给某个人
                /prompt /list        查看补充提示的动作
                /prompt /delete 编号
                /prompt /clear
                下次 @ 我时生效。
                """.stripTrailing();
    }
}
