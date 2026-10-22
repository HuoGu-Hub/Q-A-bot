package com.example.qqbot.prompt;

import com.example.qqbot.command.Board;
import com.example.qqbot.command.BoardStore;
import com.example.qqbot.command.BotCommand;
import com.example.qqbot.command.CommandStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命令别名的后台接口。
 *
 * <p>别名分两层：命令名声明一次，动作词声明一次。
 * create 只配一次“新建”，所有含 /create 的命令都会自动共用，不需要按模块重复。
 */
@RestController
@RequestMapping("/admin/api/system-aliases")
public class SystemAliasController {

    private static final Set<String> RESERVED =
            Set.of("list", "help", "prompt", "create", "delete", "query", "clear");

    private final SystemAliasStore store;
    private final CommandStore commands;
    private final BoardStore boards;

    public SystemAliasController(SystemAliasStore store, CommandStore commands, BoardStore boards) {
        this.store = store;
        this.commands = commands;
        this.boards = boards;
    }

    public record Item(String kind, String target, String label, List<String> aliases) {
    }

    public record Group(String label, List<Item> items) {
    }

    @GetMapping("")
    public Map<String, Object> list() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        for (SystemAliasStore.Alias item : store.list()) {
            aliases.computeIfAbsent(key(item.kind(), item.target()), k -> new ArrayList<>())
                    .add(item.alias());
        }
        return Map.of("groups", catalog(aliases));
    }

    @PostMapping("")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        List<SystemAliasStore.Alias> entries = new ArrayList<>();
        if (body.get("entries") instanceof List<?> list) {
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }
                String kind = String.valueOf(map.get("kind"));
                String target = String.valueOf(map.get("target"));
                if (map.get("aliases") instanceof List<?> values) {
                    for (Object value : values) {
                        entries.add(new SystemAliasStore.Alias(kind, target, String.valueOf(value)));
                    }
                }
            }
        }

        Map<String, String> allowed = new LinkedHashMap<>();
        for (Group group : catalog(Map.of())) {
            for (Item item : group.items()) {
                allowed.put(key(item.kind(), item.target()), item.label());
            }
        }
        List<SystemAliasStore.Alias> valid = entries.stream()
                .filter(e -> allowed.containsKey(key(e.kind(), e.target())))
                .toList();

        try {
            for (SystemAliasStore.Alias entry : valid) {
                String name = entry.alias() == null ? "" : entry.alias().trim().replaceFirst("^/", "");
                if (name.isEmpty()) {
                    continue;
                }
                if (RESERVED.contains(name.toLowerCase())) {
                    return ResponseEntity.badRequest().body(Map.of("error",
                            "别名「" + name + "」是系统保留名"));
                }
                if (commands.findByTrigger(name) != null || boards.find(name) != null) {
                    return ResponseEntity.badRequest().body(Map.of("error",
                            "别名「" + name + "」和已有命令或板块重名"));
                }
            }
            store.replace(valid);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (SystemAliasStore.AliasException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 命令名 + 动作词两份清单；动作只列一次 */
    private List<Group> catalog(Map<String, List<String>> aliases) {
        List<Item> commandItems = new ArrayList<>();
        commandItems.add(item(SystemAliasStore.KIND_COMMAND, "list", "列出全部命令", aliases));
        commandItems.add(item(SystemAliasStore.KIND_COMMAND, "help", "格式与说明", aliases));
        commandItems.add(item(SystemAliasStore.KIND_COMMAND, "prompt", "补充提示", aliases));
        for (Board board : boards.listAll()) {
            commandItems.add(item(SystemAliasStore.KIND_COMMAND, board.path(), "板块", aliases));
        }
        for (BotCommand command : commands.listAll()) {
            if ("prompt".equalsIgnoreCase(command.trigger())) {
                continue;
            }
            String label = command.description() == null || command.description().isBlank()
                    ? "/" + command.trigger() : command.description();
            commandItems.add(item(SystemAliasStore.KIND_COMMAND, command.trigger(), label, aliases));
        }

        List<Item> actionItems = new ArrayList<>();
        actionItems.add(item(SystemAliasStore.KIND_ACTION, Board.ACT_CREATE, "添加", aliases));
        actionItems.add(item(SystemAliasStore.KIND_ACTION, Board.ACT_DELETE, "删除", aliases));
        actionItems.add(item(SystemAliasStore.KIND_ACTION, Board.ACT_QUERY, "查看", aliases));
        actionItems.add(item(SystemAliasStore.KIND_ACTION, "clear", "清空补充提示", aliases));

        return List.of(new Group("命令", commandItems), new Group("动作", actionItems));
    }

    private static Item item(String kind, String target, String label,
                             Map<String, List<String>> aliases) {
        return new Item(kind, target, label, aliases.getOrDefault(key(kind, target), List.of()));
    }

    private static String key(String kind, String target) {
        return kind + ":" + target;
    }
}
