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
    @DisplayName("★ /板块 /list 列出参数，不依赖记录库")
    void boardListShowsParams() {
        BoardStore boards = mock(BoardStore.class);
        BoardRecordStore records = mock(BoardRecordStore.class);
        Board board = new Board(1, "开黑", "开黑",
                List.of(ParamSpec.of("内容", "text", true, "示例")),
                Board.DIRECT, "all", DataPolicy.DEFAULT, true, 30, false);
        when(boards.find("开黑")).thenReturn(board);
        when(records.isAvailable()).thenReturn(false);

        BoardRunner runner = new BoardRunner(boards, records, mock(CommandParser.class),
                mock(VariableRenderer.class), mock(AclStore.class), mock(CommandCatalog.class),
                mock(CommandStore.class));

        var reply = runner.tryRun(event(), "/开黑 /list");

        assertThat(reply).isPresent();
        assertThat(reply.get().text()).contains("支持 1 个参数", "#内容#", "例 示例");
    }

    private static OneBotEvent event() {
        OneBotEvent event = mock(OneBotEvent.class);
        when(event.getUserId()).thenReturn(200L);
        when(event.getGroupId()).thenReturn(100L);
        when(event.isGroupMessage()).thenReturn(true);
        return event;
    }
}
