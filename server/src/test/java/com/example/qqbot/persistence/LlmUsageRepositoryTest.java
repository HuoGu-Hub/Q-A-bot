package com.example.qqbot.persistence;

import com.example.qqbot.config.PersistenceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日用量表：重复落盘要累加、按区间取、过期能删。
 *
 * <p>最要紧的是第一条 —— 同一分钟的多批数据会被分批写进来，
 * 写成覆盖的话后一批会把前一批抹掉，而且页面上只会显示"用得少"，不会报错。
 */
class LlmUsageRepositoryTest {

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private LlmUsageRepository repo;

    @BeforeEach
    void setUp() {
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(tmp.resolve("usage-test.sqlite").toString());
        db = new SqliteDatabase(props);
        db.init();
        repo = new LlmUsageRepository(new Jdbc(db));
        repo.init();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private static LlmUsageRepository.Row row(String day, String provider, String model,
                                              boolean estimated, long calls,
                                              long tokensIn, long tokensOut) {
        return new LlmUsageRepository.Row(day, provider, model, estimated, calls, tokensIn, tokensOut);
    }

    @Test
    @DisplayName("★ 同一个主键重复落盘是累加，不是覆盖")
    void repeatFlushAccumulates() {
        repo.addAll(List.of(row("2026-11-08", "opencode-go", "m", false, 1, 100, 20)));
        repo.addAll(List.of(row("2026-11-08", "opencode-go", "m", false, 2, 50, 10)));

        List<LlmUsageRepository.Row> rows = repo.between("2026-11-08", "2026-11-08");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).calls()).isEqualTo(3);
        assertThat(rows.get(0).tokensIn()).isEqualTo(150);
        assertThat(rows.get(0).tokensOut()).isEqualTo(30);
    }

    @Test
    @DisplayName("真实值与估算值是两个主键，各记各的")
    void estimatedAndActualCoexist() {
        repo.addAll(List.of(
                row("2026-11-08", "opencode-go", "m", false, 1, 100, 20),
                row("2026-11-08", "opencode-go", "m", true, 1, 40, 8)));

        List<LlmUsageRepository.Row> rows = repo.between("2026-11-08", "2026-11-08");

        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(LlmUsageRepository.Row::estimated).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("按闭区间取；区间外的不返回")
    void betweenIsInclusive() {
        repo.addAll(List.of(
                row("2026-11-06", "a", "m", false, 1, 1, 1),
                row("2026-11-07", "a", "m", false, 1, 1, 1),
                row("2026-11-08", "a", "m", false, 1, 1, 1)));

        assertThat(repo.between("2026-11-07", "2026-11-08")).hasSize(2);
        assertThat(repo.between("2026-11-08", "2026-11-08")).hasSize(1);
    }

    @Test
    @DisplayName("清理只删早于边界的行")
    void pruneKeepsNewerRows() {
        repo.addAll(List.of(
                row("2026-01-01", "a", "m", false, 1, 1, 1),
                row("2026-11-08", "a", "m", false, 1, 1, 1)));

        assertThat(repo.prune("2026-06-01")).isEqualTo(1);
        assertThat(repo.between("2026-01-01", "2026-12-31")).hasSize(1);
    }
}
