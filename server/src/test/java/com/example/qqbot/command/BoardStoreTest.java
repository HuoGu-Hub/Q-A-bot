package com.example.qqbot.command;

import com.example.qqbot.config.PersistenceProperties;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 板块是配置，不是代码。这里钉住三件事：种子长什么样、默认动作是查询、改过之后不被覆盖。
 */
class BoardStoreTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private BoardStore store;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("board.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        store = new BoardStore(new BoardRepository(jdbc), new ObjectMapper());
        store.init();
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("三个内置板块都在")
    void seedsExist() {
        assertThat(store.listAll()).extracting(Board::path)
                .contains("公告", "任务", "开黑");
    }

    @Test
    @DisplayName("★ 公告是到点发、只给管理员；开黑是立即展示、跨群")
    void seedShapes() {
        Board announcement = store.find("公告");
        assertThat(announcement.isScheduled()).isTrue();
        assertThat(announcement.access()).isEqualTo("admin");
        assertThat(announcement.policy().scope()).isEqualTo(DataPolicy.DataScope.GROUP);

        Board lfg = store.find("开黑");
        assertThat(lfg.isScheduled()).isFalse();
        assertThat(lfg.access()).isEqualTo("all");
        assertThat(lfg.policy().scope()).isEqualTo(DataPolicy.DataScope.ALL);
        assertThat(lfg.policy().showGroup()).as("跨群要标出来自哪个群").isTrue();
    }

    @Test
    @DisplayName("★ 不发动作时默认是查")
    void defaultActionIsQuery() {
        assertThat(Board.normalizeAction(null)).isEqualTo(Board.ACT_QUERY);
        assertThat(Board.normalizeAction("")).isEqualTo(Board.ACT_QUERY);
        assertThat(Board.normalizeAction("  ")).isEqualTo(Board.ACT_QUERY);
    }

    @Test
    @DisplayName("认不出来的动作也按查处理，不会报错")
    void unknownActionFallsBackToQuery() {
        assertThat(Board.normalizeAction("删除")).isEqualTo(Board.ACT_QUERY);
        assertThat(Board.normalizeAction("Create")).isEqualTo(Board.ACT_CREATE);
    }

    @Test
    @DisplayName("四个动作都在")
    void fourActions() {
        assertThat(Board.ACTIONS).containsExactly("create", "query", "delete", "help");
    }

    @Test
    @DisplayName("★ 管理员改过的板块不会被重启覆盖，且名称始终等于调用名")
    void editedBoardSurvivesRestart() {
        Board edited = new Board(0, "公告", "群公告",
                List.of(ParamSpec.of("时间", "moment", true, "21:30")),
                Board.SCHEDULED, "owner", DataPolicy.DEFAULT, true, 10, true);
        assertThat(edited.title()).as("构造时就把显示名拉回调用名").isEqualTo("公告");
        store.save(edited);

        // 重新初始化 = 模拟重启
        store.init();

        Board after = store.find("公告");
        assertThat(after.title()).as("title 不能脱离调用名").isEqualTo("公告");
        assertThat(after.displayTitle()).isEqualTo("公告");
        assertThat(after.access()).as("改过的权限保住了").isEqualTo("owner");
    }

    @Test
    @DisplayName("新板块可以加，读回来参数表还在")
    void customBoardRoundTrips() {
        store.save(new Board(0, "举报", "举报", List.of(ParamSpec.of("对象", "text", true, "某人")),
                Board.DIRECT, "all", DataPolicy.DEFAULT, true, 40, false));

        Board saved = store.find("举报");
        assertThat(saved.params()).hasSize(1);
        assertThat(saved.params().get(0).name()).isEqualTo("对象");
        assertThat(saved.builtin()).isFalse();
    }
}
