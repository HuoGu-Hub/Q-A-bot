package com.example.qqbot.command;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.BoardRepository;
import com.example.qqbot.persistence.CommandRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令目录 —— 后台列表与群里 {@code /list}、{@code /help} 的同一份数据源。
 *
 * <p>这里钉住的是"两边不能各说各话"：板块与指令都在，顺序稳定，
 * 文案里能同时找到两类，停用的不出现在群清单里。
 */
class CommandCatalogTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private BoardStore boards;
    private CommandStore commands;
    private CommandCatalog catalog;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("catalog.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        ObjectMapper mapper = new ObjectMapper();
        boards = new BoardStore(new BoardRepository(jdbc), mapper);
        boards.init();
        commands = new CommandStore(new CommandRepository(jdbc), mapper);
        commands.init();
        catalog = new CommandCatalog(boards, commands);
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("目录：三个板块在前，两条内置指令在后")
    void entriesCoverBoardsAndCommands() {
        assertThat(catalog.entries(true)).extracting(CommandCatalog.Entry::path)
                .containsExactly("公告", "任务", "开黑", "ping", "stats", "prompt");
    }

    @Test
    @DisplayName("★ /list 只列一级命令")
    void listTextCoversTopLevel() {
        String text = catalog.listText();

        assertThat(text).contains("/list", "/help", "/公告", "/任务", "/开黑", "/ping", "/stats", "/prompt");
        assertThat(text).doesNotContain("系统：", "板块动作", "/公告 /create");
    }

    @Test
    @DisplayName("★ /help 同时说清板块与普通指令怎么写")
    void helpTextCoversBoth() {
        String text = catalog.helpText();

        assertThat(text).contains("/板块 /create", "/板块 /delete", "/板块 /list", "/板块 /help");
        assertThat(text).contains("单条命令", "/命令 /list", "/命令 /help");
        assertThat(text).contains("独立的 /create、/delete");
    }

    @Test
    @DisplayName("板块用法带参数示例，与指令 display() 同一种形状")
    void boardUsageShowsParamExamples() {
        assertThat(CommandCatalog.boardUsage(boards.find("开黑")))
                .isEqualTo("/开黑 /create #空闲-今晚8点# #风格-上分# #联系-QQ 123456# 内容");
    }

    @Test
    @DisplayName("没有参数的板块也给出 /create 写法")
    void boardUsageWithoutParamsStillShowsCreate() {
        Board board = new Board(0, "举报", "举报", List.of(),
                Board.DIRECT, "all", DataPolicy.DEFAULT, true, 40, false);

        assertThat(CommandCatalog.boardUsage(board)).isEqualTo("/举报 /create 内容");
    }

    @Test
    @DisplayName("全局 list/help 有系统条目，并说明板块动作")
    void globalTextExplainsSystemAndBoardActions() {
        assertThat(catalog.listText())
                .contains("/list", "/help", "/公告", "/ping")
                .doesNotContain("板块动作", "/板块 /create");
        assertThat(catalog.helpText())
                .contains("单条命令", "/命令 /list", "/命令 /help")
                .contains("/板块 /create", "/板块 /delete")
                .contains("独立的 /create、/delete");
    }

    @Test
    @DisplayName("停用的板块不出现在群清单里，但后台仍看得到")
    void disabledBoardHiddenFromGroupList() {
        Board task = boards.find("任务");
        boards.save(new Board(task.id(), task.path(), task.title(), task.params(),
                task.delivery(), task.access(), task.policy(), false, task.sortOrder(), task.builtin()));

        assertThat(catalog.entries(false)).extracting(CommandCatalog.Entry::path)
                .doesNotContain("任务");
        assertThat(catalog.entries(true)).extracting(CommandCatalog.Entry::path)
                .contains("任务");
        assertThat(catalog.listText()).doesNotContain("/任务");
    }

    @Test
    @DisplayName("★ 指令 JSON：action 是执行器 id，不是 boolean；access 归一成 all")
    void commandViewKeepsActionId() throws Exception {
        BotCommand command = new BotCommand(1, "set/output", "用法", "说明",
                "all", List.of(), "member", true, 1, true,
                BotCommand.KIND_ACTION, BotCommand.MODE_KB,
                List.of(ParamSpec.of("time", "moment", true, "21:30")),
                null, "schedule.create");

        CommandController.CommandView view = CommandController.CommandView.of(command);

        assertThat(view.access()).as("老值 member 要归一成 all").isEqualTo("all");
        assertThat(view.action()).as("绑定的执行器 id 不能被 isAction() 顶掉")
                .isEqualTo("schedule.create");
        assertThat(view.usage()).isEqualTo("/set /output #time-21:30#");

        String json = new ObjectMapper().writeValueAsString(view);
        assertThat(json).contains("\"action\":\"schedule.create\"");
        assertThat(json).doesNotContain("\"action\":true");
    }

    @Test
    @DisplayName("被板块接管的 help / list 不再出现在指令目录里")
    void shadowedCommandsAreRetired() {
        assertThat(catalog.entries(true)).extracting(CommandCatalog.Entry::path)
                .doesNotContain("help", "list", "set/output", "list/output", "del/output");
        assertThat(commands.findByTrigger("help")).isNull();
        assertThat(commands.findByTrigger("list")).isNull();
    }
}
