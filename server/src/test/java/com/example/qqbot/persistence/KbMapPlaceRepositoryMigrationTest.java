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
 * {@code kb_map_place} 的**老库升级**。
 *
 * <p>为什么要单独测这一条：{@code CREATE TABLE IF NOT EXISTS} 对**已经存在**的表
 * 一个字段都不会补。所以给点位加 {@code canvas_fp} 这件事，
 * 在全新库上能跑、在老库上会以 {@code no such column: canvas_fp} 起步就炸 ——
 * 而所有其他测试用的都是全新库，正好一条都测不到。
 */
class KbMapPlaceRepositoryMigrationTest {

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private Jdbc jdbc;

    @BeforeEach
    void setUp() {
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(tmp.resolve("old-place.sqlite").toString());
        db = new SqliteDatabase(props);
        db.init();
        jdbc = new Jdbc(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    /** 升级前的表结构：**没有 canvas_fp** */
    private void createOldTable() {
        jdbc.execute("CREATE TABLE kb_map_place ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT, map TEXT NOT NULL,"
                + " page TEXT NOT NULL DEFAULT '', x REAL NOT NULL DEFAULT 0,"
                + " y REAL NOT NULL DEFAULT 0, name TEXT NOT NULL DEFAULT '',"
                + " aliases TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT 'custom',"
                + " note TEXT NOT NULL DEFAULT '', icon TEXT NOT NULL DEFAULT '',"
                + " color TEXT NOT NULL DEFAULT '', landmark INTEGER NOT NULL DEFAULT 0,"
                + " priority INTEGER NOT NULL DEFAULT 0, visible INTEGER NOT NULL DEFAULT 1,"
                + " created_at TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL DEFAULT '')");
        jdbc.insert("INSERT INTO kb_map_place (map, page, x, y, name) VALUES (?,?,?,?,?)",
                "Embervale", "", 3413.0, 4821.0, "我家基地");
    }

    private List<String> columns() {
        return jdbc.query("PRAGMA table_info(kb_map_place)", r -> r.str("name"));
    }

    @Test
    @DisplayName("★ 老库升级：补上 canvas_fp，并且原有数据一条不丢")
    void addsColumnToLegacyTable() {
        createOldTable();
        assertThat(columns()).as("前提：这张老表确实没有 canvas_fp").doesNotContain("canvas_fp");

        new KbMapPlaceRepository(jdbc).initSchema();

        assertThat(columns()).contains("canvas_fp");
        List<KbMapPlaceRepository.PlaceRow> rows =
                new KbMapPlaceRepository(jdbc).byMap("Embervale", false);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).name()).isEqualTo("我家基地");
        assertThat(rows.get(0).canvasFp()).as("老数据没登记过指纹 → 空串 → 会进待复核").isEmpty();
    }

    @Test
    @DisplayName("重复 initSchema 是幂等的（每次启动都会跑）")
    void initSchemaIsIdempotent() {
        KbMapPlaceRepository repo = new KbMapPlaceRepository(jdbc);
        repo.initSchema();
        repo.initSchema();

        assertThat(columns()).contains("canvas_fp");
        assertThat(columns().stream().filter("canvas_fp"::equals)).hasSize(1);
    }
}
