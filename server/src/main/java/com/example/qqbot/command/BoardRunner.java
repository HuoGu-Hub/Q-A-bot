package com.example.qqbot.command;

import com.example.qqbot.onebot.model.OneBotEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 板块命令的执行：{@code /板块 /动作}。
 *
 * <h2>为什么不走命令表</h2>
 * 板块本身就是命令。再给每个板块生成五行命令配置，等于把同一件事写两遍，
 * 而且板块一改就要重新同步。这里直接按"第一段是不是板块名"分派。
 *
 * <h2>默认动作是查</h2>
 * 群友发 {@code /公告} 时问的是"有什么"，不是想创建。所以不带动作一律按查处理。
 */
@Component
public class BoardRunner {

    private static final Logger log = LoggerFactory.getLogger(BoardRunner.class);

    /** 一次最多列几条，防止把群刷屏 */
    private static final int LIST_LIMIT = 20;

    private final BoardStore boards;
    private final BoardRecordStore records;
    private final CommandParser parser;
    private final VariableRenderer renderer;
    private final AclStore aclStore;

    public BoardRunner(BoardStore boards, BoardRecordStore records, CommandParser parser,
                       VariableRenderer renderer, AclStore aclStore) {
        this.boards = boards;
        this.records = records;
        this.parser = parser;
        this.renderer = renderer;
        this.aclStore = aclStore;
    }

    /** 一句话结果：要么回一段文本，要么表示"这不是板块命令" */
    public record Reply(String text) {
    }

    /**
     * 试一試这条消息是不是板块命令。
     *
     * @return 空 = 不是板块命令，交给原来的命令表；有值 = 已经处理完，把文本发出去
     */
    public Optional<Reply> tryRun(OneBotEvent event, String body) {
        if (body == null) {
            return Optional.empty();
        }
        String trimmed = body.trim();
        // 群里常见 @机器人 /公告，先把前面那截 @ 去掉
        if (trimmed.startsWith("@")) {
            int space = trimmed.indexOf(' ');
            if (space > 0) {
                trimmed = trimmed.substring(space + 1).trim();
            }
        }
        if (!trimmed.startsWith("/")) {
            return Optional.empty();
        }
        // 第一段是板块名，第二段（可选）是动作
        String[] parts = trimmed.substring(1).split("\\s+", 2);
        String boardPath = parts[0];

        // /help 只说"怎么用"，/list 才列全部 —— 两者分工写在这里
        if ("help".equalsIgnoreCase(boardPath)) {
            return Optional.of(new Reply(globalHelp()));
        }
        if ("list".equalsIgnoreCase(boardPath)) {
            return Optional.of(new Reply(listAll()));
        }

        Board board = boards.find(boardPath);
        if (board == null || !board.enabled()) {
            return Optional.empty();
        }

        String rest = parts.length > 1 ? parts[1].trim() : "";
        String action = Board.DEFAULT_ACTION;
        if (rest.startsWith("/")) {
            String[] actionParts = rest.substring(1).split("\\s+", 2);
            action = Board.normalizeAction(actionParts[0]);
            rest = actionParts.length > 1 ? actionParts[1].trim() : "";
        }

        long userId = event.getUserId() == null ? 0L : event.getUserId();
        long groupId = event.getGroupId() == null ? 0L : event.getGroupId();
        boolean inGroup = event.isGroupMessage();

        if (!inGroup && !board.policy().allowPrivate()) {
            return Optional.of(new Reply("这条命令要在群里用"));
        }
        CommandAccess access = CommandAccess.of(board.access());
        if (!AccessRules.allows(access, CommandRole.ofSender(event), userId, groupId,
                List.of(), aclStore.global())) {
            return Optional.of(new Reply(access.describe(inGroup)));
        }
        if (!records.isAvailable()) {
            return Optional.of(new Reply("这个板块暂时用不了"));
        }

        return Optional.of(new Reply(switch (action) {
            case Board.ACT_CREATE -> create(board, event, userId, groupId, rest);
            case Board.ACT_DELETE -> delete(board, rest);
            case Board.ACT_HELP -> help(board);
            default -> query(board, groupId);
        }));
    }

