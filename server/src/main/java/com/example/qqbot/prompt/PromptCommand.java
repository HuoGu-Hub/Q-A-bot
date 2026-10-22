package com.example.qqbot.prompt;

import com.example.qqbot.command.CommandStore;
import com.example.qqbot.onebot.model.OneBotEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 个人补充提示命令：{@code /prompt /create 文本}。
 *
 * <p>它不经过命令表，也不走参数解析：提示内容里可能带 # 或空格，
 * 用通用参数解析反而会把原文拆坏。前缀与动作在这里拆，剩余部分原样保存。
 */
@Component
public class PromptCommand {

    private static final Logger log = LoggerFactory.getLogger(PromptCommand.class);
    private static final Pattern NUMBER = Pattern.compile("\\d+");

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
            return Optional.of(new Reply("个人提示暂时用不了"));
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
                case "/create" -> create(userId, arg);
                case "/list" -> list(userId);
                case "/delete" -> delete(userId, arg);
                case "/clear" -> clear(userId);
                default -> usage();
            }));
        } catch (PromptStore.PromptException e) {
            return Optional.of(new Reply(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("[PROMPT] 处理失败：{}", e.getMessage());
            return Optional.of(new Reply("刚才没处理成功，稍后再试"));
        }
    }

    private String create(long userId, String text) {
        PromptStore.Prompt prompt = prompts.create(userId, text);
        return "已添加 #" + prompt.id() + "：" + shorten(prompt.content())
                + "\n下次 @ 我时生效。";
    }

    private String list(long userId) {
        List<PromptStore.Prompt> items = prompts.list(userId);
        if (items.isEmpty()) {
            return "你还没有设置补充提示。\n" + usage();
        }
        StringBuilder sb = new StringBuilder("你的补充提示：\n");
        for (PromptStore.Prompt item : items) {
            sb.append('#').append(item.id()).append(' ').append(shorten(item.content())).append('\n');
        }
        return sb.append("删除：/prompt /delete 编号").toString();
    }

    private String delete(long userId, String arg) {
        java.util.regex.Matcher m = NUMBER.matcher(arg == null ? "" : arg);
        long id = m.find() ? Long.parseLong(m.group()) : 0L;
        if (id <= 0) {
            return "要删哪一条？例：/prompt /delete 3";
        }
        return prompts.delete(userId, id) ? "已删除 #" + id : "没有 #" + id + " 这一条";
    }

    private String clear(long userId) {
        int n = prompts.clear(userId);
        return n == 0 ? "你还没有设置补充提示" : "已清空 " + n + " 条补充提示";
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
                个人提示，只影响你自己：
                  /prompt /create 文本   添加
                  /prompt /list          查看
                  /prompt /delete 编号   删除
                  /prompt /clear         清空
                下次 @ 我时生效。
                """.stripTrailing();
    }
}
