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

/**
 * 命令别名的后台接口。
 *
 * <p>别名不是系统命令专属：板块、板块动作、普通命令、/prompt 的子动作
 * 都有自己的规范路径，后台给任意一个声明中文调用名即可。
 */
@RestController
@RequestMapping("/admin/api/system-aliases")
public class SystemAliasController {

    private final SystemAliasStore store;
    private final CommandStore commands;
    private final BoardStore boards;

    public SystemAliasController(SystemAliasStore store, CommandStore commands, BoardStore boards) {
        this.store = store;
        this.commands = commands;
        this.boards = boards;
    }

    public record Item(String target, String label, List<String> aliases) {
    }

    public record Group(String label, List<Item> items) {
    }

    @GetMapping("")
    public Map<String, Object> list() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        for (SystemAliasStore.Alias item : store.list()) {
            aliases.computeIfAbsent(item.target(), k -> new ArrayList<>()).add(item.alias());
        }
        return Map.of("groups", catalog(aliases));
    }

    @PostMapping("")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        List<Item> entries = new ArrayList<>();
        if (body.get("entries") instanceof List<?> list) {
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }
                String target = String.valueOf(map.get("target"));
                List<String> aliases = new ArrayList<>();
                if (map.get("aliases") instanceof List<?> values) {
                    for (Object value : values) {
                        aliases.add(String.valueOf(value));
                    }
                }
                entries.add(new Item(target, "", aliases));
            }
        }

        Map<String, String> allowed = new LinkedHashMap<>();
        for (Group group : catalog(Map.of())) {
            for (Item item : group.items()) {
                allowed.put(item.target(), item.label());
            }
        }
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        for (Item entry : entries) {
            if (!allowed.containsKey(entry.target())) {
                continue;
            }
            byTarget.put(entry.target(), entry.aliases());
        }

        try {
            for (List<String> aliases : byTarget.values()) {
                for (String alias : aliases) {
                    String name = alias == null ? "" : alias.trim().replaceFirst("^/", "");
                    if (name.isEmpty()) {
                        continue;
                    }
                    if (name.equalsIgnoreCase("list") || name.equalsIgnoreCase("help")
                            || name.equalsIgnoreCase("prompt") || name.equalsIgnoreCase("create")
                            || name.equalsIgnoreCase("delete")) {
                        return ResponseEntity.badRequest().body(Map.of("error",
                                "别名「" + name + "」是系统保留名"));
                    }
                    if (commands.findByTrigger(name) != null || boards.find(name) != null) {
                        return ResponseEntity.badRequest().body(Map.of("error",
                                "别名「" + name + "」和已有命令或板块重名"));
                    }
                }
            }
            store.replace(byTarget);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (SystemAliasStore.AliasException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 规范命令目录：系统、板块、普通命令。板块动作也单独列出，create/delete 就在其中 */
    private List<Group> catalog(Map<String, List<String>> aliases) {
        List<Item> system = new ArrayList<>();
        system.add(item("list", "列出全部命令", aliases));
        system.add(item("help", "格式与说明", aliases));
        system.add(item("prompt", "补充提示", aliases));
        system.add(item("prompt/create", "添加补充提示", aliases));
        system.add(item("prompt/delete", "删除补充提示", aliases));
        system.add(item("prompt/list", "查看补充提示", aliases));
        system.add(item("prompt/clear", "清空补充提示", aliases));

        List<Item> boardItems = new ArrayList<>();
        for (Board board : boards.listAll()) {
            boardItems.add(item(board.path(), "查看", aliases));
            for (String action : List.of(Board.ACT_CREATE, Board.ACT_DELETE,
                    Board.ACT_LIST, Board.ACT_HELP)) {
                boardItems.add(item(board.path() + "/" + action, actionLabel(action), aliases));
            }
        }

        List<Item> commandItems = new ArrayList<>();
        for (BotCommand command : commands.listAll()) {
            if ("prompt".equalsIgnoreCase(command.trigger())) {
                continue;
            }
            String label = command.description() == null || command.description().isBlank()
                    ? "/" + command.trigger() : command.description();
            commandItems.add(item(command.trigger(), label, aliases));
        }

        return List.of(
                new Group("系统", system),
                new Group("板块与动作", boardItems),
                new Group("普通命令", commandItems));
    }

    private static Item item(String target, String label, Map<String, List<String>> aliases) {
        return new Item(target, label, aliases.getOrDefault(target, List.of()));
    }

    private static String actionLabel(String action) {
        return switch (action) {
            case Board.ACT_CREATE -> "添加";
            case Board.ACT_DELETE -> "撤销";
            case Board.ACT_LIST -> "参数";
            case Board.ACT_HELP -> "用法";
            default -> action;
        };
    }
}
