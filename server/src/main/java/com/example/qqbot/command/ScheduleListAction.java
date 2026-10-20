package com.example.qqbot.command;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code /list /output} —— 看本群还挂着哪些定时输出。
 *
 * <p>只列待发的，不列发过的。要查历史去后台，群聊里刷一屏"发过什么"没有意义。
 */
@Component
public class ScheduleListAction implements CommandAction {

    public static final String ID = "schedule.list";

    /** 一次最多列几条：群聊里超过十条就没人看了 */
    private static final int LIMIT = 10;

    private final ScheduleStore store;

    public ScheduleListAction(ScheduleStore store) {
        this.store = store;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "查看定时输出";
    }

    @Override
    public String defaultPath() {
        return "list/output";
    }

    @Override
    public String description() {
        return "列出本群还没发出去的那些";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of();
    }

    @Override
    public String execute(ActionContext ctx, CommandArgs args) {
        List<ScheduleTask> tasks = ctx.group()
                ? store.pendingForGroup(ctx.groupId(), LIMIT)
                : store.pendingForUser(ctx.userId(), LIMIT);
        if (tasks.isEmpty()) {
            return ctx.group() ? "本群还没有定时输出" : "你还没有定时输出";
        }
        StringBuilder sb = new StringBuilder(ctx.group() ? "本群待发：" : "你的待发：");
        for (ScheduleTask t : tasks) {
            sb.append('\n').append(t.displayLine());
        }
        return sb.toString();
    }
}
