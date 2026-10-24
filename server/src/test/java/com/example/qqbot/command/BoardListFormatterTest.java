package com.example.qqbot.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BoardListFormatterTest {

    @Test
    @DisplayName("★ 列表头/每条/变量都按配置渲染")
    void rendersConfiguredFormat() {
        Board board = board("预约开黑 · 共 {count} 条",
                "#{id} {params.时间} {text}（{nickname}）", "还没有预约", 0);
        List<BoardRecord> records = List.of(
                record(1, "阿明", "缺打野", Map.of("时间", "今晚8点")),
                record(2, "小红", "满员了", Map.of("时间", "明天8点")));

        String text = BoardListFormatter.render(board, records);

        assertThat(text).contains("预约开黑 · 共 2 条")
                .contains("#1 今晚8点 缺打野（阿明）")
                .contains("#2 明天8点 满员了（小红）");
    }

    @Test
    @DisplayName("没有配置格式时沿用旧的 displayLine")
    void fallsBackToLegacyFormat() {
        Board board = board("", "", "", 20);
        List<BoardRecord> records = List.of(record(1, "阿明", "缺打野", Map.of()));

        String text = BoardListFormatter.render(board, records);

        assertThat(text).contains("共 1 条：").contains("#1").contains("立即展示").contains("缺打野");
    }

    @Test
    @DisplayName("空列表也走自定义文案")
    void rendersEmptyText() {
        Board board = board("", "", "还没有 {count} 条预约", 20);

        assertThat(BoardListFormatter.render(board, List.of())).isEqualTo("还没有 0 条预约");
    }

    @Test
    @DisplayName("不认识的变量原样保留，方便发现拼写错误")
    void unknownVariableStays() {
        Board board = board("", "{unknown} {id}", "", 20);
        List<BoardRecord> records = List.of(record(7, "阿明", "内容", Map.of()));

        assertThat(BoardListFormatter.render(board, records)).contains("{unknown} 7");
    }

    @Test
    @DisplayName("★ 跨群列表给别群的记录标来源群号与 QQ 号，本群记录不标")
    void marksForeignRecordsOnly() {
        Board board = board("", "#{id} {text}", "", 20,
                new DataPolicy(DataPolicy.DataScope.ALL, List.of(), true, true, false));
        List<BoardRecord> records = List.of(
                record(1, 100L, 200L, "阿明", "缺打野", Map.of()),
                record(2, 300L, 201L, "小红", "满员了", Map.of()));

        String text = BoardListFormatter.render(board, records, 100L);

        assertThat(text).contains("#1 缺打野")
                .doesNotContain("#1 缺打野 ·");
        assertThat(text).contains("#2 满员了 · 来自 300 · QQ 201");
    }

    @Test
    @DisplayName("★ 只勾「显示群」时不带 QQ 号")
    void marksGroupOnly() {
        Board board = board("", "#{id}", "", 20,
                new DataPolicy(DataPolicy.DataScope.ALL, List.of(), true, false, false));
        List<BoardRecord> records = List.of(record(2, 300L, 201L, "小红", "满员了", Map.of()));

        assertThat(BoardListFormatter.render(board, records, 100L))
                .contains("#2 · 来自 300")
                .doesNotContain("QQ");
    }

    @Test
    @DisplayName("★ 显示 QQ 打开后 {user.id} 才认，否则原样保留")
    void userIdVariableNeedsShowUser() {
        List<BoardRecord> records = List.of(record(1, 100L, 200L, "阿明", "缺打野", Map.of()));
        Board shown = board("", "#{id} {user.id}", "", 20,
                new DataPolicy(DataPolicy.DataScope.ALL, List.of(), false, true, false));
        Board hidden = board("", "#{id} {user.id}", "", 20, DataPolicy.DEFAULT);

        assertThat(BoardListFormatter.render(shown, records, 100L)).contains("#1 200");
        assertThat(BoardListFormatter.render(hidden, records, 100L)).contains("{user.id}");
    }

    private static Board board(String header, String item, String empty, int limit) {
        return board(header, item, empty, limit, DataPolicy.DEFAULT);
    }

    @Test
    @DisplayName("★ {params.重复} 显示中文，不把 daily 这种落库形态发到群里")
    void repeatParamShowsHumanText() {
        Board board = board("", "#{id} {params.重复} {params.时间}", "", 20);
        List<BoardRecord> records = List.of(record(3, 100L, 200L, "阿明", "",
                Map.of("重复", "daily", "时间", "21:30")));

        assertThat(BoardListFormatter.render(board, records, 100L))
                .contains("#3 每天 21:30");
    }

    @Test
    @DisplayName("★ 重复参数改名后，{params.周期} 也显示中文")
    void renamedRepeatParamShowsHumanText() {
        Board board = new Board(1, "打卡提醒", "打卡提醒", List.of(
                ParamSpec.of("周期", "repeat", true, "每天"),
                ParamSpec.of("时间", "moment", true, "21:30")),
                Board.DIRECT, "all", DataPolicy.DEFAULT, true, 0, false,
                "", "#{id} {params.周期} {params.时间}", "", 20);
        List<BoardRecord> records = List.of(record(5, 100L, 200L, "阿明", "",
                Map.of("周期", "weekly:3", "时间", "21:30")));

        assertThat(BoardListFormatter.render(board, records, 100L))
                .contains("#5 每周三 21:30");
    }

    @Test
    @DisplayName("★ 参数改名后，老记录里的「重复」键仍然显示中文")
    void legacyRepeatKeyAfterRename() {
        Board board = new Board(1, "打卡提醒", "打卡提醒", List.of(
                ParamSpec.of("周期", "repeat", true, "每天"),
                ParamSpec.of("时间", "moment", true, "21:30")),
                Board.DIRECT, "all", DataPolicy.DEFAULT, true, 0, false,
                "", "#{id} {params.重复}", "", 20);
        List<BoardRecord> records = List.of(record(6, 100L, 200L, "阿明", "",
                Map.of("重复", "daily")));

        assertThat(BoardListFormatter.render(board, records, 100L)).contains("#6 每天");
    }

    @Test
    @DisplayName("★ 没有正文时用参数拼行，重复也要显示中文")
    void bodyFallbackUsesHumanRepeat() {
        Board board = board("", "#{id} {text}", "", 20);
        List<BoardRecord> records = List.of(record(4, 100L, 200L, "阿明", "",
                Map.of("重复", "weekly:3", "时间", "21:30")));

        assertThat(BoardListFormatter.render(board, records, 100L))
                .contains("#4")
                .contains("每周三")
                .contains("21:30");
    }

    private static Board board(String header, String item, String empty, int limit,
                               DataPolicy policy) {
        return new Board(1, "预约开黑", "预约开黑", List.of(), Board.DIRECT, "all",
                policy, true, 0, false, header, item, empty, limit);
    }

    private static BoardRecord record(long id, String nickname, String text, Map<String, String> params) {
        return record(id, 100L, 200L, nickname, text, params);
    }

    private static BoardRecord record(long id, long groupId, long userId, String nickname,
                                      String text, Map<String, String> params) {
        return new BoardRecord(id, "预约开黑", groupId, userId, nickname, text, params,
                Board.DIRECT, null, "once", List.of(), null, "pending", 0, null,
                Instant.now(), null, null);
    }
}
