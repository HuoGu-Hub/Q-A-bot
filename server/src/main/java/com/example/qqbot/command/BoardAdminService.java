package com.example.qqbot.command;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 板块的后台编辑。
 *
 * <h2>为什么单独一层</h2>
 * 板块的保存不只是"把表单写进库"：路径不能和指令抢、定时板块必须带
 * {@code 重复} 与 {@code 时间} 两个参数、内置板块不能被删。这些规则如果
 * 散在 Controller 里，前端每加一个入口就要再抄一遍。收在这里之后，
 * Controller 只负责 HTTP 形状。
 *
 * <h2>路径不可改名</h2>
 * 板块记录按 {@code board} 列引用板块名；改名会让历史记录失去归属。
 * 要换名字就新建一个板块，旧记录仍留在旧板块里，这是有意的。
 */
@Service
public class BoardAdminService {

    private static final String PARAM_REPEAT = BoardRecordStore.PARAM_REPEAT;
    private static final String PARAM_TIME = BoardRecordStore.PARAM_TIME;

    private final BoardStore boards;
    private final CommandStore commands;

    public BoardAdminService(BoardStore boards, CommandStore commands) {
        this.boards = boards;
        this.commands = commands;
    }

    /**
     * 后台看到的一条板块。
     *
     * @param usage 完整创建写法，与 QQ 侧目录、/list 用同一份格式
     */
    public record BoardView(long id, String path, String title, List<ParamSpec> params,
                            String delivery, String access, String scope, List<Long> groups,
                            boolean showGroup, boolean showUser, boolean allowPrivate,
                            boolean enabled, int sortOrder, boolean builtin, String usage,
                            String listHeader, String listItem, String listEmpty, int listLimit) {
    }

    /**
     * 保存请求。
     *
     * @param title        历史字段，传什么都忽略；命令名固定等于 path
     * @param originalPath 编辑时必填：用来确认"改的是哪一条"。为空表示新建
     */
    public record BoardInput(String path, String title, List<ParamSpec> params,
                             String delivery, String access, String scope, List<Long> groups,
                             Boolean showGroup, Boolean showUser, Boolean allowPrivate,
                             Boolean enabled, Integer sortOrder, String originalPath,
                             String listHeader, String listItem, String listEmpty,
                             Integer listLimit) {

        /** 兼容旧调用：没有列表格式时沿用默认 */
        public BoardInput(String path, String title, List<ParamSpec> params,
                          String delivery, String access, String scope, List<Long> groups,
                          Boolean showGroup, Boolean showUser, Boolean allowPrivate,
                          Boolean enabled, Integer sortOrder, String originalPath) {
            this(path, title, params, delivery, access, scope, groups,
                    showGroup, showUser, allowPrivate, enabled, sortOrder, originalPath,
                    null, null, null, null);
        }
    }

    public List<BoardView> list() {
        List<BoardView> out = new ArrayList<>();
        for (Board board : boards.listAll()) {
            out.add(view(board));
        }
        return out;
    }

