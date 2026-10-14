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
 * 清掉**不在白名单里**的地图数据。
 *
 * <p>这是整套"多地图残留"清理里唯一会**删数据**的一步，所以它必须满足三件事：
 * <ol>
 *   <li>白名单里的地图一行都不能少；</li>
 *   <li>白名单外的表要一张不落（漏一张就会留下"半张地图"）；</li>
 *   <li>白名单为空时**什么都不做** —— 那是"不过滤"的旧语义，不是"清空"。</li>
 * </ol>
 */
class KbMapRepositoryPurgeTest {

    @TempDir
    Path tmp;

    private SqliteDatabase db;
    private Jdbc jdbc;
    private KbMapRepository repo;

    @BeforeEach
    void setUp() {
        PersistenceProperties props = new PersistenceProperties();
        props.setDb(tmp.resolve("purge.sqlite").toString());
        db = new SqliteDatabase(props);
        db.init();
        jdbc = new Jdbc(db);
        repo = new KbMapRepository(jdbc);
        repo.initSchema();
        // 备注表不归 KbMapRepository 管（由 MapNoteService 的仓储创建），这里自己建一张
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_marker_note ("
                + " map TEXT NOT NULL, page TEXT NOT NULL DEFAULT '', marker_id TEXT NOT NULL,"
                + " alias TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',"
                + " category TEXT NOT NULL DEFAULT '', landmark INTEGER NOT NULL DEFAULT 0,"
                + " priority INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0,"
                + " updated_at TEXT NOT NULL DEFAULT '',"
                + " PRIMARY KEY (map, page, marker_id))");
        seed("Embervale");
        seed("EarlyAccess");
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    /** 给一张地图塞满各表各一行 —— 清理时一张表都不能漏 */
    private void seed(String map) {
        jdbc.insert("INSERT INTO kb_map_page (page, map, revid) VALUES (?,?,?)", "Map:" + map + "/Main", map, 1L);
        jdbc.insert("INSERT INTO kb_map_canvas (map, page) VALUES (?,?)", map, "Map:" + map + "/Main");
        jdbc.insert("INSERT INTO kb_map_group (map, key) VALUES (?,?)", map, "Village");
        jdbc.insert("INSERT INTO kb_map_marker (map, page, marker_id, grp, name, x, y)"
                + " VALUES (?,?,?,?,?,?,?)", map, "Map:" + map + "/Main", map + "-1", "Village",
                map + " 的村子", 1.0, 2.0);
        jdbc.insert("INSERT INTO kb_map_marker_note (map, page, marker_id) VALUES (?,?,?)",
                map, "Map:" + map + "/Main", map + "-1");
    }

    private long rows(String table) {
        return jdbc.count("SELECT COUNT(*) FROM " + table);
    }

    private long rowsOf(String table, String map) {
        return jdbc.count("SELECT COUNT(*) FROM " + table + " WHERE map = ?", map);
    }

    @Test
    @DisplayName("★ 只清白名单外的地图：留下来的那张一行不少，清掉的那张一张表都不剩")
    void purgesOnlyMapsOutsideTheAllowlist() {
        KbMapRepository.PurgeCounts counts = repo.purgeMapsNotIn(List.of("Embervale"));

        assertThat(counts.maps()).containsExactly("EarlyAccess");
        assertThat(counts.total()).as("五张表各清掉一行，外加它自己的默认图层与自定义点位（这次没插）")
                .isEqualTo(5);

        for (String table : List.of("kb_map_page", "kb_map_canvas", "kb_map_group",
                "kb_map_marker", "kb_map_marker_note")) {
            assertThat(rowsOf(table, "EarlyAccess")).as(table + " 里不该再留旧地图").isZero();
            assertThat(rowsOf(table, "Embervale")).as(table + " 里白名单地图必须完好").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("★ 白名单为空 = 不过滤，而不是清空 —— 空列表一个字符都不能删")
    void emptyAllowlistDeletesNothing() {
        assertThat(repo.purgeMapsNotIn(List.of()).total()).isZero();
        assertThat(repo.purgeMapsNotIn(null).total()).isZero();
        assertThat(rows("kb_map_marker")).isEqualTo(2);
        assertThat(rows("kb_map_page")).isEqualTo(2);
    }

    @Test
    @DisplayName("重复跑是幂等的：第二次没有东西可清")
    void purgeIsIdempotent() {
        repo.purgeMapsNotIn(List.of("Embervale"));
        KbMapRepository.PurgeCounts again = repo.purgeMapsNotIn(List.of("Embervale"));

        assertThat(again.total()).isZero();
        assertThat(again.maps()).isEmpty();
    }
}
