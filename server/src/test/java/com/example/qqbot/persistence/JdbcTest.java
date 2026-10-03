package com.example.qqbot.persistence;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.QaProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Jdbc} 的事务原语验收 —— 不联网、跑在临时库上。
 *
 * <p>锁住三件事：成功要提交、抛异常要回滚、块内可重入（不会自己把自己锁死）。
 */
class JdbcTest {

    @TempDir
    Path tmp;

    private PersistenceProperties props;
    private SqliteDatabase db;
    private Jdbc jdbc;

    @BeforeEach
    void setUp() {
        props = new PersistenceProperties();
        props.setDb(tmp.resolve("jdbc-test.sqlite").toString());
        db = new SqliteDatabase(props);
        db.init();
        jdbc = new Jdbc(db);
        jdbc.execute("CREATE TABLE t (k TEXT PRIMARY KEY, v INTEGER NOT NULL)");
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private int v(String k) {
        return (int) jdbc.count("SELECT v FROM t WHERE k = ?", k);
    }

    @Test
    @DisplayName("★ 成功就提交")
    void commitsOnSuccess() {
        String out = jdbc.transaction(() -> {
            jdbc.update("INSERT INTO t (k, v) VALUES (?,?)", "a", 1);
            jdbc.update("INSERT INTO t (k, v) VALUES (?,?)", "b", 2);
            return "ok";
        });

        assertThat(out).isEqualTo("ok");
        assertThat(v("a")).isEqualTo(1);
        assertThat(v("b")).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 抛异常就整体回滚 —— 一半写进去比没写更糟")
    void rollsBackOnFailure() {
        assertThatThrownBy(() -> jdbc.transaction(() -> {
            jdbc.update("INSERT INTO t (k, v) VALUES (?,?)", "a", 1);
            throw new IllegalStateException("故意失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.count("SELECT COUNT(*) FROM t")).isZero();
    }

    @Test
    @DisplayName("★ 块内可重入：事务里再调 update/query/batch 不会死锁")
    void reentrantInsideTransaction() {
        int n = jdbc.transaction(() -> {
            jdbc.update("INSERT INTO t (k, v) VALUES (?,?)", "x", 1);
            long seen = jdbc.count("SELECT COUNT(*) FROM t");
            jdbc.batch("INSERT INTO t (k, v) VALUES (?,?)",
                    List.of(new Object[]{"y", 2}, new Object[]{"z", 3}));
            return (int) seen;
        });

        assertThat(n).isEqualTo(1);
        assertThat(jdbc.count("SELECT COUNT(*) FROM t")).isEqualTo(3);
    }

    @Test
    @DisplayName("读-改-写交替（setStatusBatch 那种形态）能在一个事务里完成")
    void readThenWriteInOneTransaction() {
        jdbc.update("INSERT INTO t (k, v) VALUES (?,?)", "a", 0);
        jdbc.update("INSERT INTO t (k, v) VALUES (?,?)", "b", 0);

        jdbc.transaction(() -> {
            for (String k : List.of("a", "b")) {
                // 先读旧值，再决定怎么写 —— 这正是 batch() 表达不了的形态
                int old = v(k);
                jdbc.update("UPDATE t SET v = ? WHERE k = ?", old + 5, k);
            }
            return null;
        });

        assertThat(v("a")).isEqualTo(5);
        assertThat(v("b")).isEqualTo(5);
    }

    @Test
    @DisplayName("insert 返回自增主键")
    void insertReturnsGeneratedKey() {
        long id = jdbc.insert("INSERT INTO t (k, v) VALUES (?,?)", "gen", 9);
        assertThat(id).isPositive();
        assertThat(v("gen")).isEqualTo(9);
    }

    @Test
    @DisplayName("queryOne 没有行时返回 null")
    void queryOneReturnsNull() {
        Integer missing = jdbc.queryOne("SELECT v FROM t WHERE k = ?", r -> r.intOf("v"), "none");
        assertThat(missing).isNull();
    }
}