    /**
     * 新建或更新。
     *
     * @throws IllegalArgumentException 路径、参数或定时约束不合法；消息直接给人看
     */
    public BoardView save(BoardInput input) {
        if (input == null) {
            throw new IllegalArgumentException("缺少板块内容");
        }
        String path = trim(input.path());
        if (path == null) {
            throw new IllegalArgumentException("板块名不能为空");
        }
        if (path.contains("/") || path.contains(" ") || path.contains("//")) {
            throw new IllegalArgumentException("板块名就是一级路径，不能带斜杠或空格");
        }
        if (!path.matches("[\\p{IsHan}A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("板块名「" + path + "」含不支持的字符");
        }
        if ("help".equalsIgnoreCase(path) || "list".equalsIgnoreCase(path)
                || "create".equalsIgnoreCase(path) || "delete".equalsIgnoreCase(path)) {
            throw new IllegalArgumentException(
                    "help / list / create / delete 是系统或板块动作的保留名，不能作为板块名");
        }

        String originalPath = trim(input.originalPath());
        Board existing;
        boolean renaming = false;
        if (originalPath == null) {
            existing = boards.find(path);
            if (existing != null) {
                throw new IllegalArgumentException("已经有同名板块：/" + path);
            }
        } else {
            existing = boards.find(originalPath);
            if (existing == null) {
                throw new IllegalArgumentException("板块不存在：/" + originalPath);
            }
            if (!originalPath.equals(path)) {
                if (existing.builtin()) {
                    throw new IllegalArgumentException("内置板块不能改名");
                }
                if (boards.find(path) != null) {
                    throw new IllegalArgumentException("已经有同名板块：/" + path);
                }
                renaming = true;
            }
        }

        // 板块在 BoardRunner 里先于指令表匹配，同名的指令会永远发不出去
        for (BotCommand command : commands.listAll()) {
            // 停用的指令不会参与匹配，不该拦板块；与 CommandController 的判定保持对称
            if (!command.enabled()) {
                continue;
            }
            String first = command.trigger().split("/")[0];
            if (first.equalsIgnoreCase(path)) {
                throw new IllegalArgumentException(
                        "「" + first + "」已经是指令路径的第一段，板块会把它遮住");
            }
        }

        List<ParamSpec> params = validateParams(input.params());
        String delivery = Board.SCHEDULED.equalsIgnoreCase(trim(input.delivery()))
                ? Board.SCHEDULED : Board.DIRECT;
        validateDelivery(delivery, params);

        String access = CommandAccess.of(input.access()).code();
        DataPolicy.DataScope scope = parseScope(input.scope());
        List<Long> groups = positiveIds(input.groups());
        if (scope == DataPolicy.DataScope.SOME && groups.isEmpty()) {
            throw new IllegalArgumentException("数据范围选「指定群」时，至少要填一个群号");
        }
        boolean groupScope = scope == DataPolicy.DataScope.GROUP;
        DataPolicy policy = new DataPolicy(scope, groups,
                !groupScope && Boolean.TRUE.equals(input.showGroup()),
                !groupScope && Boolean.TRUE.equals(input.showUser()),
                Boolean.TRUE.equals(input.allowPrivate()));

        // 命令名就是调用名：title 不再接受独立输入，构造器也会强制回 path
        String title = path;
        int sortOrder = input.sortOrder() == null
                ? (existing == null ? 0 : existing.sortOrder())
                : input.sortOrder();
        boolean builtin = existing != null && existing.builtin();

        String listHeader = input.listHeader() == null
                ? (existing == null ? "" : existing.listHeader())
                : clip(input.listHeader(), 200);
        String listItem = input.listItem() == null
                ? (existing == null ? "" : existing.listItem())
                : clip(input.listItem(), 500);
        String listEmpty = input.listEmpty() == null
                ? (existing == null ? "" : existing.listEmpty())
                : clip(input.listEmpty(), 200);
        int listLimit = input.listLimit() == null
                ? (existing == null ? 20 : existing.listLimit())
                : Math.max(0, Math.min(200, input.listLimit()));

        Board board = new Board(
                existing == null ? 0 : existing.id(), path, title, params, delivery, access, policy,
                input.enabled() == null || input.enabled(), sortOrder, builtin,
                listHeader, listItem, listEmpty, listLimit);
        if (renaming && !boards.rename(originalPath, path)) {
            throw new IllegalArgumentException("改名失败：旧板块不存在或不允许改名");
        }
        boards.save(board);
        return view(boards.find(path));
    }

    public void toggle(String path, boolean enabled) {
        Board board = require(path);
        boards.save(new Board(board.id(), board.path(), board.title(), board.params(),
                board.delivery(), board.access(), board.policy(), enabled, board.sortOrder(),
                board.builtin(), board.listHeader(), board.listItem(), board.listEmpty(),
                board.listLimit()));
    }

    public void delete(String path) {
        Board board = require(path);
        if (board.builtin()) {
            throw new IllegalArgumentException("内置板块不能删除（可以停用）");
        }
        if (!boards.delete(path)) {
            throw new IllegalArgumentException("删除失败：没有这个板块");
        }
    }

    // ==================== 校验 ====================

    private Board require(String path) {
        String p = trim(path);
        if (p == null) {
            throw new IllegalArgumentException("缺少板块名");
        }
        Board board = boards.find(p);
        if (board == null) {
            throw new IllegalArgumentException("板块不存在：/" + p);
        }
        return board;
    }

    /**
     * 参数表校验。
     *
     * <p>名字规则与指令完全一致：它要能写进 {@code #名字-值#}。
     * 重复参数直接拒绝 —— 后一个会静默覆盖前一个，群里看不出来。
     */
    private static List<ParamSpec> validateParams(List<ParamSpec> raw) {
        List<ParamSpec> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (raw != null) {
            for (ParamSpec spec : raw) {
                if (spec == null) {
                    continue;
                }
                String name = spec.name();
                if (name == null || !name.matches("[\\p{IsHan}A-Za-z0-9_]+")) {
                    throw new IllegalArgumentException(
                            "参数名「" + name + "」不合法：只能用汉字、字母、数字、下划线");
                }
                if (!seen.add(name)) {
                    throw new IllegalArgumentException("参数名「" + name + "」重复了");
                }
                out.add(spec);
            }
        }
        return out;
    }

    /**
     * 定时板块的两个约定参数。
     *
     * <p>{@link BoardRecordStore} 按名字取 {@code 重复} 与 {@code 时间}；
     * 名字或类型不对，保存时能过，群里一发就报错 —— 所以在保存时就拦住。
     */
    private static void validateDelivery(String delivery, List<ParamSpec> params) {
        if (!Board.SCHEDULED.equals(delivery)) {
            return;
        }
        ParamSpec repeat = find(params, PARAM_REPEAT);
        ParamSpec time = find(params, PARAM_TIME);
        if (repeat == null) {
            throw new IllegalArgumentException("到点发送的板块必须有 #" + PARAM_REPEAT + "# 参数");
        }
        if (repeat.type() != ParamType.TEXT) {
            throw new IllegalArgumentException("#" + PARAM_REPEAT + "# 必须是文本参数");
        }
        if (time == null) {
            throw new IllegalArgumentException("到点发送的板块必须有 #" + PARAM_TIME + "# 参数");
        }
        if (time.type() != ParamType.MOMENT) {
            throw new IllegalArgumentException("#" + PARAM_TIME + "# 必须是时间点参数");
        }
        if (!repeat.required() || !time.required()) {
            throw new IllegalArgumentException("#" + PARAM_REPEAT + "# 与 #" + PARAM_TIME + "# 必须设为必填");
        }
    }

    private static ParamSpec find(List<ParamSpec> params, String name) {
        for (ParamSpec spec : params) {
            if (name.equals(spec.name())) {
                return spec;
            }
        }
        return null;
    }

    private static DataPolicy.DataScope parseScope(String raw) {
        try {
            return DataPolicy.DataScope.valueOf(
                    raw == null ? "GROUP" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DataPolicy.DataScope.GROUP;
        }
    }

    private static List<Long> positiveIds(List<Long> raw) {
        List<Long> out = new ArrayList<>();
        if (raw != null) {
            for (Long id : raw) {
                if (id != null && id > 0) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** 列表格式只做长度保护，允许换行与空串；空串表示走框架默认排版 */
    private static String clip(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static BoardView view(Board board) {
        return new BoardView(board.id(), board.path(), board.displayTitle(), board.params(),
                board.delivery(), CommandAccess.of(board.access()).code(),
                board.policy().scope().name(), board.policy().groups(),
                board.policy().showGroup(), board.policy().showUser(),
                board.policy().allowPrivate(), board.enabled(), board.sortOrder(),
                board.builtin(), CommandCatalog.boardUsage(board),
                board.listHeader(), board.listItem(), board.listEmpty(), board.listLimit());
    }
}
