package com.example.qqbot.persistence;

import com.example.qqbot.config.PersistenceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「清空地图数据」的**安全边界**。
 *
 * <h2>为什么这个测试比功能本身重要</h2>
 * 这个操作删掉的东西全部可以重新同步回来，**唯一会造成不可挽回损失的方式**，
 * 是把人建的东西一起删掉：自定义点位、手写备注、每张图的图层配置、启用范围。
 * 那些没有任何地方能重新变出来 —— 所以不变量不是"删得干净"，而是"只删该删的"。
 *
 * <p>而"哪张表属于哪一类"这件事只写在 {@link KbMapRepository#clearSynced()} 的注释里，
 * 靠人记住是靠不住的：以后给地图功能加一张同步表，很容易顺手写进那条 DELETE 列表里。
 * 所以这里把八张表**逐张点名**，并用 {@code hasSize(8)} 当绊线 ——
 * 以后新增地图表时这个测试会失败，逼作者回答"它是同步来的还是人建的"。
 */
class KbMapRepositoryClearSyncedTest {

    /** wiki 同步来的四张表：删了能重新变出来 */
    private static final List<String> SYNCED = List.of(
            "kb_map_page", "kb_map_canvas", "kb_map_group", "kb_map_marker");

    /** 人建的四张表：**一张都不许碰** */
    private static final List<String> HUMAN = List.of(
            "kb_map_place", "kb_map_marker_note", "kb_map_layer_default", "kb_map_active");

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private Jdbc jdbc;

    @BeforeEach
    void setUp() {
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(tmp.resolve("clear-synced.sqlite").toString());
        db = new SqliteDatabase(props);
        db.init();
        jdbc = new Jdbc(db);
        // 八张地图表全部建出来。谁建的不要紧 —— 本测试关心的是"清空碰了哪几张"
        new KbMapRepository(jdbc).initSchema();
        new KbMapPlaceRepository(jdbc).initSchema();
        new KbMapLayerRepository(jdbc).initSchema();
        new KbMapNoteRepository(jdbc).initSchema();
        new KbMapActiveRepository(jdbc).initSchema();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private List<String> mapTables() {
        return jdbc.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'kb_map%'"
                + " ORDER BY name", r -> r.str("name"));
    }

    private long rows(String table) {
        return jdbc.count("SELECT COUNT(*) FROM " + table);
    }

    /**
     * 往一张表里塞一行。
     *
     * <p>列名与类型从 {@code PRAGMA table_info} 读，**不写死任何一张表的字段** ——
     * 否则这个测试会在下一次给地图表加列时变成一笔需要人肉维护的债。
     *
     * <p>⚠️ 这里**一律不给 NULL**。原先想"主键给 NULL 让它自增"，但 {@code table_info} 里的
     * {@code pk} 是**主键内的列序号**（1、2、3…），不是"是不是主键"：地图这几张表用的是
     * 复合主键 {@code (map, page, marker_id)}，于是第一列 {@code map} 的 {@code pk} 也是 1，
     * 被当成自增整数主键传了 NULL，直接撞在 {@code NOT NULL constraint failed: ...map} 上。
     * 显式给值最省事：整数主键接受显式 rowid，其余列按类型给 1 / 1.0 / 'test'。
     */
    private void seed(String table) {
        record Col(String name, String type) {
        }
        List<Col> cols = jdbc.query("PRAGMA table_info(" + table + ")",
                r -> new Col(r.str("name"), r.str("type")));
        StringBuilder sql = new StringBuilder("INSERT INTO " + table + " (");
        List<String> holes = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Col c : cols) {
            if (!args.isEmpty()) {
                sql.append(", ");
            }
            sql.append(c.name());
            holes.add("?");
            String type = c.type() == null ? "" : c.type().toUpperCase(Locale.ROOT);
            if (type.contains("INT")) {
                args.add(1);
            } else if (type.contains("REAL") || type.contains("DOUB") || type.contains("FLOA")) {
                args.add(1.0);
            } else {
                args.add("test");
            }
        }
        sql.append(") VALUES (").append(String.join(",", holes)).append(")");
        jdbc.insert(sql.toString(), args.toArray());
    }

    @Test
    @DisplayName("★ 只清同步来的四张表，人建的四张一行不丢")
    void clearsOnlySyncedTables() {
        assertThat(mapTables()).as("绊线：地图表多了或少了，都要回来答一句" +
                "「它是同步来的还是人建的」").hasSize(8);
        for (String t : mapTables()) {
            seed(t);
            assertThat(rows(t)).as("种一行到 " + t).isEqualTo(1);
        }

        KbMapRepository.ClearCounts c = new KbMapRepository(jdbc).clearSynced();

        assertThat(c.total()).as("四张表各一行，正好删掉四行").isEqualTo(4);
        for (String t : SYNCED) {
            assertThat(rows(t)).as(t + " 是 wiki 的投影，应当清空").isZero();
        }
        for (String t : HUMAN) {
            assertThat(rows(t)).as(t + " 是人建的，删了就再也变不回来").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("重复清空幂等：第二次删 0 行，不报错")
    void clearIsIdempotent() {
        seed("kb_map_marker");
        KbMapRepository repo = new KbMapRepository(jdbc);

        assertThat(repo.clearSynced().total()).isEqualTo(1);
        assertThat(repo.clearSynced().total()).isZero();
    }
}
