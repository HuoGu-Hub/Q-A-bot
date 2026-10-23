package com.example.qqbot.command;

import org.springframework.stereotype.Component;

import com.example.qqbot.prompt.SystemAliasStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 命令目录 —— 板块与普通指令的<b>唯一清单</b>。
 *
 * <h2>为什么要有它</h2>
 * 板块接入之后，"群里能发什么命令"变成了两张表：{@code bot_board} 与
 * {@code bot_command}。后台的指令页、群里的 {@code /list}、以及将来的权限总览
 * 都得回答同一个问题，各写一份查询与排版迟早会漂移 —— 用户看到的就是
 * "后台写着 A，群里回的是 B"。
 *
 * <p>所以清单只在这里拼一次：{@link #entries(boolean)} 给后台，
 * {@link #listText()} 与 {@link #helpText()} 给群里的 {@code /list}、{@code /help}。
 * 两边读的是同一份条目，文案可以不同，内容不会互相打脸。
 *
 * <h2>排序约定</h2>
 * 板块在前、指令在后，各自按 {@code sortOrder} 排 —— 群友最常发的是板块
 * （公告、任务、开黑），普通指令是补充。
 */
@Component
public class CommandCatalog {

    private final BoardStore boards;
    private final CommandStore commands;
    private final SystemAliasStore aliases;

    public CommandCatalog(BoardStore boards, CommandStore commands) {
        this(boards, commands, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CommandCatalog(BoardStore boards, CommandStore commands, SystemAliasStore aliases) {
        this.boards = boards;
        this.commands = commands;
        this.aliases = aliases;
    }

    /**
     * 一条可被群友触发的命令。
     *
     * @param source    board / command，后台据此决定给不给"编辑"按钮
     * @param path      不含开头斜杠的命令路径；板块就是板块名
     * @param title     给人看的名字：板块标题，或指令的说明
     * @param kind      board / template / agent / action
     * @param usage     完整用法，带参数示例；与群里 {@code /help} 的写法一致
     * @param access    归一化后的准入档位 code
     * @param scope     板块的数据范围 GROUP / SOME / ALL；指令暂不在这里展开
     * @param delivery  板块的投递方式 direct / scheduled；指令为 null
     * @param actions   板块支持的动作；指令为空
     */
    public record Entry(
            String source,
            long id,
            String path,
            String title,
            String description,
            String kind,
            List<ParamSpec> params,
            String usage,
            String access,
            String scope,
            String delivery,
            boolean enabled,
            boolean builtin,
            List<String> actions) {
    }

    /**
     * 全部条目。
     *
     * @param includeDisabled true = 后台列表（停用的也要看见）；
     *                        false = 群里的 {@code /list}（只列能用的）
     */
    public List<Entry> entries(boolean includeDisabled) {
        List<Entry> out = new ArrayList<>();
        List<Board> boardList = includeDisabled ? boards.listAll() : boards.listEnabled();
        for (Board board : boardList) {
            out.add(boardEntry(board));
        }
        List<BotCommand> commandList = includeDisabled ? commands.listAll() : commands.listEnabled();
        for (BotCommand command : commandList) {
            out.add(commandEntry(command));
        }
        return out;
    }

    private static Entry boardEntry(Board board) {
        boolean scheduled = board.isScheduled();
        return new Entry(
                "board",
                board.id(),
                board.path(),
                board.displayTitle(),
                scheduled ? "板块 · 到点发送" : "板块 · 立即展示",
                "board",
                board.params(),
                boardUsage(board),
                CommandAccess.of(board.access()).code(),
                board.policy().scope().name(),
                board.delivery(),
                board.enabled(),
                board.builtin(),
                Board.ACTIONS);
    }

    private static Entry commandEntry(BotCommand command) {
        String description = command.description() == null ? "" : command.description();
        return new Entry(
                "command",
                command.id(),
                command.trigger(),
                description,
                description,
                command.kindName(),
                command.params(),
                command.display(),
                CommandAccess.of(command.access()).code(),
                null,
                null,
                command.enabled(),
                command.builtin(),
                List.of());
    }

    /** 全局 /list：只列当前可用的一级命令，不展开动作 */
    public String listText() {
        Set<String> out = new LinkedHashSet<>();
        out.add(commandDisplay("list"));
        out.add(commandDisplay("help"));
        for (Board board : boards.listEnabled()) {
            out.add(commandDisplay(board.path()));
        }
        for (BotCommand command : commands.listEnabled()) {
            out.add(commandDisplay(command.trigger()));
        }
        return String.join("\n", out);
    }

    /** 单个命令的 /list：当前命令下的动作清单 */
    public String moduleList(String target, List<String> actions) {
        StringBuilder sb = new StringBuilder(commandDisplay(target));
        for (String action : actions) {
            sb.append('\n').append(commandDisplay(target)).append(" /")
                    .append(actionDisplay(action));
            if ("delete".equalsIgnoreCase(action)) {
                sb.append(" id");
            }
        }
        return sb.toString();
    }

    public String promptModuleList() {
        return moduleList("prompt", List.of("create", "delete", "help", "list"));
    }

    public String commandModuleList(BotCommand command) {
        return moduleList(command.trigger(), List.of("help", "list"));
    }

    public String boardModuleList(Board board) {
        return moduleList(board.path(), List.of("create", "delete", "help", "list"));
    }

    private String commandDisplay(String target) {
        return aliases == null ? "/" + target.replace("/", " /") : aliases.commandDisplay(target);
    }

    private String actionDisplay(String action) {
        return aliases == null ? action : aliases.actionName(action);
    }

    /**
     * 群里 {@code /help} 的正文。
     *
     * <p>只讲"怎么写"，不铺清单 —— 清单在 {@code /list}，两件事分开。
     */
    public String helpText() {
        return """
                命令格式：/命令 [/#动作] [#参数-值#] [文本]
                全局：
                  /list            当前可用的命令
                  /help            这份说明
                补充提示：
                  /prompt                                查看当前生效的提示
                  /prompt /create 文本                   默认加到本群
                  /prompt /create #user_id-QQ号# 文本    只给某个人
                  /prompt /list                          查看补充提示的动作
                  /prompt /delete 编号
                  /prompt /clear
                单条命令：
                  /命令 /list      看这条命令有哪些动作
                  /命令 /help      看这条命令怎么用
                板块：
                  /板块            看这个板块当前的数据
                  /板块 /create    添加一条
                  /板块 /delete    按编号撤销一条
                  /板块 /list      看这个板块有哪些动作
                  /板块 /help      看这个板块怎么用
                注意：
                  独立的 /create、/delete 不生效：板块用 /板块 /create，补充提示用 /prompt /create
                  参数写成 #名字-值#，顺序随意，写在文本前后都行
                  多个值用顿号隔开；# 用半角，前后不要多打空格
                """;
    }

    /** 板块的完整创建写法，参数用示例值 —— 与指令的 display() 保持同一种形状 */
    public static String boardUsage(Board board) {
        StringBuilder sb = new StringBuilder("/").append(board.path()).append(" /create");
        for (ParamSpec param : board.params()) {
            String example = param.example() == null || param.example().isBlank()
                    ? param.name() : param.example();
            sb.append(" #").append(param.name()).append('-').append(example).append('#');
        }
        return sb.append(" 内容").toString();
    }
}
