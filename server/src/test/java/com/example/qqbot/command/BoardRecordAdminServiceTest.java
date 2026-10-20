package com.example.qqbot.command;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.BoardRecordRepository;
import com.example.qqbot.persistence.BoardRepository;
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
 * 板块记录后台的验收测试：能看、能撤销、能恢复，且不把「已撤销」当成不存在。
 */
class BoardRecordAdminServiceTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private BoardStore boards;
    private BoardRecordStore records;
    private BoardRecordAdminService service;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("records.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        ObjectMapper mapper = new ObjectMapper();
        boards = new BoardStore(new BoardRepository(jdbc), mapper);
        boards.init();
        records = new BoardRecordStore(new BoardRecordRepository(jdbc), mapper);
        records.init();
        service = new BoardRecordAdminService(boards, records);
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("立即展示的板块记录：列表能看到人话时间与正文")
    void listsDirectRecord() throws Exception {
        long id = records.create(boards.find("开黑"), 100L, 200L, "测试用户",
                "今晚八点开黑", Map.of("空闲", "今晚8点"), List.of(), Instant.now());

        var list = service.list("开黑", 0, false, 50);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo(id);
        assertThat(list.get(0).when()).isEqualTo("立即展示");
        assertThat(list.get(0).text()).isEqualTo("今晚八点开黑");
        assertThat(list.get(0).nickname()).isEqualTo("测试用户");
        assertThat(list.get(0).deleted()).isFalse();
    }

    @Test
    @DisplayName("★ 撤销后默认列表看不到，勾上「显示已撤销」还能看到并恢复")
    void softDeleteAndRestore() throws Exception {
        long id = records.create(boards.find("开黑"), 100L, 200L, "测试用户",
                "今晚八点开黑", Map.of(), List.of(), Instant.now());

        service.softDelete(id);

        assertThat(service.list("开黑", 0, false, 50)).isEmpty();
        var deleted = service.list("开黑", 0, true, 50);
        assertThat(deleted).hasSize(1);
        assertThat(deleted.get(0).deleted()).isTrue();

        service.restore(id);

        var restored = service.list("开黑", 0, false, 50);
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).deleted()).isFalse();
    }

    @Test
    @DisplayName("重复撤销 / 恢复没撤销的记录都会给人话提示")
    void invalidTransitions() throws Exception {
        long id = records.create(boards.find("开黑"), 100L, 200L, "测试用户",
                "今晚八点开黑", Map.of(), List.of(), Instant.now());

        assertThatThrownBy(() -> service.restore(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有被撤销");

        service.softDelete(id);
        assertThatThrownBy(() -> service.softDelete(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已经撤销过");
    }

    @Test
    @DisplayName("定时板块的记录显示真实的下次发送说明")
    void scheduledRecordShowsWhen() throws Exception {
        Instant now = Instant.now();
        records.create(boards.find("公告"), 100L, 200L, "管理员",
                "今晚八点开黑", Map.of("重复", "每天"), List.of(now.plusSeconds(3600)), now);

        var list = service.list("公告", 100L, false, 50);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).when()).contains("每天").doesNotContain("立即展示");
        assertThat(list.get(0).delivery()).isEqualTo(Board.SCHEDULED);
    }

    @Test
    @DisplayName("不存在的板块直接给错误，不返回空列表")
    void unknownBoardIsAnError() {
        assertThatThrownBy(() -> service.list("不存在的板块", 0, false, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("板块不存在");
    }
}
