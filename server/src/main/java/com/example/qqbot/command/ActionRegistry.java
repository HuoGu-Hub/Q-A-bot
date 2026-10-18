package com.example.qqbot.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动作注册表 —— 把容器里所有 {@link CommandAction} 收成一张按 id 查的表。
 *
 * <p>构造时一次性建表，运行期只读。重复 id 保留先出现的那个并告警：
 * 让它静默覆盖才是最难查的 —— 线上跑的是哪一个类完全看不出来。
 */
@Component
public class ActionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActionRegistry.class);

    private final Map<String, CommandAction> actions = new LinkedHashMap<>();

    public ActionRegistry(List<CommandAction> beans) {
        for (CommandAction action : beans == null ? List.<CommandAction>of() : beans) {
            String id = action.id() == null ? "" : action.id().trim();
            if (id.isEmpty()) {
                log.warn("[CMD] 忽略一个没有 id 的动作：{}", action.getClass().getSimpleName());
                continue;
            }
            CommandAction exists = actions.putIfAbsent(id, action);
            if (exists != null) {
                log.warn("[CMD] 动作 id 重复：{} 同时来自 {} 与 {}，保留前者",
                        id, exists.getClass().getSimpleName(), action.getClass().getSimpleName());
            }
        }
        log.info("[CMD] 已注册 {} 个动作：{}", actions.size(), actions.keySet());
    }

    /**
     * 按 id 找动作。
     *
     * <p>找不到返回 null 而不是抛异常：库里可能留着一条指向已删除动作的命令，
     * 那种情况应该在<b>匹配阶段</b>当作停用并记日志，而不是在线上抛 ClassNotFound。
     */
    public CommandAction find(String id) {
        return id == null ? null : actions.get(id.trim());
    }

    public List<CommandAction> all() {
        return new ArrayList<>(actions.values());
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }
}
