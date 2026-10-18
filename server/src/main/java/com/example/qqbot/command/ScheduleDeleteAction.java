package com.example.qqbot.command;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@code /del /output #id-3#} —— 撤销一条定时输出。
 *
 * <h2>为什么按编号撤，不按内容匹配</h2>
 * 两句「打本集合」分不出是哪条，按内容撤销会撤错。
 *
 * <h2>权限</h2>
 * 创建者本人，或者群主与群管。任务是群内资产：换个群查同一个编号查不到，
 * 所以跨群既看不到也撤不了。
 */
@Component
public class ScheduleDeleteAction implements CommandAction {

    public static final String ID = "schedule.delete";

    public static final ParamSpec ID_PARAM = new ParamSpec(
            "id", ParamType.INT, true, null, 0, "要撤销的编号", "3", List.of());

    private final ScheduleStore store;

    public ScheduleDeleteAction(ScheduleStore store) {
        this.store = store;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(ID_PARAM);
    }

    @Override
    public String execute(ActionContext ctx, CommandArgs args) throws ActionException {
        Long id = args.number("id");
        if (id == null) {
            throw new ActionException("要指定编号，例：/del /output #id-3#");
        }

        Optional<ScheduleTask> found = store.find(id);
        if (found.isEmpty()) {
            throw new ActionException("没有编号 #" + id + " 的定时输出");
        }
        ScheduleTask task = found.get();
        // 别的群的任务在这里查不到，也不该被得知存在
        if (task.groupId() != ctx.groupId()) {
            throw new ActionException("没有编号 #" + id + " 的定时输出");
        }
        if (task.userId() != ctx.userId() && !ctx.role().atLeast(CommandRole.ADMIN)) {
            throw new ActionException("#" + id + " 不是你设的，只有本人或管理员能撤销");
        }
        if (!store.cancel(id, "由 " + ctx.userId() + " 撤销")) {
            throw new ActionException("#" + id + " 撤销失败，可能已经发过或被撤了");
        }
        return "已撤销 #" + id + "：" + task.shortContent();
    }
}
