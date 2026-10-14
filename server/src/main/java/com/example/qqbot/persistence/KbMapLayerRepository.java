package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 地图**默认打开的图层**（{@code kb_map_layer_default}）。
 *
 * <h2>为什么这件事要落库</h2>
 * "打开地图时默认显示哪些分组"是**审美与用途**的判断，不是数据。
 * 余烬谷有 43 个分组、一千多个点位，全开就是密密麻麻一片，谁也看不出东西；
 * 而该开哪几个只有管理员说得准。写死在代码里，改一次默认值就得重新发一版。
 *
 * <h2>为什么存"白名单"而不是"黑名单"</h2>
 * 存的是**要打开**的分组。wiki 同步之后会冒出新分组：
 * 白名单让新分组默认关着，不打扰；黑名单则会让它们默认开着，又糊一次。
 * 配置表达的是"我挑过的这些才显示"，白名单才是这个意思。
 *
 * <h2>为什么一张地图"没有行"和"没有分组"必须区分</h2>
 * 表里查不到这张地图 = 管理员还没管过它，前端这时用内置经验值兜底；
 * 有行但一个分组都没有 = 他管过了，就是全关。
 * 两者混在一起的话，新地图一上来会是一片空白。
 */
@Repository
public class KbMapLayerRepository {

    private static final String COLUMNS = "map, group_key";

    private final Jdbc jdbc;

    public KbMapLayerRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_layer_default ("
                + " map       TEXT NOT NULL,"
                + " group_key TEXT NOT NULL,"
                + " PRIMARY KEY (map, group_key))");
    }

    /**
     * 全部地图的默认图层。
     *
     * <p>返回的 map 里**只有被配置过的地图** —— 缺键就是"没配置过"，
     * 调用方靠这一点区分"没管过"和"管过但全关"。
     */
    public Map<String, Set<String>> all() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        List<String[]> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_map_layer_default ORDER BY map, group_key",
                r -> new String[]{r.str("map"), r.str("group_key")});
        for (String[] row : rows) {
            out.computeIfAbsent(row[0], k -> new LinkedHashSet<>()).add(row[1]);
        }
        return out;
    }

    /**
     * 覆盖一张地图的默认图层。
     *
     * <p>先删后插、放在一个事务里：中途失败的话，读到的会是"这张图没配置过"，
     * 前端退回经验值 —— 比留下半套配置好。
     */
    public void replace(String map, Collection<String> keys) {
        jdbc.transaction(() -> {
            jdbc.update("DELETE FROM kb_map_layer_default WHERE map = ?", map);
            List<Object[]> rows = new ArrayList<>();
            for (String k : keys) {
                if (k != null && !k.isBlank()) {
                    rows.add(new Object[]{map, k});
                }
            }
            if (!rows.isEmpty()) {
                jdbc.batch("INSERT OR REPLACE INTO kb_map_layer_default (" + COLUMNS + ") VALUES (?,?)", rows);
            }
            return null;
        });
    }

    /** 抹掉一张地图的配置 —— 让它回到"未配置"，前端重新用经验值 */
    public void clear(String map) {
        jdbc.update("DELETE FROM kb_map_layer_default WHERE map = ?", map);
    }
}
