package com.example.qqbot.config;

import java.time.ZoneId;

/**
 * 全局时间口径 —— 统计分桶与「今日」判定用的同一个时区。
 *
 * <h2>为什么需要它</h2>
 * 容器里没有设 TZ，也没在代码里设过默认时区，所以 {@code LocalDate.now()} 与
 * {@code substr(ts,1,10)} 取到的都是 **UTC 的日期**。而 bot 面向的是中文群 ——
 * UTC 的 00:00 是北京时间的 08:00，于是：
 * <ul>
 *   <li>看板「每日提问量」会把这天早上 8 点<b>之前</b>的提问算到<b>前一天</b>
 *       （实测：{@code 2026-09-27T20:30Z} 会被算成 09-27，而它其实是 09-28 凌晨 4:30）；</li>
 *   <li>预算的「今日」在北京时间早上 8 点重置，而不是零点。</li>
 * </ul>
 *
 * <h2>为什么是一个常量而不是两处各写一遍</h2>
 * 两处口径必须来自同一个值：只改 SQL 不改预算，就会出现「图上算今天、预算算昨天」
 * 这种同一屏自相矛盾的情况，而且极难发现。这里收成一处。
 *
 * <p>固定东八区：项目面向中文群，中国没有夏令时，所以不需要 tzdata 命名库
 * （用 {@code GMT+08:00} 表达，避免依赖系统 tzdata）。
 */
public final class AppTime {

    private AppTime() {
    }

    /** SQLite 的时区修饰符（{@code [+-]HH:MM}）。 */
    public static final String OFFSET = "+08:00";

    /** 与 {@link #OFFSET} 对应的时区。 */
    public static final ZoneId ZONE = ZoneId.of("GMT" + OFFSET);

    /**
     * SQLite 里把 UTC 的 ISO 时间戳换算成**本地日期**的表达式。
     *
     * <p>用法：{@code "SELECT " + SQL_DAY + " d, COUNT(*) FROM 某表"} ——
     * 表里那一列必须叫 {@code ts}。
     *
     * <p>实测 SQLite 能直接吃 {@code Instant.toString()} 产出的纳秒精度串
     * （{@code 2026-09-27T15:02:22.836835400Z}），不需要先截断。
     */
    public static final String SQL_DAY = "strftime('%Y-%m-%d', ts, '" + OFFSET + "')";
}
