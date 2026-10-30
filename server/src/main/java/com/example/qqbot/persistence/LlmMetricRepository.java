package com.example.qqbot.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每分钟一行的模型调用读数（{@code llm_minute}）。
 *
 * <p>和 {@link TrafficRepository} 同一套做法：主键是 epoch 分钟，
 * 重复写是覆盖不是累加，保留 7 天。
 */
@Component
public class LlmMetricRepository {

    /** 一行 = 一分钟的模型调用 */
    public record Row(long minute, long calls, long failures, long tokensIn, long tokensOut,
                      int p50, int p95, int max) {
    }

    private final Jdbc jdbc;

    public LlmMetricRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** 建表。公开是为了让测试能在临时库上自己装配，不依赖 Spring */
    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS llm_minute ("
                + " minute     INTEGER PRIMARY KEY,"
                + " calls      INTEGER NOT NULL,"
                + " failures   INTEGER NOT NULL,"
                + " tokens_in  INTEGER NOT NULL,"
                + " tokens_out INTEGER NOT NULL,"
                + " p50        INTEGER NOT NULL,"
                + " p95        INTEGER NOT NULL,"
                + " max        INTEGER NOT NULL"
                + " )");
    }

    public void save(Row row) {
        jdbc.update("INSERT INTO llm_minute (minute, calls, failures, tokens_in, tokens_out, p50, p95, max)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(minute) DO UPDATE SET"
                        + " calls = excluded.calls, failures = excluded.failures,"
                        + " tokens_in = excluded.tokens_in, tokens_out = excluded.tokens_out,"
                        + " p50 = excluded.p50, p95 = excluded.p95, max = excluded.max",
                row.minute(), row.calls(), row.failures(), row.tokensIn(), row.tokensOut(),
                row.p50(), row.p95(), row.max());
    }

    /** 最近 {@code minutes} 分钟的行，按时间正序 */
    public List<Row> recent(int minutes) {
        long since = System.currentTimeMillis() / 60_000L - Math.max(1, minutes);
        return jdbc.query("SELECT minute, calls, failures, tokens_in, tokens_out, p50, p95, max"
                        + " FROM llm_minute WHERE minute > ? ORDER BY minute",
                r -> new Row(r.longOf("minute"), r.longOf("calls"), r.longOf("failures"),
                        r.longOf("tokens_in"), r.longOf("tokens_out"),
                        r.intOf("p50"), r.intOf("p95"), r.intOf("max")),
                since);
    }

    public int prune(long beforeMinute) {
        return jdbc.update("DELETE FROM llm_minute WHERE minute < ?", beforeMinute);
    }
}
