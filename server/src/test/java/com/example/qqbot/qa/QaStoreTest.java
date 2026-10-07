package com.example.qqbot.qa;

import com.example.qqbot.TestIds;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存储层验收测试。核心是那条策略：
 * <b>删原文之后，统计表必须一行不少</b>。
 */
class QaStoreTest {

    @TempDir
    Path base;

    private QaProperties props;
    private SqliteDatabase db;
    private QaStore store;

    @BeforeEach
    void setUp() {
        props = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        store = new QaStore(new QaStoreRepository(new Jdbc(db)), props, new ObjectMapper());
        store.init();
    }

    private QaRecord record(String ts, String question, String answer) {
        return new QaRecord(ts, TestIds.GROUP, TestIds.USER, 12345L,
                question, null, answer,
                0, true, 3, 0.18, 0.53, 0.53, "both",
                "[{\"i\":12,\"title\":\"Scrap Cup\",\"score\":0.53,\"src\":\"both\"}]",
                "pass", 120, 3400, 3600, "deepseek-v4.1-flash",
                List.of(new QaRecord.Keyword("废料杯", "Scrap Cup", true)));
    }

    @AfterEach
    void closeStores() {
        db.close();
    }

    @Test
    @DisplayName("写入后统计表和原文表都有数据")
    void insertsBothLayers() {
        store.insertBatch(List.of(record(Instant.now().toString(), "废料杯怎么合成？", "用装饰工作台")));

        assertThat(store.countStat()).isEqualTo(1);
        assertThat(store.countRaw()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 清理只删原文，统计表和关键词表一行不动")
    void purgeRemovesOnlyRaw() {
        String old = Instant.now().minus(200, ChronoUnit.DAYS).toString();
        String fresh = Instant.now().toString();
        store.insertBatch(List.of(
                record(old, "过期的问题", "过期的回答"),
                record(fresh, "新的问题", "新的回答")));
        assertThat(store.countStat()).isEqualTo(2);
        assertThat(store.countRaw()).isEqualTo(2);

        int removed = store.purgeRaw(180, false);

        assertThat(removed).isEqualTo(1);
        assertThat(store.countRaw()).as("只删掉了过期那一条原文").isEqualTo(1);
        assertThat(store.countStat()).as("★ 统计表必须一行不少").isEqualTo(2);

        // 关键词也还在（永久层）
        try (var st = db.connection().createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM qa_keyword")) {
            rs.next();
            assertThat(rs.getInt(1)).as("关键词表也必须一行不少").isEqualTo(2);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("dry-run 只统计不删除")
    void dryRunDoesNotDelete() {
        store.insertBatch(List.of(
                record(Instant.now().minus(200, ChronoUnit.DAYS).toString(), "旧问题", "旧回答")));

        int wouldRemove = store.purgeRaw(180, true);

        assertThat(wouldRemove).isEqualTo(1);
        assertThat(store.countRaw()).as("dry-run 不能真的删").isEqualTo(1);
    }

    @Test
    @DisplayName("保留期为 0 表示永不删除")
    void zeroRetentionKeepsEverything() {
        store.insertBatch(List.of(
                record(Instant.now().minus(999, ChronoUnit.DAYS).toString(), "很旧的问题", "很旧的回答")));

        assertThat(store.purgeRaw(0, false)).isZero();
        assertThat(store.countRaw()).isEqualTo(1);
    }

    @Test
    @DisplayName("空批次不报错；重复插入正常累加")
    void handlesEmptyAndRepeated() {
        store.insertBatch(List.of());
        store.insertBatch(List.of(record(Instant.now().toString(), "一", "一")));
        store.insertBatch(List.of(record(Instant.now().toString(), "二", "二")));

        assertThat(store.countStat()).isEqualTo(2);
    }

    @Test
    @DisplayName("标注：写入 verdict 并能读回")
    void annotatesRecord() {
        store.insertBatch(List.of(record(Instant.now().toString(), "问题", "答案")));

        assertThat(store.annotate(1, "bad", "答非所问", "human")).isTrue();

        try (var st = db.connection().createStatement();
             var rs = st.executeQuery("SELECT verdict, verdict_note, verdict_by FROM qa_stat WHERE id=1")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("bad");
            assertThat(rs.getString(2)).isEqualTo("答非所问");
            assertThat(rs.getString(3)).isEqualTo("human");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("标注不存在的记录返回 false")
    void annotateMissingReturnsFalse() {
        assertThat(store.annotate(999, "good", null, "human")).isFalse();
    }

    @Test
    @DisplayName("★ 追问标记：只覆盖未标注的记录，不覆盖人工结论")
    void followUpDoesNotOverwriteHumanVerdict() {
        store.insertBatch(List.of(
                record(Instant.now().toString(), "第一条", "答"),
                record(Instant.now().toString(), "第二条", "答")));

        assertThat(store.markFollowUp(1)).as("未标注的可被自动标记").isTrue();
        store.annotate(2, "good", null, "human");
        assertThat(store.markFollowUp(2)).as("★ 人工已标注的不该被自动覆盖").isFalse();
        assertThat(store.markFollowUp(1)).as("已经是 follow_up 了，不重复标").isFalse();
    }

    @Test
    @DisplayName("关掉开关时不建库、写入变空操作")
    void disabledIsNoop() {
        QaProperties off = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setEnabled(false);   // 关的是"库"，不是"问答记录"
        persist.setDb(base.resolve("never.sqlite").toString());
        SqliteDatabase offDb = new SqliteDatabase(persist);
        offDb.init();
        QaStore offStore = new QaStore(new QaStoreRepository(new Jdbc(offDb)), off, new ObjectMapper());
        offStore.init();

        assertThat(offStore.isAvailable()).isFalse();
        offStore.insertBatch(List.of(record(Instant.now().toString(), "x", "y")));
        assertThat(offStore.countStat()).isZero();
        assertThat(base.resolve("never.sqlite")).doesNotExist();
    }
}
