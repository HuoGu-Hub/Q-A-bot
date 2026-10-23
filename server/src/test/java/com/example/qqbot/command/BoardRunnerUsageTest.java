package com.example.qqbot.command;

import com.example.qqbot.onebot.model.OneBotEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
        when(records.query(any(), anyLong(), anyInt())).thenReturn(List.of());

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
        when(records.query(board, 0L, 200)).thenReturn(List.of(record));

        BoardRunner runner = new BoardRunner(boards, records, mock(CommandParser.class),
                mock(VariableRenderer.class), mock(AclStore.class), mock(CommandCatalog.class),
                mock(CommandStore.class));

        var reply = runner.tryRun(event(), "/预约开黑");

        assertThat(reply).isPresent();
        assertThat(reply.get().text())
                .contains("预约开黑 · 共 1 条")
                .contains("#1 今晚8点 缺打野（阿明）");
    }

    private static OneBotEvent event() {
        OneBotEvent event = mock(OneBotEvent.class);
        when(event.getUserId()).thenReturn(200L);
        when(event.getGroupId()).thenReturn(100L);
        when(event.isGroupMessage()).thenReturn(true);
        return event;
    }
}
