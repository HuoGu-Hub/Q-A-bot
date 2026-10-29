package com.example.qqbot.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每分钟一行的流量读数（{@code traffic_minute}）。
 *
 * <p>为什么落库而不是只留内存：重启会清零，而"上周三晚上为什么慢"这种问题
 * 恰恰只能在历史里回答。每分钟一行，7 天一万行，SQLite 毫无压力。
 *
 * <p>主键是 {@code minute}（epoch 分钟），所以同一分钟重复写入是覆盖而不是累加 ——
 * 调度器因为任何原因多跑一次都不会把 QPS 翻倍。
 */
@Component
public class TrafficRepository {

    /** 一行 = 一分钟 */
    public record Row(long minute, long requests, long api, long errors, int p50, int p95, int max) {
    }

    private final Jdbc jdbc;

    public TrafficRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** 建表。公开是为了让测试能在临时库上自己装配，不依赖 Spring */
    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_minute ("
                + " minute   INTEGER PRIMARY KEY,"
                + " requests INTEGER NOT NULL,"
                + " api      INTEGER NOT NULL,"
                + " errors   INTEGER NOT NULL,"
                + " p50      INTEGER NOT NULL,"
                + " p95      INTEGER NOT NULL,"
                + " max      INTEGER NOT NULL"
                + " )");
    }

    public void save(Row row) {
        jdbc.update("INSERT INTO traffic_minute (minute, requests, api, errors, p50, p95, max)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(minute) DO UPDATE SET"
                        + " requests = excluded.requests, api = excluded.api, errors = excluded.errors,"
                        + " p50 = excluded.p50, p95 = excluded.p95, max = excluded.max",
                row.minute(), row.requests(), row.api(), row.errors(),
                row.p50(), row.p95(), row.max());
    }

    /** 最近 {@code minutes} 分钟的行，按时间正序。没有请求的分钟不会出现在结果里 */
    public List<Row> recent(int minutes) {
        long since = System.currentTimeMillis() / 60_000L - Math.max(1, minutes);
        return jdbc.query("SELECT minute, requests, api, errors, p50, p95, max FROM traffic_minute"
                        + " WHERE minute > ? ORDER BY minute",
                r -> new Row(r.longOf("minute"), r.longOf("requests"), r.longOf("api"),
                        r.longOf("errors"), r.intOf("p50"), r.intOf("p95"), r.intOf("max")),
                since);
    }

    /** 删掉早于 {@code beforeMinute} 的行 */
    public int prune(long beforeMinute) {
        return jdbc.update("DELETE FROM traffic_minute WHERE minute < ?", beforeMinute);
    }
}
