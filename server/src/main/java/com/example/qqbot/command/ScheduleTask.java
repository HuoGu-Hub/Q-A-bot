package com.example.qqbot.command;

import com.example.qqbot.time.AppTime;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
        Instant sentAt) {

    public static final String REPEAT_ONCE = "once";
    public static final String REPEAT_DAILY = "daily";

    public boolean isGroup() {
        return groupId > 0;
    }

    public boolean isDaily() {
        return REPEAT_DAILY.equalsIgnoreCase(repeatRule);
    }

    /**
     * 给群里看的时刻：周期任务只说钟点，一次性的说日期。
     *
     * <p>周期任务不写「每天」而是由调用方补，所以这里只给时刻部分。
     */
    public String displayTime() {
        LocalDateTime at = LocalDateTime.ofInstant(fireAt, AppTime.ZONE);
        return at.format(isDaily()
                ? DateTimeFormatter.ofPattern("HH:mm")
                : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /** 列表里的一行：{@code #3 每天21:30 晚上打本集合} */
    public String displayLine() {
        String when = isDaily() ? "每天 " + displayTime() : displayTime();
        return "#" + id + "  " + when + "  " + shortContent();
    }

    /** 内容太长时截断，列表不该被一条长文案撑爆 */
    public String shortContent() {
        String oneLine = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
    }
}
