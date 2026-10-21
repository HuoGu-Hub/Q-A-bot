package com.example.qqbot.command;

import org.springframework.stereotype.Component;

import java.util.List;

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

    /** 单命令参数表：{@code /路径 /list} 回的那一段 */
    public String list(BotCommand cmd) {
        String head = "/" + cmd.trigger().replace("/", " /") + " ";
        return head + paramList(cmd.params(), "没有参数。");
    }

    /**
     * 参数表统一格式。板块的 {@code /板块 /list} 也用这一份，
     * 免得命令和板块对同一个参数给出两种说法。
     */
    public static String paramList(List<ParamSpec> params, String emptyText) {
        if (params == null || params.isEmpty()) {
            return emptyText;
        }
        StringBuilder sb = new StringBuilder("支持 ").append(params.size()).append(" 个参数：");
        for (ParamSpec p : params) {
            sb.append('\n').append("  #").append(p.name()).append("#  ").append(typeLabel(p.type()));
            sb.append(p.required() ? "  必填" : "  可选");
            if (p.multiple()) {
                sb.append("  可多值，用顿号隔开");
            }
            if (p.hasDefault()) {
                sb.append("  默认 ").append(p.defaultValue());
            }
            if (p.description() != null && !p.description().isBlank()) {
                sb.append("  ").append(p.description());
            }
            if (p.example() != null && !p.example().isBlank()) {
                sb.append("  例 ").append(p.example());
            }
            if (!p.options().isEmpty()) {
                sb.append("  可选值：").append(String.join(" / ", p.options()));
            }
        }
        sb.append('\n').append("写法：#名字-值#，多个参数顺序随意，写在文本前后都行。");
        return sb.toString();
    }

    private static String typeLabel(ParamType type) {
        return switch (type) {
            case INT -> "整数";
            case MOMENT -> "时间";
            case DATE -> "日期";
            case ENUM -> "枚举";
            default -> "文本";
        };
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
