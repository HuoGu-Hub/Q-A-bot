package com.example.qqbot.command;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.ScheduleProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.ScheduleRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.time.AppTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三个定时输出动作的验收测试。
 *
 * <p>重点不是"回复好不好看"，而是三条规则：<b>过去的时间不接</b>、
 * <b>别人设的任务撤不掉</b>、<b>私聊默认不开放</b>。
 */
class ScheduleActionsTest {

    @TempDir
    Path base;

    private static final long GROUP = 100L;
    private static final long USER = 200L;
    private static final long OTHER = 300L;

    private SqliteDatabase db;
    private ScheduleStore store;
    private ScheduleProperties props;
    private ScheduleCreateAction create;
    private ScheduleListAction list;
    private ScheduleDeleteAction delete;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        store = new ScheduleStore(new ScheduleRepository(new Jdbc(db)), new ObjectMapper());
        store.init();
        props = new ScheduleProperties();
        create = new ScheduleCreateAction(store, props);
        list = new ScheduleListAction(store);
        delete = new ScheduleDeleteAction(store);
    }

    @AfterEach
    void close() {
        db.close();
    }

    // ==================== 夹具 ====================

    private static ActionContext groupCtx(long userId, CommandRole role, String text) {
        return new ActionContext(userId, GROUP, true, role, "张三", "set/output", text);
    }

    private static CommandArgs withMoment(String raw, Instant at) {
        return new CommandArgs(Map.of("time", raw), Map.of("time", at));
    }

    private static CommandArgs withMomentAndDate(String raw, Instant at, LocalDate date) {
        return new CommandArgs(Map.of("time", raw, "date", "10-20"),
                Map.of("time", at, "date", date));
    }

    private static CommandArgs withId(long id) {
        return new CommandArgs(Map.of("id", String.valueOf(id)), Map.of("id", id));
    }

    // ==================== 设置 ====================

    @Test
    @DisplayName("不带日期：每天发，回执写明编号与时刻")
    void createDaily() throws Exception {
        Instant fire = Instant.now().plus(Duration.ofHours(3));

        String reply = create.execute(groupCtx(USER, CommandRole.MEMBER, "晚上打本集合"),
                withMoment("21:30", fire));

        assertThat(reply).startsWith("已记下 #").contains("每天").contains("本群").contains("晚上打本集合");
        var tasks = store.pendingForGroup(GROUP, 10);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).isDaily()).isTrue();
        assertThat(tasks.get(0).nickname()).isEqualTo("张三");
    }

    @Test
    @DisplayName("★ 带日期：只发一次，时刻取 #time# 的钟点配那一天")
    void createOnceWithDate() throws Exception {
        LocalDate date = LocalDate.now(AppTime.ZONE).plusDays(5);
        Instant clock = Instant.now().plus(Duration.ofHours(3));

        String reply = create.execute(groupCtx(USER, CommandRole.MEMBER, "集合"),
                withMomentAndDate("21:30", clock, date));

        assertThat(reply).contains("输出一次").doesNotContain("每天");
        var task = store.pendingForGroup(GROUP, 10).get(0);
        assertThat(task.isDaily()).isFalse();
        assertThat(LocalDateTime.ofInstant(task.fireAt(), AppTime.ZONE).toLocalDate())
                .as("日期来自 #date#，钟点来自 #time#").isEqualTo(date);
    }

    @Test
    @DisplayName("没写内容不接")
    void createNeedsContent() {
        Instant fire = Instant.now().plus(Duration.ofHours(3));

        assertThatThrownBy(() -> create.execute(groupCtx(USER, CommandRole.MEMBER, "  "),
                withMoment("21:30", fire)))
                .isInstanceOf(ActionException.class)
                .hasMessageContaining("还没写要发的内容");
    }

    @Test
    @DisplayName("内容超长不接")
    void createRejectsLongContent() {
        props.setMaxContentChars(5);
        Instant fire = Instant.now().plus(Duration.ofHours(3));

        assertThatThrownBy(() -> create.execute(groupCtx(USER, CommandRole.MEMBER, "一二三四五六"),
                withMoment("21:30", fire)))
                .isInstanceOf(ActionException.class)
                .hasMessageContaining("太长");
    }

    @Test
    @DisplayName("★ 过去的时间不接")
    void createRejectsPast() {
        assertThatThrownBy(() -> create.execute(groupCtx(USER, CommandRole.MEMBER, "内容"),
                withMoment("08:00", Instant.now().minus(Duration.ofHours(1)))))
                .isInstanceOf(ActionException.class)
                .hasMessageContaining("已经过去");
    }

    @Test
    @DisplayName("太远的时间不接")
    void createRejectsTooFar() {
        props.setMaxFutureDays(1);
        Instant fire = Instant.now().plus(Duration.ofDays(5));

        assertThatThrownBy(() -> create.execute(groupCtx(USER, CommandRole.MEMBER, "内容"),
                withMoment("21:30", fire)))
                .isInstanceOf(ActionException.class)
                .hasMessageContaining("天以内");
    }

    @Test
    @DisplayName("★ 私聊默认不开放")
    void createRejectsPrivateByDefault() {
        ActionContext privateCtx = new ActionContext(USER, 0L, false, CommandRole.MEMBER,
                "张三", "set/output", "内容");
        Instant fire = Instant.now().plus(Duration.ofHours(3));

        assertThatThrownBy(() -> create.execute(privateCtx, withMoment("21:30", fire)))
                .isInstanceOf(ActionException.class)
                .hasMessageContaining("私聊");
    }

    @Test
    @DisplayName("★ 待发条数到上限就不让再挂了")
    void createRespectsPendingCap() throws Exception {
        props.setMaxPendingPerUser(1);
        Instant fire = Instant.now().plus(Duration.ofHours(3));
        create.execute(groupCtx(USER, CommandRole.MEMBER, "第一条"), withMoment("21:30", fire));

        assertThatThrownBy(() -> create.execute(groupCtx(USER, CommandRole.MEMBER, "第二条"),
                withMoment("21:30", fire)))
                .isInstanceOf(ActionException.class)
                .hasMessageContaining("已经挂了");
    }

    // ==================== 列表 ====================

    @Test
    @DisplayName("空列表说人话")
    void listEmpty() {
        assertThat(list.execute(groupCtx(USER, CommandRole.MEMBER, ""), CommandArgs.empty()))
                .isEqualTo("本群还没有定时输出");
    }

    @Test
    @DisplayName("列表带上编号与内容")
    void listShowsTasks() throws Exception {
        Instant fire = Instant.now().plus(Duration.ofHours(3));
        create.execute(groupCtx(USER, CommandRole.MEMBER, "晚上打本集合"), withMoment("21:30", fire));

        String out = list.execute(groupCtx(USER, CommandRole.MEMBER, ""), CommandArgs.empty());

        assertThat(out).contains("本群待发").contains("#1").contains("每天").contains("晚上打本集合");
    }

    // ==================== 撤销 ====================

    private long seedTask() throws Exception {
        return store.create(GROUP, USER, "张三", "要撤的", Map.of(),
                Instant.now().plus(Duration.ofHours(3)), ScheduleStore.REPEAT_ONCE);
    }

    @Test
    @DisplayName("创建者本人能撤")
    void creatorCanDelete() throws Exception {
        long id = seedTask();

        String reply = delete.execute(groupCtx(USER, CommandRole.MEMBER, ""), withId(id));

        assertThat(reply).contains("已撤销").contains("#" + id);
        assertThat(store.find(id).orElseThrow().status()).isEqualTo(ScheduleStore.STATUS_CANCELED);
    }

    @Test
    @DisplayName("★ 别人设的任务，普通成员撤不掉")
    void otherMemberCannotDelete() throws Exception {
        long id = seedTask();

        assertThatThrownBy(() -> delete.execute(groupCtx(OTHER, CommandRole.MEMBER, ""), withId(id)))
                .isInstanceOf(ActionException.class)
                .hasMessageContaining("只有本人或管理员");
        assertThat(store.find(id).orElseThrow().status()).isEqualTo(ScheduleStore.STATUS_PENDING);
    }

    @Test
    @DisplayName("★ 管理员能撤别人的")
    void adminCanDelete() throws Exception {
        long id = seedTask();

        String reply = delete.execute(groupCtx(OTHER, CommandRole.ADMIN, ""), withId(id));

        assertThat(reply).contains("已撤销");
        assertThat(store.find(id).orElseThrow().status()).isEqualTo(ScheduleStore.STATUS_CANCELED);
    }

    @Test
    @DisplayName("★ 别的群的任务在这里查不到，也撤不掉")
    void crossGroupIsInvisible() throws Exception {
        long other = store.create(999L, OTHER, "别人", "别的群的", Map.of(),
                Instant.now().plus(Duration.ofHours(3)), ScheduleStore.REPEAT_ONCE);

        assertThatThrownBy(() -> delete.execute(groupCtx(OTHER, CommandRole.OWNER, ""), withId(other)))
                .isInstanceOf(ActionException.class)
                .hasMessageContaining("没有编号");
    }

    @Test
    @DisplayName("编号不存在时说清楚")
    void missingId() {
        assertThatThrownBy(() -> delete.execute(groupCtx(USER, CommandRole.MEMBER, ""), withId(999L)))
                .isInstanceOf(ActionException.class)
                .hasMessageContaining("没有编号 #999");
    }

    @Test
    @DisplayName("重复撤销说已经被撤了")
    void doubleDeleteIsRejected() throws Exception {
        long id = seedTask();
        delete.execute(groupCtx(USER, CommandRole.MEMBER, ""), withId(id));

        assertThatThrownBy(() -> delete.execute(groupCtx(USER, CommandRole.MEMBER, ""), withId(id)))
                .isInstanceOf(ActionException.class)
                .hasMessageContaining("撤销失败");
    }
}
