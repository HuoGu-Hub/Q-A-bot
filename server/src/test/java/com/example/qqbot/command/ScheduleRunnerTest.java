package com.example.qqbot.command;

import com.example.qqbot.config.BotProperties;
import com.example.qqbot.config.CommandProperties;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.ScheduleProperties;
import com.example.qqbot.kb.Glossary;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.onebot.outbound.OutboundSender;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.ScheduleRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.qa.QaAnalytics;
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
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时投递的验收测试。
 *
 * <p>要守住的四件事：<b>过期不补发</b>、<b>到点用发送时刻渲染</b>、
 * <b>周期任务推下一个时刻而不是连发</b>、<b>失败能停</b>。
 */
class ScheduleRunnerTest {

    @TempDir
    Path base;

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final long GROUP = 100L;
    private static final long USER = 200L;

    private SqliteDatabase db;
    private ScheduleStore store;
    private OutboundSender outbound;
    private ScheduleProperties props;
    private ScheduleRunner runner;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        store = new ScheduleStore(new ScheduleRepository(new Jdbc(db)), new ObjectMapper());
        store.init();

        outbound = mock(OutboundSender.class);
        props = new ScheduleProperties();

        KbBlockStore blocks = mock(KbBlockStore.class);
        when(blocks.isAvailable()).thenReturn(false);
        Glossary glossary = mock(Glossary.class);
        VariableRenderer renderer = new VariableRenderer(new CommandProperties(), mock(CommandStore.class),
                blocks, glossary, mock(QaAnalytics.class), new BotProperties());

        runner = new ScheduleRunner(store, props, renderer, outbound);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private long createGroupTask(String content, Map<String, String> args, Instant fireAt, String repeat)
            throws Exception {
        return store.create(GROUP, USER, "张三", content, args, fireAt, repeat);
    }

    @Test
    @DisplayName("★ 到点发到群里，状态变已发")
    void deliversGroupTask() throws Exception {
        long id = createGroupTask("晚上打本集合", Map.of(), NOW.minusSeconds(5), ScheduleStore.REPEAT_ONCE);

        assertThat(runner.runOnce(NOW)).isEqualTo(1);

        verify(outbound).sendToGroup(eq(GROUP), eq("晚上打本集合"));
        assertThat(store.find(id).orElseThrow().status()).isEqualTo(ScheduleStore.STATUS_SENT);
    }

    @Test
    @DisplayName("★ 渲染用发送时刻：{user} 是创建者，{time} 是现在")
    void rendersAtSendTime() throws Exception {
        // 发送时刻 NOW = 本地 20:00
        createGroupTask("{user} 的 {time} 提醒", Map.of(), NOW.minusSeconds(5), ScheduleStore.REPEAT_ONCE);

        runner.runOnce(NOW);

        LocalDateTime local = LocalDateTime.ofInstant(NOW, AppTime.ZONE);
        String expected = "张三 的 " + local.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + " 提醒";
        verify(outbound).sendToGroup(eq(GROUP), eq(expected));
    }

    @Test
    @DisplayName("参数在发送时才展开：{arg.time}")
    void rendersNamedArgs() throws Exception {
        createGroupTask("时间是 {arg.time}", Map.of("time", "21:30"), NOW.minusSeconds(5), ScheduleStore.REPEAT_ONCE);

        runner.runOnce(NOW);

        verify(outbound).sendToGroup(eq(GROUP), eq("时间是 21:30"));
    }

    @Test
    @DisplayName("私聊任务走私聊出口")
    void deliversPrivateTask() throws Exception {
        store.create(0L, USER, "张三", "私聊提醒", Map.of(), NOW.minusSeconds(5), ScheduleStore.REPEAT_ONCE);

        runner.runOnce(NOW);

        verify(outbound).sendToPrivate(eq(USER), eq("私聊提醒"));
    }

