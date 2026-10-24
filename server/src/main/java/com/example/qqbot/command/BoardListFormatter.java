package com.example.qqbot.command;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 板块列表排版。
 *
 * <p>管理员在后台填三段文本：列表头、每条格式、空列表文案；这里按记录循环渲染。
 * 变量故意只做简单替换，不接 {@link VariableRenderer} —— 后者属于指令参数上下文，
 * 把两套变量混在一起，出错时很难判断是哪一个占位符的问题。
 */
final class BoardListFormatter {

    /** 默认只显示 20 条；0 = 全部，但仍受 MAX_LIMIT 与消息长度保护 */
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 200;
    /** 与 app.commands.max-reply-length 的默认值一致，防止一条列表刷屏 */
    static final int MAX_MESSAGE_CHARS = 1500;

    private static final Pattern VAR = Pattern.compile("\\{([^{}]+)}");

    private BoardListFormatter() {
    }

    static String render(Board board, List<BoardRecord> records) {
        return render(board, records, 0L);
    }

    /**
     * 渲染列表，并标注跨群来源。
     *
     * @param viewerGroupId 发起查询的群；私聊或未知传 0。<b>本群记录不标注</b> ——
     *                      「来自本群」对看的人没有信息量，只有别群来的才需要
     */
    static String render(Board board, List<BoardRecord> records, long viewerGroupId) {
        if (records == null || records.isEmpty()) {
            return renderEmpty(board);
        }

        String header = board.listHeader().isBlank()
                ? "「" + board.displayTitle() + "」共 " + records.size() + " 条："
                : fill(board.listHeader(), Map.of(
                        "count", String.valueOf(records.size()),
                        "path", board.path()));
        if (header.isBlank()) {
            header = "「" + board.displayTitle() + "」共 " + records.size() + " 条：";
        }
        StringBuilder sb = new StringBuilder(trimTo(header, MAX_MESSAGE_CHARS));

        int shown = 0;
        for (BoardRecord record : records) {
            String line = board.listItem().isBlank()
                    ? record.displayLine()
                    : fill(board.listItem(), vars(record, board, records.size()));
            line = line == null ? "" : line.stripTrailing();
            line = markOrigin(line, board, record, viewerGroupId);
            if (line.isEmpty()) {
                continue;
            }
            int remaining = MAX_MESSAGE_CHARS - sb.length() - 1;
            if (remaining <= 0) {
                break;
            }
            if (line.length() > remaining) {
                sb.append('\n').append(line, 0, Math.max(0, remaining - 1)).append('…');
                shown++;
                break;
            }
            sb.append('\n').append(line);
            shown++;
        }

        if (shown < records.size()) {
            String more = "\n…还有 " + (records.size() - shown) + " 条未显示";
            if (sb.length() + more.length() <= MAX_MESSAGE_CHARS) {
                sb.append(more);
            }
        }
        return sb.toString().stripTrailing();
    }

    static String renderEmpty(Board board) {
        if (board.listEmpty().isBlank()) {
            return "「" + board.displayTitle() + "」还没有内容。要添加就用 /"
                    + board.path() + " /create";
        }
        return fill(board.listEmpty(), Map.of("count", "0", "path", board.path())).stripTrailing();
    }

    /**
     * 跨群结果的来源标注。
     *
     * <p>两个开关都按板块配，落在 {@link DataPolicy#marksGroup()} /
     * {@link DataPolicy#marksUser()} 上；本群记录一律不标，免得列表里每条都挂着
     * 「来自本群」。
     */
    private static String markOrigin(String line, Board board, BoardRecord record, long viewerGroupId) {
        if (line.isEmpty() || record.groupId() <= 0) {
            return line;
        }
        boolean foreign = viewerGroupId <= 0 || record.groupId() != viewerGroupId;
        if (!foreign) {
            return line;
        }
        StringBuilder sb = new StringBuilder(line);
        if (board.policy().marksGroup()) {
            sb.append(" · 来自 ").append(record.groupId());
        }
        if (board.policy().marksUser()) {
            sb.append(" · QQ ").append(record.userId());
        }
        return sb.toString();
    }

    private static Map<String, String> vars(BoardRecord record, Board board, int count) {
        Map<String, String> vars = new HashMap<>();
        vars.put("id", String.valueOf(record.id()));
        vars.put("when", record.whenText());
        vars.put("text", body(record));
        vars.put("nickname", record.nickname() == null ? "" : record.nickname());
        vars.put("group", String.valueOf(record.groupId()));
        // QQ 号没有独立开关：勾了「显示 QQ」才认这个变量，否则原样保留，
        // 与 VariableRenderer 对未启用变量的处理一致（不静默变空）
        vars.put("user.id", board.policy().marksUser() ? String.valueOf(record.userId()) : null);
        vars.put("count", String.valueOf(count));
        vars.put("path", board.path());
        // 「重复」按周期显示（库里是 daily / weekly:3），其余参数原样
        for (String name : record.params().keySet()) {
            vars.put("params." + name, record.paramText(name));
        }
        return vars;
    }

    private static String body(BoardRecord record) {
        if (record.text() != null && !record.text().isBlank()) {
            return record.text();
        }
        if (record.params().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : record.params().keySet()) {
            String value = record.paramText(name);
            if (value.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private static String fill(String template, Map<String, String> vars) {
        Matcher matcher = VAR.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = vars.get(key);
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(value == null ? matcher.group(0) : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String trimTo(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }
}
