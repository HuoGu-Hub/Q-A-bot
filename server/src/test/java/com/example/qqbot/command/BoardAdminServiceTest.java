package com.example.qqbot.command;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.BoardRecordRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 板块后台编辑的验收测试。
 *
 * <p>重点不是"能存进去"，而是三条容易在群里炸的约束：
 * 板块名不能和指令抢、定时板块必须带 重复/时间、内置板块不能被删。
 */
class BoardAdminServiceTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private BoardStore boards;
    private BoardRecordStore records;
    private CommandStore commands;
    private BoardAdminService service;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("boards.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        ObjectMapper mapper = new ObjectMapper();
        boards = new BoardStore(new BoardRepository(jdbc), mapper);
        boards.init();
        records = new BoardRecordStore(new BoardRecordRepository(jdbc), mapper);
        records.init();
        commands = new CommandStore(new CommandRepository(jdbc), mapper);
        commands.init();
        service = new BoardAdminService(boards, commands);
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("新建一个立即展示的板块，读回来参数与用法都在")
    void createsCustomBoard() {
        BoardAdminService.BoardView view = service.save(input("举报", Board.DIRECT,
                List.of(ParamSpec.of("对象", "text", true, "某人"))));

        assertThat(view.path()).isEqualTo("举报");
        assertThat(view.builtin()).isFalse();
        assertThat(view.access()).isEqualTo("all");
        assertThat(view.usage()).isEqualTo("/举报 /create #对象-某人# 内容");
        assertThat(boards.find("举报").params()).hasSize(1);
    }

    @Test
    @DisplayName("内置板块能改权限，但删不掉；title 不能脱离调用名")
    void builtinCanBeEditedButNotDeleted() {
        Board announcement = boards.find("公告");
        BoardAdminService.BoardInput edit = new BoardAdminService.BoardInput(
                "公告", "群公告", announcement.params(), Board.SCHEDULED, "owner", "GROUP",
                List.of(), false, false, false, true, 10, "公告");

        BoardAdminService.BoardView view = service.save(edit);

        assertThat(view.title()).as("传了别的 title 也会被拉回调用名").isEqualTo("公告");
        assertThat(view.access()).isEqualTo("owner");
        assertThat(view.builtin()).isTrue();
        assertThat(boards.find("公告").title()).isEqualTo("公告");

        assertThatThrownBy(() -> service.delete("公告"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内置板块不能删除");

        BoardAdminService.BoardInput rename = new BoardAdminService.BoardInput(
                "公告2", "公告2", announcement.params(), Board.SCHEDULED, "owner", "GROUP",
                List.of(), false, false, false, true, 10, "公告");
        assertThatThrownBy(() -> service.save(rename))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内置板块不能改名");
    }

    @Test
    @DisplayName("★ 到点发送的板块必须同时有 重复 与 时间")
    void scheduledNeedsRepeatAndTime() {
        assertThatThrownBy(() -> service.save(input("打卡提醒", Board.SCHEDULED,
                List.of(ParamSpec.of("事项", "text", true, "打卡")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("时间参数类型不对也拦住")
    void scheduledNeedsMomentType() {
        assertThatThrownBy(() -> service.save(input("打卡提醒", Board.SCHEDULED, List.of(
                ParamSpec.of("重复", "text", true, "每天"),
                ParamSpec.of("时间", "text", true, "21:30")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时间点");
    }

    @Test
    @DisplayName("★ 板块名不能和已有指令的第一段重名")
    void pathCannotShadowCommand() throws Exception {
        commands.upsert(new BotCommand(0, "组队", "回复", "说明", "all", List.of(), "member",
                true, 0, false, BotCommand.KIND_TEMPLATE, BotCommand.MODE_KB, List.of(), null, null));

        assertThatThrownBy(() -> service.save(input("组队", Board.DIRECT,
                List.of(ParamSpec.of("时间", "text", true, "今晚8点")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("指令路径");
    }

    @Test
    @DisplayName("指定群模式至少要有一个群号")
    void someScopeNeedsGroups() {
        BoardAdminService.BoardInput input = new BoardAdminService.BoardInput(
                "跨群板", "跨群板", List.of(ParamSpec.of("内容", "text", true, "示例")),
                Board.DIRECT, "all", "SOME", List.of(), false, false, false, true, 0, null);

        assertThatThrownBy(() -> service.save(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("指定群");
    }

    @Test
    @DisplayName("★ 自建板块改名：旧名消失，历史记录跟着走")
    void renameCustomBoardMovesRecords() throws Exception {
        service.save(input("旧板", Board.DIRECT, List.of(ParamSpec.of("内容", "text", true, "示例"))));
        Board old = boards.find("旧板");
        long recordId = records.create(old, 100L, 200L, "阿明", "内容",
                Map.of(), List.of(), Instant.now());

        BoardAdminService.BoardView view = service.save(new BoardAdminService.BoardInput(
                "新板", "新板", old.params(), Board.DIRECT, "all", "GROUP", List.of(),
                false, false, false, true, 40, "旧板"));

        assertThat(view.path()).isEqualTo("新板");
        assertThat(boards.find("旧板")).isNull();
        assertThat(boards.find("新板")).isNotNull();
        assertThat(records.query(boards.find("新板"), 0, 50))
                .extracting(BoardRecord::id).contains(recordId);
    }

    @Test
    @DisplayName("自建板块可以停用、可以删除")
    void customBoardCanBeToggledAndDeleted() {
        service.save(input("举报", Board.DIRECT, List.of(ParamSpec.of("对象", "text", true, "某人"))));

        service.toggle("举报", false);
        assertThat(boards.find("举报").enabled()).isFalse();

        service.delete("举报");
        assertThat(boards.find("举报")).isNull();
    }

    @Test
    @DisplayName("老值 member 落库前归一成 all")
    void memberAccessNormalized() {
        BoardAdminService.BoardView view = service.save(new BoardAdminService.BoardInput(
                "举报", "举报", List.of(ParamSpec.of("对象", "text", true, "某人")),
                Board.DIRECT, "member", "GROUP", List.of(), false, false, false, true, 0, null));

        assertThat(view.access()).isEqualTo("all");
        assertThat(boards.find("举报").access()).isEqualTo("all");
    }

    private static BoardAdminService.BoardInput input(String path, String delivery,
                                                      List<ParamSpec> params) {
        return new BoardAdminService.BoardInput(path, path, params, delivery, "all", "GROUP",
                List.of(), false, false, false, true, 40, null);
    }
}
