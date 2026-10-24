package com.example.qqbot.command;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.BoardRecordRepository;
import com.example.qqbot.persistence.BoardRepository;
import com.example.qqbot.persistence.Jdbc;
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
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 板块记录：增、查、软删、恢复、到点。
 *
 * <p>重点在<b>软删</b>：删了要能从"已删除"里捞回来，而且编号不回收。
 */
class BoardRecordStoreTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private BoardStore boards;
    private BoardRecordStore records;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("rec.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        ObjectMapper mapper = new ObjectMapper();
        boards = new BoardStore(new BoardRepository(jdbc), mapper);
        boards.init();
        records = new BoardRecordStore(new BoardRecordRepository(jdbc), mapper);
        records.init();
    }

    @AfterEach
    void close() {
        db.close();
    }

    private Instant futureAt(int hour, int minute) {
        return ZonedDateTime.now(AppTime.ZONE).plusDays(1)
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0).toInstant();
    }

    // ==================== 立即展示 ====================

    @Test
    @DisplayName("★ 立即展示的板块：收下就能查到，没有发送时刻")
    void directBoardStoresImmediately() throws Exception {
        Board lfg = boards.find("开黑");
        Instant now = Instant.now();

        long id = records.create(lfg, 100L, 200L, "张三", "今晚八点",
                Map.of("空闲", "今晚8点", "风格", "上分"), List.of(), now);

        BoardRecord record = records.find(id);
        assertThat(record.isScheduled()).isFalse();
        assertThat(record.fireAt()).isNull();
        assertThat(record.text()).isEqualTo("今晚八点");
        assertThat(record.params()).containsEntry("风格", "上分");
        assertThat(records.query(lfg, 100L, 10)).hasSize(1);
    }

    // ==================== 到点发 ====================

    @Test
    @DisplayName("★ 到点发的板块：算出下次时刻，时刻组存进记录")
    void scheduledBoardComputesNextFire() throws Exception {
        Board announcement = boards.find("公告");
        Instant now = Instant.now();
        Instant at = futureAt(21, 30);

        long id = records.create(announcement, 100L, 200L, "管理员", "今晚开黑",
                Map.of("重复", "每天", "时间", "21:30"), List.of(at), now);

        BoardRecord record = records.find(id);
        assertThat(record.isScheduled()).isTrue();
        assertThat(record.fireAt()).isAfter(now);
        assertThat(record.times()).hasSize(1);
        assertThat(record.whenText()).startsWith("每天");
    }

    @Test
    @DisplayName("★ 共 N 次：时间按钟点存，第一次的时刻作为起算日")
    void timesRuleStoresClockSlotsAndAnchor() throws Exception {
        Board board = new Board(1, "打卡", "打卡", List.of(
                ParamSpec.of("周期", "repeat", true, "3次"),
                ParamSpec.multi("时间", ParamType.MOMENT, true, null, 0, null, "21:30")),
                Board.SCHEDULED, "all", DataPolicy.DEFAULT, true, 0, false);
        Instant first = futureAt(21, 30);

        long id = records.create(board, 100L, 200L, "阿明", "打卡",
                Map.of("周期", "3次", "时间", "21:30"), List.of(first), Instant.now());

        BoardRecord record = records.find(id);
        assertThat(record.repeatRule()).isEqualTo("times:3");
        assertThat(record.times()).containsExactly("21:30");
        assertThat(record.whenText()).startsWith("共 3 次");
        assertThat(record.anchorAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("★ 执行完的有限周期不再出现在群友列表，后台仍看得到")
    void sentFiniteRecordHiddenFromVisibleList() throws Exception {
        Board announcement = boards.find("公告");
        Instant at = futureAt(21, 30);
        long id = records.create(announcement, 100L, 200L, "管理员", "公告测试",
                Map.of("重复", "一次", "时间", "21:30"), List.of(at), Instant.now());

        assertThat(records.queryVisible(announcement, 100L, 10))
                .extracting(BoardRecord::id).contains(id);

        records.markSent(records.find(id), Instant.now());

        assertThat(records.queryVisible(announcement, 100L, 10))
                .extracting(BoardRecord::id).doesNotContain(id);
        // 后台历史不受影响
        assertThat(records.query(announcement, 100L, 10))
                .extracting(BoardRecord::id).contains(id);
    }

    @Test
    @DisplayName("★ 重复与时间参数改名后仍能识别")
    void renamedScheduleParamsStillWork() throws Exception {
        Board board = new Board(1, "打卡提醒", "打卡提醒", List.of(
                ParamSpec.of("周期", "repeat", true, "每天"),
                ParamSpec.multi("提醒时间", ParamType.MOMENT, true, null, 0, null, "21:30")),
                Board.SCHEDULED, "all", DataPolicy.DEFAULT, true, 0, false);
        Instant now = Instant.now();

        long id = records.create(board, 100L, 200L, "管理员", "打卡",
                Map.of("周期", "daily", "提醒时间", "21:30"),
                List.of(futureAt(21, 30)), now);

        BoardRecord record = records.find(id);
        assertThat(record.isScheduled()).isTrue();
        assertThat(record.whenText()).startsWith("每天");
    }

    @Test
    @DisplayName("一天三个时刻：一条记录，三个都存着")
    void severalTimesInOneRecord() throws Exception {
        Board announcement = boards.find("公告");
        Instant now = Instant.now();
        Instant first = futureAt(7, 0);

        long id = records.create(announcement, 100L, 200L, "管理员", "打卡",
                Map.of("重复", "每天", "时间", "07:00、14:00、21:30"),
                List.of(first, first.plusSeconds(7 * 3600), first.plusSeconds(14 * 3600)), now);

        BoardRecord record = records.find(id);
        assertThat(record.times()).hasSize(3);
        assertThat(record.displayLine()).contains("#" + id).contains("07:00");
    }

    @Test
    @DisplayName("重复写错会明确报错，不会静默当成一次")
    void badRepeatIsRejected() {
        Board announcement = boards.find("公告");

        assertThatThrownBy(() -> records.create(announcement, 100L, 200L, "管理员", "x",
                Map.of("重复", "隔三差五", "时间", "21:30"),
                List.of(futureAt(21, 30)), Instant.now()))
                .isInstanceOf(CommandParseException.class)
                .hasMessageContaining("没看懂");
    }

    // ==================== 软删 ====================

    @Test
    @DisplayName("★ 删是打标记：查不到，但还在，能恢复")
    void softDeleteHidesButKeeps() throws Exception {
        Board lfg = boards.find("开黑");
        long id = records.create(lfg, 100L, 200L, "张三", "今晚八点", Map.of(), List.of(), Instant.now());

        assertThat(records.softDelete(id, Instant.now())).isTrue();
        assertThat(records.query(lfg, 100L, 10)).as("群友查不到").isEmpty();
        assertThat(records.queryWithDeleted(lfg, 100L, 10)).as("后台看得到").hasSize(1);
        assertThat(records.find(id).isDeleted()).isTrue();

        assertThat(records.restore(id)).isTrue();
        assertThat(records.query(lfg, 100L, 10)).as("恢复后又出现了").hasSize(1);
    }

    @Test
    @DisplayName("重复删第二次返回 false，不会静默成功")
    void secondDeleteIsRejected() throws Exception {
        Board lfg = boards.find("开黑");
        long id = records.create(lfg, 100L, 200L, "张三", "x", Map.of(), List.of(), Instant.now());

        assertThat(records.softDelete(id, Instant.now())).isTrue();
        assertThat(records.softDelete(id, Instant.now())).isFalse();
    }

    // ==================== 到点扫描 ====================

    @Test
    @DisplayName("★ 删掉的记录不会被扫到发出去")
    void deletedRecordIsNotDelivered() throws Exception {
        Board announcement = boards.find("公告");
        Instant now = Instant.now();
        long id = records.create(announcement, 100L, 200L, "管理员", "别发",
                Map.of("重复", "一次", "时间", "21:30"), List.of(futureAt(21, 30)), now);
        records.softDelete(id, now);

        assertThat(records.due(now.plus(Duration.ofDays(2)), 10)).isEmpty();
    }

    @Test
    @DisplayName("到点的取出来，状态变发送中，不会取第二次")
    void dueClaimsOnce() throws Exception {
        Board announcement = boards.find("公告");
        Instant now = Instant.now();
        long id = records.create(announcement, 100L, 200L, "管理员", "到点了",
                Map.of("重复", "一次", "时间", "21:30"), List.of(futureAt(21, 30)), now);

        Instant later = now.plus(Duration.ofDays(2));
        assertThat(records.due(later, 10)).hasSize(1);
        assertThat(records.due(later, 10)).as("已经被抢走").isEmpty();
        assertThat(records.find(id).status()).isEqualTo("sending");
    }
}
