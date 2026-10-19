package com.example.qqbot.command;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.ScheduleRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端定时任务接口的验收测试。
 *
 * <p>这个页面的存在理由是三条：跨群全景、撤掉任何一条、以及<b>看到失败原因</b>。
 * 所以用例集中在筛选是否准、计数是否对、以及"撤不掉的时候有没有说清楚"。
 */
class ScheduleAdminTest {

    @TempDir
    Path base;

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final long GROUP = 100L;
    private static final long OTHER_GROUP = 200L;
    private static final long USER = 300L;

    private SqliteDatabase db;
    private ScheduleStore store;
    private ScheduleController controller;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        store = new ScheduleStore(new ScheduleRepository(new Jdbc(db)), new ObjectMapper());
        store.init();
        controller = new ScheduleController(store);
    }

    @AfterEach
    void close() {
        db.close();
    }

    // ==================== 夹具 ====================

    private long create(long groupId, String content, Instant fireAt) throws Exception {
        return store.create(groupId, USER, "张三", content, Map.of("time", "21:30"),
                fireAt, ScheduleStore.REPEAT_ONCE);
    }

    /** 直接把行推到某个状态。仓库那几个方法按 id 更新，不看当前状态，所以够用 */
    private void markAs(long id, String status) {
        ScheduleTask task = store.find(id).orElseThrow();
        switch (status) {
            case "sent" -> store.markSent(task, NOW);
            case "failed" -> store.markFailed(task, "协议层连不上");
            case "expired" -> store.markExpired(task);
            case "canceled" -> store.cancel(id, "测试撤销");
            default -> { }
        }
    }

    private List<ScheduleTask> listAdmin(String status, long groupId, int limit) {
        return store.adminList(status, groupId, limit);
    }

    // ==================== 筛选 ====================

    @Test
    @DisplayName("不筛状态时全都能看到 —— 包括别的群、别的状态的")
    void listsEverything() throws Exception {
        create(GROUP, "群里的", NOW.plusSeconds(60));
        long sent = create(GROUP, "发过的", NOW.minusSeconds(600));
        markAs(sent, "sent");
        create(OTHER_GROUP, "别人群的", NOW.plusSeconds(120));

        assertThat(listAdmin(null, 0, 50)).hasSize(3);
    }

    @Test
    @DisplayName("按状态筛：失败与过期能单独捞出来")
    void filtersByStatus() throws Exception {
        create(GROUP, "待发的", NOW.plusSeconds(60));
        long failed = create(GROUP, "失败的", NOW.minusSeconds(60));
        markAs(failed, "failed");
        long expired = create(GROUP, "过期的", NOW.minusSeconds(7200));
        markAs(expired, "expired");

        assertThat(listAdmin("failed", 0, 50)).extracting(ScheduleTask::content)
                .containsExactly("失败的");
        assertThat(listAdmin("expired", 0, 50)).extracting(ScheduleTask::content)
                .containsExactly("过期的");
        assertThat(listAdmin("pending", 0, 50)).extracting(ScheduleTask::content)
                .containsExactly("待发的");
    }

    @Test
    @DisplayName("按群筛")
    void filtersByGroup() throws Exception {
        create(GROUP, "群里的", NOW.plusSeconds(60));
        create(OTHER_GROUP, "别人群的", NOW.plusSeconds(60));

        assertThat(listAdmin(null, GROUP, 50)).extracting(ScheduleTask::content)
                .containsExactly("群里的");
    }

    @Test
    @DisplayName("按到点时间倒序：管理端先看最近要发的与刚发过的")
    void ordersByFireAtDesc() throws Exception {
        create(GROUP, "晚的", NOW.plusSeconds(7200));
        create(GROUP, "早的", NOW.plusSeconds(60));

        assertThat(listAdmin(null, 0, 50)).extracting(ScheduleTask::content)
                .containsExactly("晚的", "早的");
    }

    @Test
    @DisplayName("条数上限生效")
    void respectsLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            create(GROUP, "第" + i, NOW.plusSeconds(60L * (i + 1)));
        }

        assertThat(listAdmin(null, 0, 2)).hasSize(2);
    }

    // ==================== 计数 ====================

    @Test
    @DisplayName("概览数字按状态分开")
    void countsByStatus() throws Exception {
        create(GROUP, "待发 A", NOW.plusSeconds(60));
        create(GROUP, "待发 B", NOW.plusSeconds(120));
        long failed = create(GROUP, "失败", NOW.minusSeconds(60));
        markAs(failed, "failed");
        long canceled = create(GROUP, "撤销", NOW.plusSeconds(180));
        markAs(canceled, "canceled");

        Map<String, Long> counts = store.statusCounts();

        assertThat(counts).containsEntry("pending", 2L);
        assertThat(counts).containsEntry("failed", 1L);
        assertThat(counts).containsEntry("canceled", 1L);
    }

    // ==================== 接口形状 ====================

    @Test
    @DisplayName("列表接口：带概览、带任务，且不吐出内部字段")
    void controllerListShape() throws Exception {
        long id = create(GROUP, "晚上打本", NOW.plusSeconds(60));
        markAs(id, "failed");

        Map<String, Object> out = controller.list("all", 0, 50);

        assertThat(out).containsKey("available");
        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) out.get("counts");
        assertThat(counts).containsEntry("failed", 1L);
        List<ScheduleController.ScheduleView> tasks = tasksOf(out);
        assertThat(tasks).hasSize(1);
        ScheduleTask raw = store.find(id).orElseThrow();
        ScheduleController.ScheduleView view = tasks.get(0);
        assertThat(view.id()).isEqualTo(id);
        assertThat(view.content()).isEqualTo("晚上打本");
        assertThat(view.status()).isEqualTo("failed");
        assertThat(view.lastError()).isEqualTo("协议层连不上");
        assertThat(view.fireAt()).isEqualTo(raw.fireAt());
    }

    @Test
    @DisplayName("参数化的空串与 all 都表示不筛")
    void blankStatusMeansAll() throws Exception {
        create(GROUP, "待发", NOW.plusSeconds(60));
        long sent = create(GROUP, "已发", NOW.minusSeconds(60));
        markAs(sent, "sent");

        assertThat(tasksOf(controller.list(null, 0, 50))).hasSize(2);
        assertThat(tasksOf(controller.list("", 0, 50))).hasSize(2);
        assertThat(tasksOf(controller.list("  ", 0, 50))).hasSize(2);
    }

    @SuppressWarnings("unchecked")
    private static List<ScheduleController.ScheduleView> tasksOf(Map<String, Object> out) {
        return (List<ScheduleController.ScheduleView>) out.get("tasks");
    }

    // ==================== 撤销 ====================

    @Test
    @DisplayName("★ 管理员能撤任意一条，原因写进行里")
    void adminCanCancelAnyTrace() throws Exception {
        long id = create(OTHER_GROUP, "别人群的任务", NOW.plusSeconds(60));

        var response = controller.cancel(Map.of("id", id));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ScheduleTask after = store.find(id).orElseThrow();
        assertThat(after.status()).isEqualTo(ScheduleStore.STATUS_CANCELED);
        assertThat(after.lastError()).contains("管理员");
    }

    @Test
    @DisplayName("★ 已经发过的撤不了，且说明原因")
    void cannotCancelSent() throws Exception {
        long id = create(GROUP, "发过的", NOW.minusSeconds(60));
        markAs(id, "sent");

        var response = controller.cancel(Map.of("id", id));

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(String.valueOf(response.getBody())).contains("不是待发状态");
    }

    @Test
    @DisplayName("重复撤销第二次会被拒")
    void cannotCancelTwice() throws Exception {
        long id = create(GROUP, "撤两次", NOW.plusSeconds(60));
        controller.cancel(Map.of("id", id));

        var second = controller.cancel(Map.of("id", id));

        assertThat(second.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("没给 id 或 id 不是数字都被拒")
    void badIdIsRejected() {
        assertThat(controller.cancel(Map.of()).getStatusCode().is4xxClientError()).isTrue();
        assertThat(controller.cancel(Map.of("id", "abc")).getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("撤销之后概览里的待发数跟着减")
    void countsFollowCancel() throws Exception {
        long id = create(GROUP, "待撤的", NOW.plusSeconds(60));
        assertThat(store.statusCounts()).containsEntry("pending", 1L);

        controller.cancel(Map.of("id", id));

        assertThat(store.statusCounts()).containsEntry("pending", 0L);
        assertThat(store.statusCounts()).containsEntry("canceled", 1L);
    }

    // ==================== 私聊的拒绝理由 ====================

    @Test
    @DisplayName("★ 私聊里拒绝要说「去群里用」，而不是「只有群主或管理员」")
    void privateChatDenialExplainsItself() {
        assertThat(CommandRole.ADMIN.describe(false)).contains("要在群里用");
        assertThat(CommandRole.OWNER.describe(false)).contains("要在群里用");
        assertThat(CommandRole.ADMIN.describe(true)).contains("群主或管理员");
    }

    @Test
    @DisplayName("距现在多久都能原样读回来，不受时区影响")
    void fireAtRoundTrip() throws Exception {
        Instant at = Instant.parse("2026-12-01T13:30:00Z");
        long id = create(GROUP, "跨年", at);

        assertThat(store.adminList(null, 0, 10).get(0).fireAt()).isEqualTo(at);
        assertThat(store.find(id).orElseThrow().fireAt()).isEqualTo(at);
        assertThat(Duration.between(NOW, at).toDays()).isGreaterThan(0);
    }
}
