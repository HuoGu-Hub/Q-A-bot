package com.example.qqbot.distill;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 聊天时间解析 —— 采集与文件导入共用一套口径。
 *
 * <p>群聊导出文件的格式很杂，但进入库之后统一存 ISO8601 UTC 串：
 * 只有一种格式，分批、筛选、跨时区才不会各算各的。
 */
public final class ChatTime {

    /** 项目统一用东八区解释不带时区的时间 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final List<DateTimeFormatter> LOCAL_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

    private ChatTime() {
    }

    /**
     * 解析一个时间；解析不了返回 null。
     *
     * <p>接受 ISO8601、常见的本地时间格式、以及 epoch 秒/毫秒。
     */
    public static Instant parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        // JSON 导出里常见的是数字时间戳
        if (s.matches("\\d{10,13}")) {
            long n = Long.parseLong(s);
            return n > 10_000_000_000L ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // 继续试本地格式
        }
        // 带偏移但没有冒号的写法，如 +0800
        try {
            return java.time.OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        for (DateTimeFormatter f : LOCAL_FORMATS) {
            try {
                return LocalDateTime.parse(s, f).atZone(ZONE).toInstant();
            } catch (DateTimeParseException ignored) {
                // 试下一种
            }
        }
        return null;
    }

    /** 统一存这个格式；解析不了就用当前时间 */
    public static String toStore(String raw) {
        Instant instant = parse(raw);
        return (instant == null ? Instant.now() : instant).toString();
    }

    /** 本地日期，按天分批用 */
    public static String localDay(String iso) {
        Instant instant = parse(iso);
        return (instant == null ? Instant.now() : instant).atZone(ZONE).toLocalDate().toString();
    }
}
