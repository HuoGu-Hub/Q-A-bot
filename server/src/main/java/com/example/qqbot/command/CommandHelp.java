package com.example.qqbot.command;

import org.springframework.stereotype.Component;

/**
 * 单命令的 /list 与 /help。
 *
 * <p>/list 默认列出这条命令自己的动作；/help 默认直接使用全局 help 的回复。
 * 两者都允许在后台单独填写，填写后优先用自己的。
 */
@Component
public class CommandHelp {

    private final VariableRenderer renderer;
    private final CommandCatalog catalog;
    private final SystemReplyStore replies;

    public CommandHelp(VariableRenderer renderer) {
        this(renderer, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CommandHelp(VariableRenderer renderer, CommandCatalog catalog, SystemReplyStore replies) {
        this.renderer = renderer;
        this.catalog = catalog;
        this.replies = replies;
    }

    /** 单命令动作清单：/路径 /list 回的那一段；手写优先 */
    public String list(BotCommand cmd) {
        if (cmd.listReply() != null && !cmd.listReply().isBlank()) {
            return cmd.listReply();
        }
        return defaultList(cmd);
    }

    /** 后台预览用：忽略手写内容，给出这条命令的默认动作清单 */
    public String defaultList(BotCommand cmd) {
        if (catalog != null) {
            return "prompt".equalsIgnoreCase(cmd.trigger())
                    ? catalog.promptModuleList() : catalog.commandModuleList(cmd);
        }
        String base = "/" + cmd.trigger().replace("/", " /");
        return base + "\n" + base + " /help\n" + base + " /list";
    }

    public String render(BotCommand cmd, RenderContext ctx) {
        if (cmd.help() != null && !cmd.help().isBlank()) {
            return renderer.render(cmd.help(), ctx);
        }
        return defaultHelp(cmd);
    }

    /** 后台预览用：没有手写 help 时显示全局 help */
    public String defaultHelp(BotCommand cmd) {
        String custom = replies == null ? "" : replies.get(SystemReplyStore.HELP);
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        if (catalog != null) {
            return catalog.helpText();
        }
        return autoHelp(cmd);
    }

    /** 无全局目录时的旧兜底，不参与正常运行时 */
    private static String autoHelp(BotCommand cmd) {
        StringBuilder sb = new StringBuilder();
        sb.append('/').append(cmd.trigger().replace("/", " /"));
        if (cmd.description() != null && !cmd.description().isBlank()) {
            sb.append(" — ").append(cmd.description());
        }
        sb.append('\n').append("用法：").append(cmd.display());
        if (!cmd.params().isEmpty()) {
            sb.append('\n').append("参数：");
            for (ParamSpec p : cmd.params()) {
                sb.append('\n').append("  #").append(p.name()).append('#');
                if (p.description() != null && !p.description().isBlank()) {
                    sb.append("  ").append(p.description());
                }
                sb.append(p.required() ? "  必填" : "  可选");
                if (p.example() != null && !p.example().isBlank()) {
                    sb.append("  例 ").append(p.example());
                }
                if (p.hasDefault()) {
                    sb.append("  默认 ").append(p.defaultValue());
                }
            }
            sb.append('\n').append("参数顺序随意，写在文本前面或后面都行。");
        }
        return sb.toString();
    }
}
