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
 * 准入档位这份"清单"本身。
 *
 * <p>它有三个消费者：群里的判定（{@link AccessRules}）、后台的三处编辑器（靠
 * {@code GET /admin/api/commands/access-modes} 下发）、以及保存链路。
 * 以前后台每个编辑器各写一份清单，于是 /prompt 那份漏了「管理员与白名单」，
 * 文案也和别处不一样；而保存链路收的是另一个字段，改完权限一保存就掉回「全体」。
 *
 * <p>所以这里钉三件事：<b>每档都有中文名与 code 往返</b>、<b>哪几档要露名单</b>、
 * <b>存进去的 access 原样读得回来</b>。
 */
class CommandAccessTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private CommandStore store;
    private CommandController controller;

    @BeforeEach
    void setUp() throws Exception {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("access-modes.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        ObjectMapper mapper = new ObjectMapper();
        store = new CommandStore(new CommandRepository(jdbc), mapper);
        store.init();
        BoardStore boards = new BoardStore(new BoardRepository(jdbc), mapper);
        boards.init();
        // 这一轮只走 upsert 与 accessModes，其余协作者用不到，传 null
        controller = new CommandController(store, null, policy(), null, null, boards, null);
    }

    @AfterEach
    void close() {
        db.close();
    }

    /** 控制器只用它读限流与开关，给一份最小实现 */
    private static CommandPolicy policy() {
        return new CommandPolicy() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public int getRateLimitPerMinute() {
                return 5;
            }

            @Override
            public boolean isAllowUserIds() {
                return false;
            }

            @Override
            public int getMaxReplyLength() {
                return 500;
            }
        };
    }

    // ==================== 清单本身 ====================

    @Test
    @DisplayName("★ 每一档都有中文名，且 code 能原样解析回来 —— 加档位时漏写这两样会在这里红")
    void everyModeHasLabelAndRoundTripsByCode() {
        for (CommandAccess mode : CommandAccess.values()) {
            assertThat(mode.label()).as("%s 的中文名", mode).isNotBlank();
            assertThat(CommandAccess.of(mode.code())).as("%s 的 code 往返", mode).isEqualTo(mode);
        }
    }

    @Test
    @DisplayName("中文名互不重复：下拉里两行一模一样的字，等于选不出来")
    void labelsAreUnique() {
        assertThat(CommandAccess.values()).extracting(CommandAccess::label).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("★ 要露名单的只有白名单 / 黑名单 / 管理员与白名单这三档")
    void listDrivenModesAreTheThreeListTiers() {
        assertThat(CommandAccess.values())
                .filteredOn(CommandAccess::usesAcl)
                .containsExactlyInAnyOrder(CommandAccess.WHITELIST, CommandAccess.BLACKLIST,
                        CommandAccess.ADMIN_WHITELIST);
    }

    @Test
    @DisplayName("下发给后台的清单就是枚举本身：顺序、code、中文名、要不要名单一一对应")
    void endpointPublishesTheWholeEnum() {
        CommandController.AccessModes modes = controller.accessModes();

        assertThat(modes.modes()).hasSize(CommandAccess.values().length);
        for (int i = 0; i < CommandAccess.values().length; i++) {
            CommandAccess expected = CommandAccess.values()[i];
            CommandController.AccessModeView view = modes.modes().get(i);
            assertThat(view.code()).isEqualTo(expected.code());
            assertThat(view.label()).isEqualTo(expected.label());
            assertThat(view.usesAcl()).isEqualTo(expected.usesAcl());
        }
    }

    @Test
    @DisplayName("旧值 member 与认不出来的值都归一成全体 —— 老库升级不会把人锁在门外")
    void legacyValuesNormalizeToAll() {
        assertThat(CommandAccess.of("member")).isEqualTo(CommandAccess.ALL);
        assertThat(CommandAccess.of(null)).isEqualTo(CommandAccess.ALL);
        assertThat(CommandAccess.of("乱写的")).isEqualTo(CommandAccess.ALL);
    }

    // ==================== 保存链路 ====================

    @Test
    @DisplayName("★ 保存命令时带的 access 真的落库 —— 以前这个字段后端不认，一保存就掉回全体")
    void savingACommandKeepsItsAccess() throws Exception {
        controller.upsert(request("权限测试", "admin_whitelist"));

        assertThat(store.findByTrigger("权限测试").access()).isEqualTo("admin_whitelist");
    }

    @Test
    @DisplayName("改权限走同一条保存链路：admin → whitelist 原地生效")
    void changingAccessUpdatesTheSameRow() throws Exception {
        controller.upsert(request("权限测试", "admin"));
        long id = store.findByTrigger("权限测试").id();

        controller.upsert(requestWithId(id, "权限测试", "whitelist"));

        assertThat(store.findById(id).access()).isEqualTo("whitelist");
        assertThat(store.listAll()).filteredOn((c) -> c.trigger().equals("权限测试")).hasSize(1);
    }

    @Test
    @DisplayName("没带 access 的老客户端照旧落成全体，不会写成空串")
    void missingAccessFallsBackToAll() throws Exception {
        controller.upsert(request("权限测试", null));

        assertThat(store.findByTrigger("权限测试").access()).isEqualTo("all");
    }

    @Test
    @DisplayName("前端发的字段名就叫 access —— 反序列化钉住，改名会在这里红")
    void requestReadsAccessFromJson() throws Exception {
        CommandController.UpsertCommandRequest body = new ObjectMapper().readValue(
                "{\"trigger\":\"t\",\"reply\":\"r\",\"access\":\"admin_whitelist\"}",
                CommandController.UpsertCommandRequest.class);

        assertThat(body.access()).isEqualTo("admin_whitelist");
    }

    private static CommandController.UpsertCommandRequest request(String trigger, String access) {
        return requestWithId(null, trigger, access);
    }

    private static CommandController.UpsertCommandRequest requestWithId(Long id, String trigger,
                                                                       String access) {
        return new CommandController.UpsertCommandRequest(
                id, trigger, "回复", BotCommand.KIND_TEMPLATE, List.of(), null, BotCommand.MODE_KB,
                "", "all", List.of(), access, true, 0, null, null);
    }
}
