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

    private final BoardStore boards;
    private final BoardRecordStore records;
    private final CommandParser parser;
    private final VariableRenderer renderer;
    private final AclStore aclStore;
    private final CommandCatalog catalog;
    private final CommandStore commandStore;
    private final SystemReplyStore replies;

    @org.springframework.beans.factory.annotation.Autowired
    public BoardRunner(BoardStore boards, BoardRecordStore records, CommandParser parser,
                       VariableRenderer renderer, AclStore aclStore, CommandCatalog catalog,
                       CommandStore commandStore, SystemReplyStore replies) {
        this.boards = boards;
        this.records = records;
        this.parser = parser;
        this.renderer = renderer;
        this.aclStore = aclStore;
        this.catalog = catalog;
        this.commandStore = commandStore;
        this.replies = replies;
    }

    /** 兼容旧测试：不接全局回复存储 */
    public BoardRunner(BoardStore boards, BoardRecordStore records, CommandParser parser,
                       VariableRenderer renderer, AclStore aclStore, CommandCatalog catalog,
                       CommandStore commandStore) {
        this(boards, records, parser, renderer, aclStore, catalog, commandStore, null);
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

        // /help 只说"怎么用"，/list 才列全部 —— 两份文案都由 CommandCatalog 生成，
        // 后台看到的目录与群里收到的是同一份数据，避免各写一套后漂移。
        if ("help".equalsIgnoreCase(boardPath)) {
            logUsage(event, "help");
            return Optional.of(new Reply(globalReply(SystemReplyStore.HELP, catalog.helpText(), event)));
        }
        if ("list".equalsIgnoreCase(boardPath)) {
            logUsage(event, "list");
            return Optional.of(new Reply(globalReply(SystemReplyStore.LIST, catalog.listText(), event)));
        }
        // create / delete 是板块动作，不占全局命令名；给一句明确指路，避免发出去没反应
        if ("create".equalsIgnoreCase(boardPath) || "delete".equalsIgnoreCase(boardPath)) {
            return Optional.of(new Reply(
                    "create / delete 要配板块用：/板块 /create ...、/板块 /delete 编号"));
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

        // 板块也是命令：使用情况与普通指令记在同一张表里，后台才能按命令看总次数
        logUsage(event, board.path());

        if (!inGroup && !board.policy().allowPrivate()) {
            return Optional.of(new Reply("这条命令要在群里用"));
        }
        CommandAccess access = CommandAccess.of(board.access());
        if (!AccessRules.allows(access, CommandRole.ofSender(event), userId, groupId,
                List.of(), aclStore.global())) {
            return Optional.of(new Reply(access.describe(inGroup)));
        }
        // 看命令、看用法不碰记录表，记录库暂时不可用也该能回答
        if (!records.isAvailable() && !Board.ACT_HELP.equals(action)
                && !Board.ACT_LIST.equals(action)) {
            return Optional.of(new Reply("这个板块暂时用不了"));
        }

        return Optional.of(new Reply(switch (action) {
            case Board.ACT_CREATE -> create(board, event, userId, groupId, rest);
            case Board.ACT_DELETE -> delete(board, rest);
            case Board.ACT_LIST -> list(board);
            case Board.ACT_HELP -> help(board, event);
            default -> query(board, groupId);
        }));
    }

    /** 全局回复只在用户明确编辑过时覆盖自动目录 */
    private String globalReply(String key, String fallback, OneBotEvent event) {
        String custom = replies == null ? "" : replies.get(key);
        if (custom == null || custom.isBlank()) {
            return fallback;
        }
        return renderer.render(custom, renderer.contextOf(event, Map.of(), "", ""));
    }

    /** 记一次命令使用（含被权限拦下的），与 CommandHandler 的记账口径一致 */
    private void logUsage(OneBotEvent event, String trigger) {
        long userId = event.getUserId() == null ? 0L : event.getUserId();
        long groupId = event.getGroupId() == null ? 0L : event.getGroupId();
        commandStore.logUsage(trigger, groupId, userId, true);
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
        int limit = board.listLimit() <= 0
                ? BoardListFormatter.MAX_LIMIT
                : Math.min(board.listLimit(), BoardListFormatter.MAX_LIMIT);

        List<BoardRecord> list;
        DataPolicy.DataScope scope = board.policy().scope();
        if (scope == DataPolicy.DataScope.ALL) {
            // 0 = 不按群过滤，跨群预约列表靠这个
            list = records.query(board, 0L, limit);
        } else if (scope == DataPolicy.DataScope.SOME) {
            list = records.query(board, board.policy().groups(), limit);
        } else {
            if (groupId <= 0) {
                return "这个板块要在群里看";
            }
            list = records.query(board, groupId, limit);
        }
        return BoardListFormatter.render(board, list);
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

    // ==================== 参数 ====================

    private String list(Board board) {
        return board.listReply() == null || board.listReply().isBlank()
                ? catalog.boardModuleList(board) : board.listReply();
    }

    // ==================== 帮助 ====================

    private String help(Board board, OneBotEvent event) {
        return board.helpReply() == null || board.helpReply().isBlank()
                ? globalReply(SystemReplyStore.HELP, catalog.helpText(), event) : board.helpReply();
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
