package com.example.qqbot.qa;

import com.example.qqbot.config.QaProperties;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步写入器的验收测试。
 *
 * <p>要证明的两件事：<b>投递立即返回</b>、<b>记录系统出问题时回答不受影响</b>。
 */
class QaRecorderTest {

    @TempDir
    Path base;

    private QaRecord record(String q) {
        return new QaRecord(Instant.now().toString(), 1L, 2L, 3L,
                q, null, "答", 0, true, 1, 0.18, 0.5, 0.5, "vector", "[]",
                "pass", 10, 100, 120, "m",
                List.of());
    }

    /**
     * 一个「库 + Store」的组合。
     *
     * <p>返回 record 而不是只返回 Store：**谁开的谁关** —— 连接现在归
     * {@link SqliteDatabase} 持有，只交出 Store 就会没人关连接，
     * 而 Windows 上那个临时目录随即删不掉（实测过）。
     */
    private record Fixture(SqliteDatabase db, QaStore store) implements AutoCloseable {
        @Override
        public void close() {
            db.close();
        }
    }

    private Fixture fixture(QaProperties props) {
        SqliteDatabase db = new SqliteDatabase(props);
        db.init();
        QaStore s = new QaStore(db, props, new ObjectMapper());
        s.init();
        return new Fixture(db, s);
    }

    private void awaitCount(QaStore s, long expected, long timeoutMs) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        while (s.countStat() < expected && System.currentTimeMillis() - t0 < timeoutMs) {
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("投递后异步落库")
    void recordsAsynchronously() throws Exception {
        QaProperties props = new QaProperties();
        props.setDb(base.resolve("a.sqlite").toString());
        Fixture f = fixture(props);
        QaStore s = f.store();
        QaRecorder recorder = new QaRecorder(props, s);
        recorder.start();

        recorder.record(record("问题一"));
        recorder.record(record("问题二"));
        awaitCount(s, 2, 3000);

        assertThat(s.countStat()).isEqualTo(2);
        assertThat(recorder.recordedCount()).isEqualTo(2);
        assertThat(recorder.droppedCount()).isZero();
        recorder.stop();
        f.close();
    }

    @Test
    @DisplayName("★ 存储不可用时投递仍然立即返回、不抛异常")
    void survivesBrokenStore() {
        QaProperties props = new QaProperties();
        props.setDb(base.resolve("b.sqlite").toString());
        SqliteDatabase brokenDb = new SqliteDatabase(props);
        brokenDb.init();
        QaStore broken = new QaStore(brokenDb, props, new ObjectMapper());
        broken.init();
        brokenDb.close();   // 故意把连接关掉，模拟存储故障

        QaRecorder recorder = new QaRecorder(props, broken);
        recorder.start();

        long t0 = System.currentTimeMillis();
        recorder.record(record("存储挂了"));
        long cost = System.currentTimeMillis() - t0;

        assertThat(cost).as("投递必须立即返回，不能等数据库").isLessThan(200);
        recorder.stop();
    }

    @Test
    @DisplayName("队列满时丢弃并计数，绝不阻塞投递方")
    void dropsWhenQueueFull() throws Exception {
        QaProperties props = new QaProperties();
        props.setDb(base.resolve("c.sqlite").toString());
        props.setQueueCapacity(1);

        // 造一个"卡住的存储"：worker 一进去就出不来，队列必然堆满
        SqliteDatabase stuckDb = new SqliteDatabase(props);
        stuckDb.init();
        QaStore stuck = new QaStore(stuckDb, props, new ObjectMapper()) {
            @Override
            public void insertBatch(List<QaRecord> records) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        QaRecorder recorder = new QaRecorder(props, stuck);
        recorder.start();
        Thread.sleep(50);   // 让 worker 先起来

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 50; i++) {
            recorder.record(record("问题" + i));
        }
        long cost = System.currentTimeMillis() - t0;

        assertThat(cost).as("50 次投递必须瞬间完成，不能等存储").isLessThan(500);
        assertThat(recorder.droppedCount()).as("队列只有 1 格，剩下的必须被丢弃").isGreaterThan(0);
        recorder.stop();
        stuckDb.close();
    }

    @Test
    @DisplayName("关掉开关时完全不投递")
    void disabledRecordsNothing() {
        QaProperties props = new QaProperties();
        props.setEnabled(false);
        Fixture f = fixture(props);
        QaStore s = f.store();
        QaRecorder recorder = new QaRecorder(props, s);
        recorder.start();

        recorder.record(record("不该被记录"));

        assertThat(recorder.backlog()).isZero();
        assertThat(s.countStat()).isZero();
        recorder.stop();
    }
}
