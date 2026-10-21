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

    private static Board board(String header, String item, String empty, int limit) {
        return new Board(1, "预约开黑", "预约开黑", List.of(), Board.DIRECT, "all",
                DataPolicy.DEFAULT, true, 0, false, header, item, empty, limit);
    }

    private static BoardRecord record(long id, String nickname, String text, Map<String, String> params) {
        return new BoardRecord(id, "预约开黑", 100L, 200L, nickname, text, params,
                Board.DIRECT, null, "once", List.of(), null, "pending", 0, null,
                Instant.now(), null, null);
    }
}
