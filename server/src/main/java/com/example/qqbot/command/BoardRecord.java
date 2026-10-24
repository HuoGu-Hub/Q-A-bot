package com.example.qqbot.command;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 一条板块记录。定时任务也是它 —— 只是 {@code delivery=scheduled} 并且有 {@code fireAt}。
 *
 * <p>{@code text} 与 {@code params} 存的是<b>原始输入</b>：{@code {time}} 要取发送那一刻，
 * 不是写这条命令的那一刻，所以渲染留到真正发出去时做。
 */
public record BoardRecord(
        long id,
        String board,
        long groupId,
        long userId,
        String nickname,
        String text,
        Map<String, String> params,
        String delivery,
        Instant fireAt,
        String repeatRule,
        List<String> times,
        Instant anchorAt,
        String status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant sentAt,
        Instant deletedAt) {

    public BoardRecord {
        params = params == null ? Map.of() : Map.copyOf(params);
        times = times == null ? List.of() : List.copyOf(times);
    }

    public boolean isScheduled() {
        return Board.DELIVERY_SCHEDULED.equals(delivery);
    }

    /** 软删过没有 */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isGroup() {
        return groupId > 0;
    }

    /** 还原成"规则 + 时刻组"，交给它算下一次 */
    public RepeatSchedule schedule() {
        RepeatRule rule;
        try {
            rule = RepeatRule.parse(repeatRule);
        } catch (CommandParseException e) {
            rule = RepeatRule.once();
        }
        return new RepeatSchedule(rule, times, anchorAt);
    }

    /**
     * 「什么时候发」的人话。
     *
     * <p>周期任务给出规则 + <b>全部时刻</b>：一天三趟的公告只显示"每天 07:00"
     * 会让人以为另外两趟没设上。
     */
    public String whenText() {
        if (!isScheduled()) {
            return "立即展示";
        }
        RepeatSchedule s = schedule();
        if (s.rule().kind() == RepeatRule.Kind.ONCE) {
            return LocalDateTime.ofInstant(fireAt, com.example.qqbot.time.AppTime.ZONE)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        return s.rule().describe() + " " + s.describeTimes();
    }

    /**
     * 参数值的人话。
     *
     * <p>「重复」落库是 once / daily / weekly:3 这种机器形态，列表里要显示成
     * 只发一次 / 每天 / 每周三；其余参数原样。
     */
    public String paramText(String name) {
        return paramText(name, BoardRecordStore.PARAM_REPEAT);
    }

    /**
     * @param repeatParam 这条板块的重复周期参数名。按类型认出来之后可能已经改名，
     *                    不一定是「重复」，所以由调用方传进来
     */
    public String paramText(String name, String repeatParam) {
        String value = params.get(name);
        if (value == null) {
            return "";
        }
        return repeatParam.equals(name) ? RepeatRule.describeStored(value) : value;
    }

    /** 列表里的一行：{@code #12 每天 07:00、14:00 今晚八点开黑} */
    public String displayLine() {
        return displayLine(BoardRecordStore.PARAM_REPEAT);
    }

    public String displayLine(String repeatParam) {
        String body = text == null || text.isBlank() ? paramLine(repeatParam) : text;
        String oneLine = body.replaceAll("\\s+", " ").trim();
        String shortBody = oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
        return "#" + id + "  " + whenText() + "  " + shortBody;
    }

    /** 没有正文时用参数拼一行：参数值按人话显示，重复周期的存储形态不外泄 */
    private String paramLine(String repeatParam) {
        StringBuilder sb = new StringBuilder();
        for (String name : params.keySet()) {
            String value = paramText(name, repeatParam);
            if (value.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(value);
        }
        return sb.toString();
    }
}
