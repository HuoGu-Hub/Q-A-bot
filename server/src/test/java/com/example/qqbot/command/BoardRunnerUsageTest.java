package com.example.qqbot.command;

import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.time.AppTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 板块也是命令：使用情况必须与普通指令记进同一张日志表，否则统计里只有一半命令。
 */
class BoardRunnerUsageTest {

    @Test
    @DisplayName("★ 命中板块时记一次使用")
    void logsBoardUsage() {
        BoardStore boards = mock(BoardStore.class);
        BoardRecordStore records = mock(BoardRecordStore.class);
        CommandStore commandStore = mock(CommandStore.class);
        OneBotEvent event = event();

        Board board = new Board(1, "开黑", "开黑", List.of(), Board.DIRECT, "all",
                DataPolicy.DEFAULT, true, 30, true);
        when(boards.find("开黑")).thenReturn(board);
        when(records.isAvailable()).thenReturn(true);
        when(records.queryVisible(any(), anyLong(), anyInt())).thenReturn(List.of());

        BoardRunner runner = new BoardRunner(boards, records, mock(CommandParser.class),
                mock(VariableRenderer.class), mock(AclStore.class), mock(CommandCatalog.class),
                commandStore);

        assertThat(runner.tryRun(event, "/开黑")).isPresent();
        verify(commandStore).logUsage("开黑", 100L, 200L, true);
    }

    @Test
    @DisplayName("★ /help、/list 也记一次使用")
    void logsGlobalHelpAndList() {
        BoardStore boards = mock(BoardStore.class);
        BoardRecordStore records = mock(BoardRecordStore.class);
        CommandStore commandStore = mock(CommandStore.class);
        CommandCatalog catalog = mock(CommandCatalog.class);
        when(catalog.helpText()).thenReturn("help");
        when(catalog.listText()).thenReturn("list");
        OneBotEvent event = event();

        BoardRunner runner = new BoardRunner(boards, records, mock(CommandParser.class),
                mock(VariableRenderer.class), mock(AclStore.class), catalog, commandStore);

        runner.tryRun(event, "/help");
        runner.tryRun(event, "/list");

        verify(commandStore).logUsage("help", 100L, 200L, true);
        verify(commandStore).logUsage("list", 100L, 200L, true);
    }

    @Test
    @DisplayName("★ /板块 /list 列出这条板块自己的命令")
    void boardListShowsModuleCommands() {
        BoardStore boards = mock(BoardStore.class);
        BoardRecordStore records = mock(BoardRecordStore.class);
        Board board = new Board(1, "开黑", "开黑",
                List.of(ParamSpec.of("内容", "text", true, "示例")),
                Board.DIRECT, "all", DataPolicy.DEFAULT, true, 30, false);
        when(boards.find("开黑")).thenReturn(board);
        when(records.isAvailable()).thenReturn(false);

        CommandCatalog catalog = mock(CommandCatalog.class);
        when(catalog.boardModuleList(board)).thenReturn("/开黑\n/开黑 /create\n/开黑 /delete id\n/开黑 /help\n/开黑 /list");
        BoardRunner runner = new BoardRunner(boards, records, mock(CommandParser.class),
                mock(VariableRenderer.class), mock(AclStore.class), catalog,
                mock(CommandStore.class));

        var reply = runner.tryRun(event(), "/开黑 /list");

        assertThat(reply).isPresent();
        assertThat(reply.get().text()).contains("/开黑 /create", "/开黑 /delete id", "/开黑 /help", "/开黑 /list");
    }

    @Test
    @DisplayName("★ 自定义列表格式：ALL 范围跨群查，0 条限制按 200 兜底")
    void customListFormatAndAllScope() {
        BoardStore boards = mock(BoardStore.class);
        BoardRecordStore records = mock(BoardRecordStore.class);
        Board board = new Board(1, "预约开黑", "预约开黑", List.of(),
                Board.DIRECT, "all",
                new DataPolicy(DataPolicy.DataScope.ALL, List.of(), false, false, false),
                true, 0, false,
                "预约开黑 · 共 {count} 条", "#{id} {params.时间} {text}（{nickname}）",
                "还没有预约", 0);
        BoardRecord record = new BoardRecord(1, "预约开黑", 100L, 200L, "阿明", "缺打野",
                java.util.Map.of("时间", "今晚8点"), Board.DIRECT, null, "once",
                List.of(), null, "pending", 0, null, java.time.Instant.now(), null, null);
        when(boards.find("预约开黑")).thenReturn(board);
        when(records.isAvailable()).thenReturn(true);
        when(records.queryVisible(board, 0L, 200)).thenReturn(List.of(record));

        BoardRunner runner = new BoardRunner(boards, records, mock(CommandParser.class),
                mock(VariableRenderer.class), mock(AclStore.class), mock(CommandCatalog.class),
                mock(CommandStore.class));

        var reply = runner.tryRun(event(), "/预约开黑");

        assertThat(reply).isPresent();
        assertThat(reply.get().text())
                .contains("预约开黑 · 共 1 条")
                .contains("#1 今晚8点 缺打野（阿明）");
    }

