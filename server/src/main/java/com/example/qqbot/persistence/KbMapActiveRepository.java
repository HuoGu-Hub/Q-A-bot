package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "当前启用哪几张地图"（{@code kb_map_active}）的**数据访问**。
 *
 * <h2>为什么这件事不该写在配置文件里</h2>
 * "哪张地图是当前世界"是一个**会变**的判断题：游戏更新之后 wiki 会另起一页
 * （名字通常也不一样），旧页要么被改写、要么变成历史版本。
 * 把它固化进 {@code application.yml} 的白名单，等于把一个会过期的判断
 * 写进一份基本不变的配置 —— 过期时不会有人告诉你：
 * 新地图被静默跳过，旧地图以"最新状态"的样子一直留着。
 *
 * <p>所以它落在库里：<b>全拉进来，谁都不丢</b>，只是没启用的不出现在
 * 目录、地点问答和地点语料里（见 {@code kb.map.MapScope}）。
 * 新地图一出现就会出现在后台的候选列表里等人决定。
 *
 * <h2>为什么是"多张"而不是"一张"</h2>
 * 对外目前只显示一张（目录按点位数排序，取第一张）。但留成一组开关，
 * 以后想让区域小图也能开放时不用改表结构。
 */
@Repository
public class KbMapActiveRepository {

    private final Jdbc jdbc;

    public KbMapActiveRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_map_active ("
                + " map       TEXT PRIMARY KEY,"
                + " active    INTEGER NOT NULL DEFAULT 0,"
                + " chosen_at TEXT NOT NULL DEFAULT '')");
    }

    /** 每张地图的开关状态。**表里没有的行 = 没做过选择**，由调用方决定怎么兜底 */
    public Map<String, Boolean> all() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : jdbc.query(
                "SELECT map, active FROM kb_map_active ORDER BY map",
                r -> Map.entry(r.str("map"), r.intOf("active")))) {
            out.put(e.getKey(), e.getValue() != 0);
        }
        return out;
    }

    /** 当前启用的地图名 */
    public List<String> active() {
        return jdbc.query("SELECT map FROM kb_map_active WHERE active = 1 ORDER BY map",
                r -> r.str("map"));
    }

    /** 表里有没有任何一行 —— 用来区分"还没人做过选择"和"有人把开关全关了" */
    public long count() {
        return jdbc.count("SELECT COUNT(*) FROM kb_map_active");
    }

    public boolean set(String map, boolean active, String chosenAt) {
        return jdbc.update("INSERT INTO kb_map_active (map, active, chosen_at) VALUES (?,?,?)"
                + " ON CONFLICT(map) DO UPDATE SET active = excluded.active,"
                + " chosen_at = excluded.chosen_at",
                nz(map), active ? 1 : 0, nz(chosenAt)) > 0;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