    // ==================== 增 ====================

    private String create(Board board, OneBotEvent event, long userId, long groupId, String rest) {
        CommandArgs args;
        String text;
        try {
            CommandParser.Result parsed = parser.parse(rest, board.params(),
                    t -> renderer.render(t, renderer.contextOf(event, Map.of(), "", "")), Instant.now());
            args = parsed.args();
            text = parsed.text();
        } catch (CommandParseException e) {
            return e.getMessage();
        }
        if (text == null || text.isBlank()) {
            if (!board.isScheduled()) {
                return "还要写一句话：/" + board.path() + " /create 内容";
            }
        }
        try {
            long id = records.create(board, groupId, userId,
                    renderer.contextOf(event, Map.of(), "", "").nickname(), text,
                    args.rawValues(), args.moments(BoardRecordStore.PARAM_TIME),
                    Instant.now());
            BoardRecord saved = records.find(id);
            return "已记下 #" + id + "：" + saved.whenText() + " " + board.displayTitle()
                    + "「" + shorten(text) + "」";
        } catch (CommandParseException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.warn("[BOARD] 记录失败：{}", e.getMessage());
            return "没能记下来，稍后再试";
        }
    }

    // ==================== 查 ====================

    private String query(Board board, long groupId) {
        List<BoardRecord> list = records.query(board, groupId, LIST_LIMIT);
        if (list.isEmpty()) {
            return "「" + board.displayTitle() + "」还没有内容。要添加就用 /"
                    + board.path() + " /create";
        }
        StringBuilder sb = new StringBuilder("「").append(board.displayTitle()).append("」共 ")
                .append(list.size()).append(" 条：\n");
        for (BoardRecord record : list) {
            sb.append(record.displayLine()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ==================== 删 ====================

    private String delete(Board board, String rest) {
        long id = firstNumber(rest);
        if (id <= 0) {
            return "要删哪一条？写编号，例：/" + board.path() + " /delete 12";
        }
        BoardRecord record = records.find(id);
        if (record == null || !board.path().equals(record.board()) || record.isDeleted()) {
            return "没有 #" + id + " 这一条";
        }
        if (!records.softDelete(id, Instant.now())) {
            return "没有 #" + id + " 这一条";
        }
        return "已撤销 #" + id + "（数据还在，可以恢复）";
    }

    // ==================== 帮助 ====================

    private String help(Board board) {
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

    // ==================== 全局 ====================

    private String globalHelp() {
        return """
                指令都按「/板块 /动作」使用：
                  /板块            查看这个板块有什么
                  /板块 /create    添加一条
                  /板块 /delete    撤销一条
                  /板块 /help      这个板块收什么参数
                发 /list 看全部板块。
                """.strip();
    }

    private String listAll() {
        List<Board> enabled = boards.listEnabled();
        if (enabled.isEmpty()) {
            return "还没有任何板块";
        }
        StringBuilder sb = new StringBuilder("全部板块：\n");
        for (Board board : enabled) {
            sb.append("/").append(board.path()).append("  ").append(board.displayTitle());
            if (!board.params().isEmpty()) {
                sb.append("  （");
                for (int i = 0; i < board.params().size(); i++) {
                    if (i > 0) {
                        sb.append("、");
                    }
                    sb.append(board.params().get(i).name());
                }
                sb.append("）");
            }
            sb.append('\n');
        }
        return sb.append("用 /板块 /help 看某一项怎么用。").toString();
    }

    // ==================== 小工具 ====================

    /** 从 "12" 或 "#编号-12#" 里取出编号 */
    private static long firstNumber(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(text == null ? "" : text);
        return m.find() ? Long.parseLong(m.group()) : 0L;
    }

    private static String shorten(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 30 ? oneLine : oneLine.substring(0, 30) + "…";
    }
}