    @Test
    @DisplayName("★ 板块自己的白名单对普通成员生效")
    void boardWhitelistGrantsMember() {
        BoardStore boards = mock(BoardStore.class);
        BoardRecordStore records = mock(BoardRecordStore.class);
        AclStore acl = mock(AclStore.class);
        Board board = new Board(1, "公告", "公告", List.of(), Board.DIRECT, "whitelist",
                DataPolicy.DEFAULT, true, 0, false);
        when(boards.find("公告")).thenReturn(board);
        when(records.isAvailable()).thenReturn(true);
        when(records.queryVisible(board, 100L, 20)).thenReturn(List.of());
        when(acl.ofBoard(1L)).thenReturn(List.of(
                new AclEntry(AclEntry.WHITE, AclEntry.UID, 200L)));

        BoardRunner runner = new BoardRunner(boards, records, mock(CommandParser.class),
                mock(VariableRenderer.class), acl, mock(CommandCatalog.class),
                mock(CommandStore.class));

        var reply = runner.tryRun(event(), "/公告");

        assertThat(reply).isPresent();
        assertThat(reply.get().text())
                .doesNotContain("只对指定的人开放")
                .contains("还没有内容");
    }

    @Test
    @DisplayName("★ 普通群友触发管理员板块：回一句拦截原因")
    void nonAdminGetsDenyReason() {
        BoardStore boards = mock(BoardStore.class);
        BoardRecordStore records = mock(BoardRecordStore.class);
        Board board = new Board(1, "公告", "公告",
                List.of(ParamSpec.of("内容", "text", true, "示例")),
                Board.DIRECT, "admin", DataPolicy.DEFAULT, true, 0, false);
        when(boards.find("公告")).thenReturn(board);

        BoardRunner runner = new BoardRunner(boards, records, mock(CommandParser.class),
                mock(VariableRenderer.class), mock(AclStore.class), mock(CommandCatalog.class),
                mock(CommandStore.class));

        var reply = runner.tryRun(event(), "/公告");

        assertThat(reply).isPresent();
        assertThat(reply.get().text()).isEqualTo("这条命令只有群主或管理员能用");
    }

    @Test
    @DisplayName("★ 重复与时间参数改名后，/create 仍按那个参数取时刻")
    @SuppressWarnings("unchecked")
    void renamedScheduleParamsCreate() throws Exception {
        BoardStore boards = mock(BoardStore.class);
        BoardRecordStore records = mock(BoardRecordStore.class);
        Board board = new Board(1, "打卡", "打卡",
                List.of(ParamSpec.of("周期", "repeat", true, "每天"),
                        ParamSpec.multi("提醒时间", ParamType.MOMENT, true, null, 0, null, "21:30")),
                Board.SCHEDULED, "all", DataPolicy.DEFAULT, true, 0, false);
        when(boards.find("打卡")).thenReturn(board);
        when(records.isAvailable()).thenReturn(true);
        when(records.create(any(), anyLong(), anyLong(), anyString(), anyString(), anyMap(),
                anyList(), any())).thenReturn(7L);
        when(records.find(7L)).thenReturn(new BoardRecord(7L, "打卡", 100L, 200L, "阿明", "打卡啦",
                java.util.Map.of(), Board.SCHEDULED, Instant.now(), "daily", List.of(), null,
                "pending", 0, null, Instant.now(), null, null));

        VariableRenderer renderer = mock(VariableRenderer.class);
        when(renderer.render(anyString(), any(RenderContext.class)))
                .thenAnswer((call) -> call.getArgument(0));
        when(renderer.contextOf(any(OneBotEvent.class), anyMap(), anyString(), anyString()))
                .thenReturn(new RenderContext(200L, 100L, true, "阿明", "",
                        java.util.Map.of(), "", false, Instant.now()));

        BoardRunner runner = new BoardRunner(boards, records, new CommandParser(), renderer,
                mock(AclStore.class), mock(CommandCatalog.class), mock(CommandStore.class));

        var reply = runner.tryRun(event(), "/打卡 /create #周期-每天# #提醒时间-21:30# 打卡啦");

        assertThat(reply).isPresent();
        ArgumentCaptor<List<Instant>> moments = ArgumentCaptor.forClass(List.class);
        verify(records).create(any(), anyLong(), anyLong(), anyString(), anyString(), anyMap(),
                moments.capture(), any());
        assertThat(moments.getValue()).hasSize(1);
        assertThat(LocalTime.ofInstant(moments.getValue().get(0), AppTime.ZONE))
                .isEqualTo(LocalTime.of(21, 30));
    }

    private static OneBotEvent event() {
        OneBotEvent event = mock(OneBotEvent.class);
        when(event.getUserId()).thenReturn(200L);
        when(event.getGroupId()).thenReturn(100L);
        when(event.isGroupMessage()).thenReturn(true);
        return event;
    }
}
