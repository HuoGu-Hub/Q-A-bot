package com.example.qqbot.command;

import org.springframework.stereotype.Component;

/**
 * 单命令帮助 —— 群里发 {@code /路径 /help} 时回的那一段。
 *
 * <p>内容优先用手写的 {@code help} 字段，没写就按参数表自动生成。
 * 自动生成的意义在于：<b>参数表改了，帮助就跟着变</b>，
 * 不会出现「文档说要传 #time#，代码其实已经不要了」这种漂移。
 */
@Component
public class CommandHelp {

    private final VariableRenderer renderer;

    public CommandHelp(VariableRenderer renderer) {
        this.renderer = renderer;
    }

    public String render(BotCommand cmd, RenderContext ctx) {
        if (cmd.help() != null && !cmd.help().isBlank()) {
            return renderer.render(cmd.help(), ctx);
        }

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
