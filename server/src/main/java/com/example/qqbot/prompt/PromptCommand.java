package com.example.qqbot.prompt;

import com.example.qqbot.command.CommandRole;
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

    public PromptCommand(PromptStore prompts, CommandStore commandStore) {
        this.prompts = prompts;
        this.commandStore = commandStore;
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
        commandStore.logUsage("prompt", groupId, userId, true);
        if (!prompts.isAvailable()) {
            return Optional.of(new Reply("补充提示暂时用不了"));
        }

        String rest = text.substring("/prompt".length()).trim();
        if (rest.isEmpty() || "/help".equalsIgnoreCase(rest)) {
            return Optional.of(new Reply(usage()));
        }

        String[] parts = rest.split("\\s+", 2);
        String action = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        try {
            return Optional.of(new Reply(switch (action) {
                case "/create" -> create(event, userId, groupId, arg);
                case "/list" -> list(groupId, userId);
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

        CommandRole role = CommandRole.ofSender(event);
        PromptStore.Scope scope;
        long ownerUser = 0L;
        long ownerGroup = 0L;

        if (targetUserId != null) {
            if (targetUserId != userId && !role.atLeast(CommandRole.ADMIN)) {
                return "只有群主或管理员能给别人设置提示";
            }
            scope = PromptStore.Scope.USER;
            ownerUser = targetUserId;
        } else if (event.isGroupMessage() && groupId > 0) {
            if (!role.atLeast(CommandRole.ADMIN)) {
                return "本群提示需要群主或管理员设置。\n"
                        + "只给自己设置：/prompt /create #user_id-" + userId + "# 文本";
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

    private String list(long groupId, long userId) {
        List<PromptStore.Prompt> items = prompts.listFor(groupId, userId);
        if (items.isEmpty()) {
            return "还没有补充提示。\n" + usage();
        }
        StringBuilder sb = new StringBuilder();
        List<PromptStore.Prompt> group = items.stream()
                .filter(p -> p.scope() == PromptStore.Scope.GROUP).toList();
        List<PromptStore.Prompt> user = items.stream()
                .filter(p -> p.scope() == PromptStore.Scope.USER).toList();
        if (!group.isEmpty()) {
            sb.append("本群提示：\n");
            for (PromptStore.Prompt item : group) {
                sb.append('#').append(item.id()).append(' ').append(shorten(item.content())).append('\n');
            }
        }
        if (!user.isEmpty()) {
            sb.append("我的提示：\n");
            for (PromptStore.Prompt item : user) {
                sb.append('#').append(item.id()).append(' ').append(shorten(item.content())).append('\n');
            }
        }
        return sb.append("删除：/prompt /delete 编号").toString();
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
        CommandRole role = CommandRole.ofSender(event);
        boolean admin = role.atLeast(CommandRole.ADMIN);
        if (prompt.scope() == PromptStore.Scope.GROUP) {
            if (prompt.groupId() != groupId) {
                return "这条提示不属于当前群";
            }
            if (!admin) {
                return "本群提示只有群主或管理员能删";
            }
        } else if (prompt.userId() != userId && !admin) {
            return "只能删自己的提示";
        }
        return prompts.delete(id) ? "已删除 #" + id : "没有 #" + id + " 这一条";
    }

    private String clear(OneBotEvent event, long userId, long groupId) {
        CommandRole role = CommandRole.ofSender(event);
        int n = 0;
        if (event.isGroupMessage() && groupId > 0 && role.atLeast(CommandRole.ADMIN)) {
            n += prompts.clearGroup(groupId);
        }
        n += prompts.clearUser(userId);
        if (n == 0) {
            return event.isGroupMessage()
                    ? "没有可清空的提示。本群提示只有群主或管理员能清"
                    : "没有可清空的提示";
        }
        return "已清空 " + n + " 条补充提示";
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
                默认按本群保存：
                  /prompt /create 文本                  群主或管理员
                  /prompt /create #user_id-QQ号# 文本   只给某个人
                  /prompt /list
                  /prompt /delete 编号
                  /prompt /clear
                下次 @ 我时生效。
                """.stripTrailing();
    }
}
