package com.example.qqbot.command;

import com.example.qqbot.time.AppTime;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * {@code /set /output #time-21:30# 内容} —— 记一条定时输出。
 *
 * <p>内容取自<b>自由文本</b>而不是某个参数：群里最自然的写法就是
 * 把要发的话直接打在参数后面，再要求它包进 {@code #text-...#} 只是多一道手续。
 *
 * <p>不带 {@code #date#} 就是每天同一时刻发；带了就只在那一天发一次。
 */
@Component
public class ScheduleCreateAction implements CommandAction {

    public static final String ID = "schedule.create";

    /** 每天几点发 */
    public static final ParamSpec TIME = new ParamSpec(
            "time", ParamType.MOMENT, true, null, 0, "每天几点发", "21:30", List.of());
    /** 指定某一天，只发一次 */
    public static final ParamSpec DATE = new ParamSpec(
            "date", ParamType.DATE, false, null, 0, "指定日期，只发一次", "2026-10-20", List.of());

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final ScheduleStore store;
    private final SchedulePolicy policy;

    public ScheduleCreateAction(ScheduleStore store, SchedulePolicy policy) {
        this.store = store;
        this.policy = policy;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(TIME, DATE);
    }

    @Override
    public String execute(ActionContext ctx, CommandArgs args) throws ActionException {
        if (!policy.isEnabled()) {
            throw new ActionException("定时输出还没开放");
        }
        if (!store.isAvailable()) {
            throw new ActionException("定时输出暂时不可用");
        }
        if (!ctx.group() && !policy.isAllowPrivate()) {
            throw new ActionException("私聊还不能用定时输出");
        }

        String content = ctx.text() == null ? "" : ctx.text().trim();
        if (content.isEmpty()) {
            throw new ActionException("还没写要发的内容");
        }
        if (content.length() > policy.getMaxContentChars()) {
            throw new ActionException("内容太长了，最多 " + policy.getMaxContentChars() + " 字");
        }

        Instant now = Instant.now();
        // 给了日期：取 #time# 的钟点配上那一天，只发一次
        LocalDate date = args.date("date");
        Instant fireAt = date == null ? args.moment("time")
                : date.atTime(timeOf(args.moment("time"))).atZone(AppTime.ZONE).toInstant();

        if (!fireAt.isAfter(now)) {
            throw new ActionException("那个时间已经过去了，换个时间");
        }
        long days = Duration.between(now, fireAt).toDays();
        if (days > policy.getMaxFutureDays()) {
            throw new ActionException("最多只能定到 " + policy.getMaxFutureDays() + " 天以内");
        }

        long pending = store.countPending(ctx.userId(), ctx.groupId());
        if (pending >= policy.getMaxPendingPerUser()) {
            throw new ActionException("你在这里已经挂了 " + pending + " 条，先用 /del /output #id-编号# 撤掉几条");
        }

        String repeat = date == null ? ScheduleStore.REPEAT_DAILY : ScheduleStore.REPEAT_ONCE;
        long id;
        try {
            id = store.create(ctx.groupId(), ctx.userId(), ctx.nickname(), content,
                    args.rawValues(), fireAt, repeat);
        } catch (IOException e) {
            throw new ActionException("没能记下来，稍后再试");
        }

        String when = LocalDateTime.ofInstant(fireAt, AppTime.ZONE)
                .format(date == null ? CLOCK : STAMP);
        String where = ctx.group() ? "在本群" : "私聊你";
        String every = date == null ? "每天 " + when : when + " 输出一次";
        return "已记下 #" + id + "：" + every + " " + where + "输出「" + content + "」";
    }

    private static LocalTime timeOf(Instant at) {
        return LocalDateTime.ofInstant(at, AppTime.ZONE).toLocalTime();
    }
}
