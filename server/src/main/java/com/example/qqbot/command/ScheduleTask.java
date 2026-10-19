package com.example.qqbot.command;

import com.example.qqbot.time.AppTime;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 一行定时输出。
 *
 * <p>{@code content} 是<b>模板原文</b>，不是渲染结果：{@code {time}} 要取发送那一刻，
 * 而不是写这条命令的那一刻。{@code paramsJson} 存着当次收到的参数，
 * 于是 {@code {arg.名称}} 在发送时也取得到值。
 *
 * @param nickname 创建时的昵称。发送时没有入站事件，{@code {user}} 只能靠它
 */
public record ScheduleTask(
        long id,
        long groupId,
        long userId,
        String nickname,
        String content,
        String paramsJson,
        Instant fireAt,
        String repeatRule,
        String status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant sentAt,
        List<String> times,
        Instant anchorAt) {

    public static final String REPEAT_ONCE = "once";
    public static final String REPEAT_DAILY = "daily";

    public ScheduleTask {
        times = times == null ? List.of() : List.copyOf(times);
    }

    /**
     * 还原成"规则 + 时刻组"，交给它算下一次。
     *
     * <p>库里那一列读不懂时退回"一次"而不是抛异常：一条脏记录不该让整轮扫描停下。
     */
    public RepeatSchedule schedule() {
        RepeatRule rule;
        try {
            rule = RepeatRule.parse(repeatRule);
        } catch (CommandParseException e) {
            rule = RepeatRule.once();
        }
        return new RepeatSchedule(rule, times, anchorAt);
    }

    public boolean isGroup() {
        return groupId > 0;
    }

    public boolean isDaily() {
        return REPEAT_DAILY.equalsIgnoreCase(repeatRule);
    }

    /** 还会再发吗（不是一次性） */
    public boolean isRepeating() {
        return schedule().rule().kind() != RepeatRule.Kind.ONCE;
    }

    /** 列表里那句"多久一次" */
    public String repeatLabel() {
        return schedule().rule().describe();
    }

    /**
     * 「什么时候发」的人话。
     *
     * <p>周期任务给出规则 + <b>全部时刻</b>：一天三趟的公告只显示"每天 07:00"
     * 会让人以为另外两趟没设上。一次性任务给出具体日期。
     */
    public String whenText() {
        RepeatSchedule s = schedule();
        if (s.rule().kind() == RepeatRule.Kind.ONCE) {
            return LocalDateTime.ofInstant(fireAt, AppTime.ZONE)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        return s.rule().describe() + " " + s.describeTimes();
    }

    /** 给群里看的时刻：周期任务只说钟点，一次性的说日期 */
    public String displayTime() {
        return LocalDateTime.ofInstant(fireAt, AppTime.ZONE).format(isDaily()
                ? DateTimeFormatter.ofPattern("HH:mm")
                : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /** 列表里的一行：{@code #3 每天 07:00、14:00 晚上打本集合} */
    public String displayLine() {
        return "#" + id + "  " + whenText() + "  " + shortContent();
    }

    /** 内容太长时截断，列表不该被一条长文案撑爆 */
    public String shortContent() {
        String oneLine = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
    }
}
