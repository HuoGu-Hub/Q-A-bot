package com.example.qqbot.command;

import com.example.qqbot.time.AppTime;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /set /output #重复-每天# #时间-21:30# 内容} —— 记一条定时输出。
 *
 * <p>内容取自<b>自由文本</b>而不是某个参数：群里最自然的写法就是
 * 把要发的话直接打在参数后面，再要求它包进 {@code #text-...#} 只是多一道手续。
 *
 * <p>{@code #时间#} 可以一次给多个（顿号隔开），于是"一天发三次"不用写三条命令。
 * 它们共享<b>同一个编号</b>：撤销一次，几个时刻一起停。
 */
@Component
public class ScheduleCreateAction implements CommandAction {

    public static final String ID = "schedule.create";

    /** 多久来一次 */
    public static final ParamSpec REPEAT = ParamSpec.of("重复", "text", true, "每天");
    /** 几点发；可以一次给多个 */
    public static final ParamSpec TIME = ParamSpec.multi(
            "时间", ParamType.MOMENT, true, null, 0, "几点发，可以写多个，用顿号隔开", "21:30");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
        return List.of(REPEAT, TIME);
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
        RepeatRule rule;
        try {
            rule = RepeatRule.parse(args.text(REPEAT.name()));
        } catch (CommandParseException e) {
            // 参数解析层用的异常类型，动作层用的是 ActionException —— 在这里换一次
            throw new ActionException(e.getMessage());
        }

        // 时刻的存法取决于规则：一次性存绝对时刻，周期只存钟点
        List<String> times = new ArrayList<>();
        for (Instant moment : args.moments(TIME.name())) {
            // 周期任务只关心钟点，掐到分钟：群里写的 21:30 就是 21:30，
            // 带上毫秒会让回执变成 21:30:00.123 这种没人想看的东西
            times.add(rule.kind() == RepeatRule.Kind.ONCE
                    ? moment.toString()
                    : LocalDateTime.ofInstant(moment, AppTime.ZONE).toLocalTime()
                            .truncatedTo(ChronoUnit.MINUTES).toString());
        }
        RepeatSchedule schedule = new RepeatSchedule(rule, times, now);
        Instant fireAt = schedule.nextAfter(now);
        if (fireAt == null) {
            // 周期任务会自己往后滚，只有一次性会出现"全都过去了"
            throw new ActionException("那个时间已经过去了，换个时间");
        }
        if (rule.kind() == RepeatRule.Kind.ONCE
                && Duration.between(now, fireAt).toDays() > policy.getMaxFutureDays()) {
            throw new ActionException("最多只能定到 " + policy.getMaxFutureDays() + " 天以内");
        }

        long pending = store.countPending(ctx.userId(), ctx.groupId());
        if (pending >= policy.getMaxPendingPerUser()) {
            throw new ActionException("你在这里已经挂了 " + pending + " 条，先用 /del /output #id-编号# 撤掉几条");
        }

        long id;
        try {
            id = store.create(ctx.groupId(), ctx.userId(), ctx.nickname(), content,
                    args.rawValues(), fireAt, rule.toStorage(), times, now);
        } catch (IOException e) {
            throw new ActionException("没能记下来，稍后再试");
        }

        String when = rule.kind() == RepeatRule.Kind.ONCE
                ? STAMP.format(LocalDateTime.ofInstant(times.isEmpty() ? fireAt : Instant.parse(times.get(0)),
                        AppTime.ZONE))
                : String.join("、", times);
        String where = ctx.group() ? "在本群" : "私聊你";
        return "已记下 #" + id + "：" + rule.describe() + " " + when + " " + where + "输出「" + content + "」";
    }
}