    @Test
    @DisplayName("★ 超过宽限期：标记过期，一条都不发")
    void tooLateIsExpired() throws Exception {
        long id = createGroupTask("早该发了", Map.of(), NOW.minus(Duration.ofHours(2)), ScheduleStore.REPEAT_ONCE);

        assertThat(runner.runOnce(NOW)).isEqualTo(1);

        verify(outbound, never()).sendToGroup(eq(GROUP), anyString());
        assertThat(store.find(id).orElseThrow().status()).isEqualTo(ScheduleStore.STATUS_EXPIRED);
    }

    @Test
    @DisplayName("★ 周期任务：发完推下一个时刻，且保持原来那个钟点")
    void dailyTaskRearms() throws Exception {
        Instant scheduled = NOW.minusSeconds(5);
        long id = createGroupTask("每天提醒", Map.of(), scheduled, ScheduleStore.REPEAT_DAILY);

        runner.runOnce(NOW);

        ScheduleTask after = store.find(id).orElseThrow();
        assertThat(after.status()).isEqualTo(ScheduleStore.STATUS_PENDING);
        assertThat(after.fireAt()).as("下一个时刻 = 原定时刻 + 1 天，而不是「现在 + 1 天」")
                .isEqualTo(scheduled.plus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("停机很久后只推一个未来的时刻，不会连着补发")
    void dailyRearmSkipsMissedDays() throws Exception {
        long id = createGroupTask("每天提醒", Map.of(), NOW.minus(Duration.ofDays(3)), ScheduleStore.REPEAT_DAILY);
        // 宽限期放大到能容忍三天前那条，专门验"下一个时刻"这段逻辑
        props.setGraceMinutes(10 * 24 * 60);

        runner.runOnce(NOW);

        ScheduleTask after = store.find(id).orElseThrow();
        assertThat(after.fireAt()).isAfter(NOW);
        assertThat(after.fireAt()).isBefore(NOW.plus(Duration.ofDays(2)));
    }

    @Test
    @DisplayName("★ 发送失败：换一个更晚的时刻重试，不丢任务")
    void failureSchedulesRetry() throws Exception {
        long id = createGroupTask("会失败", Map.of(), NOW.minusSeconds(5), ScheduleStore.REPEAT_ONCE);
        when(outbound.sendToGroup(eq(GROUP), anyString())).thenThrow(new RuntimeException("协议层连不上"));

        runner.runOnce(NOW);

        ScheduleTask after = store.find(id).orElseThrow();
        assertThat(after.status()).isEqualTo(ScheduleStore.STATUS_PENDING);
        assertThat(after.attempts()).isEqualTo(1);
        assertThat(after.lastError()).contains("协议层连不上");
        assertThat(after.fireAt()).isAfter(NOW);
    }

    @Test
    @DisplayName("★ 连续失败到上限就停，不无限重试")
    void stopsAfterMaxAttempts() throws Exception {
        long id = createGroupTask("总会失败", Map.of(), NOW.minusSeconds(5), ScheduleStore.REPEAT_ONCE);
        when(outbound.sendToGroup(eq(GROUP), anyString())).thenThrow(new RuntimeException("协议层连不上"));
        props.setMaxAttempts(2);

        runner.runOnce(NOW);                 // 第 1 次失败 → 安排重试
        Instant retryAt = store.find(id).orElseThrow().fireAt();
        runner.runOnce(retryAt);             // 第 2 次失败 → 到达上限

        assertThat(store.find(id).orElseThrow().status()).isEqualTo(ScheduleStore.STATUS_FAILED);
    }

    @Test
    @DisplayName("总开关关掉后一轮都不扫")
    void disabledPolicyDoesNothing() throws Exception {
        createGroupTask("不该发", Map.of(), NOW.minusSeconds(5), ScheduleStore.REPEAT_ONCE);
        props.setEnabled(false);

        assertThat(runner.runOnce(NOW)).isZero();

        verify(outbound, never()).sendToGroup(eq(GROUP), anyString());
    }

    @Test
    @DisplayName("没到点的一条都不动")
    void notDueYetStaysPending() throws Exception {
        createGroupTask("以后", Map.of(), NOW.plusSeconds(600), ScheduleStore.REPEAT_ONCE);

        assertThat(runner.runOnce(NOW)).isZero();

        verify(outbound, never()).sendToGroup(eq(GROUP), anyString());
    }
}
