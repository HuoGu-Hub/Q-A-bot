package com.example.qqbot.persistence;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按日 token 用量（{@code llm_usage_day}）。
 *
 * <p>和 {@code llm_minute} 的区别：那张表回答"这一分钟快不快"，
 * 这张表回答"这个月花在谁身上"。所以维度是 日 × provider × model × 是否估算，
 * 主键也由这四列组成 —— 同一天同一模型重复落盘是<b>累加</b>，不是覆盖。
 *
 * <p>保留期比分钟表长得多：分钟表 7 天，这里 180 天。成本回顾是以月为单位的，
 * 7 天清掉等于没法回答"上个月用了多少"。
 */
@Component
public class LlmUsageRepository {

    /** 一行 = 某天某模型的用量；estimated 为 true 时 in/out 是本地估算值 */
    public record Row(String day, String provider, String model, boolean estimated,
                      long calls, long tokensIn, long tokensOut) {
    }

    private static final String UPSERT = "INSERT INTO llm_usage_day"
            + " (day, provider, model, estimated, calls, tokens_in, tokens_out)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT(day, provider, model, estimated) DO UPDATE SET"
            + " calls = calls + excluded.calls,"
            + " tokens_in = tokens_in + excluded.tokens_in,"
            + " tokens_out = tokens_out + excluded.tokens_out";

    private final Jdbc jdbc;

    public LlmUsageRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** 建表。公开是为了让测试能在临时库上自己装配，不依赖 Spring */
    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS llm_usage_day ("
                + " day        TEXT NOT NULL,"
                + " provider   TEXT NOT NULL,"
                + " model      TEXT NOT NULL,"
                + " estimated  INTEGER NOT NULL,"
                + " calls      INTEGER NOT NULL,"
                + " tokens_in  INTEGER NOT NULL,"
                + " tokens_out INTEGER NOT NULL,"
                + " PRIMARY KEY (day, provider, model, estimated)"
                + " )");
    }

    /**
     * 累加一批日账。
     *
     * <p>一条 SQL 里完成"有则加、无则插"：如果先查再写，
     * 两分钟的数据并发落盘会互相覆盖，而这里恰好是累加语义。
     */
    public void addAll(List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(rows.size());
        for (Row row : rows) {
            args.add(new Object[]{row.day(), row.provider(), row.model(), row.estimated() ? 1 : 0,
                    row.calls(), row.tokensIn(), row.tokensOut()});
        }
        jdbc.batch(UPSERT, args);
    }

    /** 区间内的行，按日期正序；起止都是 {@code YYYY-MM-DD}，闭区间 */
    public List<Row> between(String fromDay, String toDay) {
        return jdbc.query("SELECT day, provider, model, estimated, calls, tokens_in, tokens_out"
                        + " FROM llm_usage_day WHERE day >= ? AND day <= ?"
                        + " ORDER BY day, provider, model, estimated",
                r -> new Row(r.str("day"), r.str("provider"), r.str("model"), r.bool("estimated"),
                        r.longOf("calls"), r.longOf("tokens_in"), r.longOf("tokens_out")),
                fromDay, toDay);
    }

    public int prune(String beforeDay) {
        return jdbc.update("DELETE FROM llm_usage_day WHERE day < ?", beforeDay);
    }
}