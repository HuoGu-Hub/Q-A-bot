package com.example.qqbot.command;

/**
 * 板块 list / help 的默认文案。
 *
 * <p>后台预览与群里实际回复共用这一份，避免"预览是旧格式、真发出去是新格式"。
 */
public final class BoardText {

    private BoardText() {
    }

    public static String defaultList(Board board) {
        return "/" + board.path() + " " + CommandHelp.paramList(
                board.params(), "这个板块没有参数。");
    }

    public static String defaultHelp(Board board) {
        StringBuilder sb = new StringBuilder();
        sb.append("/").append(board.path()).append(" —— ").append(board.displayTitle())
                .append(board.isScheduled() ? "（到点发送）" : "（立即展示）").append('\n');
        sb.append("  增：/").append(board.path()).append(" /create ");
        for (ParamSpec spec : board.params()) {
            sb.append('#').append(spec.name()).append('-')
                    .append(spec.example() == null ? spec.name() : spec.example()).append("# ");
        }
        sb.append("内容\n");
        sb.append("  查：/").append(board.path()).append("\n");
        sb.append("  删：/").append(board.path()).append(" /delete 编号");
        sb.append("  参数：/").append(board.path()).append(" /list");
        if (!board.params().isEmpty()) {
            sb.append('\n').append("  参数：");
            for (ParamSpec spec : board.params()) {
                sb.append(spec.name());
                if (spec.required()) {
                    sb.append("(必填)");
                }
                if (spec.description() != null && !spec.description().isBlank()) {
                    sb.append(' ').append(spec.description());
                }
                sb.append("；");
            }
        }
        return sb.toString();
    }
}
