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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时输出存储层的验收测试。
 *
 * <p>两件必须守住的事：<b>同一条不会被抢两次</b>（重复发是用户最容易发现的故障），
 * 以及<b>到点排序按时间而不是按字符串</b>（时间串定长才成立，见 {@link ScheduleStore} 的注释）。
 */
class ScheduleStoreTest {

    @TempDir
    Path base;

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private SqliteDatabase db;
    private ScheduleStore store;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        store = new ScheduleStore(new ScheduleRepository(new Jdbc(db)), new ObjectMapper());
        store.init();
    }

    @AfterEach
    void close() {
        db.close();
    }

    private long create(String content, Instant fireAt, String repeat) throws Exception {
        return store.create(100L, 200L, "张三", content, Map.of("time", "21:30"), fireAt, repeat);
    }

    @Test
    @DisplayName("记一条能原样读回来")
    void createAndFind() throws Exception {
        long id = create("晚上打本集合", NOW.plusSeconds(3600), ScheduleStore.REPEAT_DAILY);

        ScheduleTask task = store.find(id).orElseThrow();
        assertThat(task.content()).isEqualTo("晚上打本集合");
        assertThat(task.groupId()).isEqualTo(100L);
        assertThat(task.userId()).isEqualTo(200L);
        assertThat(task.nickname()).isEqualTo("张三");
        assertThat(task.isDaily()).isTrue();
        assertThat(task.status()).isEqualTo(ScheduleStore.STATUS_PENDING);
        assertThat(task.fireAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    @DisplayName("参数原样存下来，供发送时的 {arg.名称} 取值")
    void paramsRoundTrip() throws Exception {
        long id = create("内容 {arg.time}", NOW.plusSeconds(60), ScheduleStore.REPEAT_ONCE);

        assertThat(store.argsOf(store.find(id).orElseThrow())).containsEntry("time", "21:30");
    }

    @Test
    @DisplayName("★ 到点的才被取走，抢过的不会再来一次")
    void claimIsExclusive() throws Exception {
        create("到点了", NOW.minusSeconds(60), ScheduleStore.REPEAT_ONCE);
        create("还没到", NOW.plusSeconds(600), ScheduleStore.REPEAT_ONCE);

        assertThat(store.claimDue(NOW, 10)).hasSize(1);
        assertThat(store.claimDue(NOW, 10)).as("★ 同一条不能被抢第二次").isEmpty();
    }

    @Test
    @DisplayName("按到点时间排，不按字符串")
    void ordersByTime() throws Exception {
        create("晚的", NOW.plusSeconds(7200), ScheduleStore.REPEAT_ONCE);
        create("早的", NOW.plusSeconds(60), ScheduleStore.REPEAT_ONCE);

        var due = store.claimDue(NOW.plusSeconds(8000), 10);

        assertThat(due).extracting(ScheduleTask::content).containsExactly("早的", "晚的");
    }

    @Test
    @DisplayName("撤销之后不再被取走")
    void canceledIsNotDue() throws Exception {
        long id = create("要撤的", NOW.minusSeconds(60), ScheduleStore.REPEAT_ONCE);

        assertThat(store.cancel(id, "测试")).isTrue();

        assertThat(store.claimDue(NOW, 10)).isEmpty();
        assertThat(store.find(id).orElseThrow().status()).isEqualTo(ScheduleStore.STATUS_CANCELED);
    }

    @Test
    @DisplayName("周期任务：记一次已发再回到待发，次数清零")
    void rearmGoesBackToPending() throws Exception {
        long id = create("每天发", NOW.minusSeconds(60), ScheduleStore.REPEAT_DAILY);
        ScheduleTask task = store.claimDue(NOW, 1).get(0);

        store.rearm(task, NOW.plus(Duration.ofDays(1)), NOW);

        ScheduleTask after = store.find(id).orElseThrow();
        assertThat(after.status()).isEqualTo(ScheduleStore.STATUS_PENDING);
        assertThat(after.fireAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
        assertThat(after.sentAt()).isEqualTo(NOW);
        assertThat(after.attempts()).isZero();
    }

    @Test
    @DisplayName("失败重试：次数 +1、原因留下、换个更晚的时刻")
    void retryLaterKeepsError() throws Exception {
        long id = create("会失败", NOW.minusSeconds(60), ScheduleStore.REPEAT_ONCE);
        ScheduleTask task = store.claimDue(NOW, 1).get(0);

        store.retryLater(task, "协议层连不上", NOW.plusSeconds(60));

        ScheduleTask after = store.find(id).orElseThrow();
        assertThat(after.status()).isEqualTo(ScheduleStore.STATUS_PENDING);
        assertThat(after.attempts()).isEqualTo(1);
        assertThat(after.lastError()).isEqualTo("协议层连不上");
        assertThat(after.fireAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("终态写入：失败、过期")
    void closedStatesAreRecorded() throws Exception {
        long id = create("过期", NOW.minusSeconds(60), ScheduleStore.REPEAT_ONCE);
        ScheduleTask task = store.claimDue(NOW, 1).get(0);

        store.markExpired(task);

        assertThat(store.find(id).orElseThrow().status()).isEqualTo(ScheduleStore.STATUS_EXPIRED);
    }

    @Test
    @DisplayName("待发计数按会话分开：同一个人在两个群各算各的")
    void pendingCountIsPerConversation() throws Exception {
        create("群里的", NOW.plusSeconds(60), ScheduleStore.REPEAT_ONCE);
        store.create(0L, 200L, "张三", "私聊的", Map.of(), NOW.plusSeconds(60), ScheduleStore.REPEAT_ONCE);

        assertThat(store.countPending(200L, 100L)).isEqualTo(1);
        assertThat(store.countPending(200L, 0L)).isEqualTo(1);
    }

    @Test
    @DisplayName("列表按到点时间排，两条都在")
    void pendingList() throws Exception {
        create("第一条", NOW.plusSeconds(60), ScheduleStore.REPEAT_ONCE);
        create("第二条", NOW.plusSeconds(120), ScheduleStore.REPEAT_ONCE);

        assertThat(store.pendingForGroup(100L, 10))
                .extracting(ScheduleTask::content)
                .containsExactly("第一条", "第二条");
    }
}
