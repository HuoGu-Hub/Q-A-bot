package com.example.qqbot.prompt;

import com.example.qqbot.command.BoardStore;
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

/** 系统命令中文别名的后台接口 */
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

    @GetMapping("")
    public Map<String, Object> list() {
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        for (String target : List.of(SystemAliasStore.LIST, SystemAliasStore.HELP, SystemAliasStore.PROMPT)) {
            byTarget.put(target, new ArrayList<>());
        }
        for (SystemAliasStore.Alias item : store.list()) {
            byTarget.computeIfAbsent(item.target(), k -> new ArrayList<>()).add(item.alias());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var entry : byTarget.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("target", entry.getKey());
            item.put("label", label(entry.getKey()));
            item.put("aliases", entry.getValue());
            out.add(item);
        }
        return Map.of("commands", out);
    }

    @PostMapping("")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        Object raw = body.get("commands");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String target = String.valueOf(map.get("target"));
                if (!List.of(SystemAliasStore.LIST, SystemAliasStore.HELP,
                        SystemAliasStore.PROMPT).contains(target)) {
                    continue;
                }
                List<String> aliases = new ArrayList<>();
                if (map.get("aliases") instanceof List<?> values) {
                    for (Object value : values) {
                        aliases.add(String.valueOf(value));
                    }
                }
                byTarget.put(target, aliases);
            }
        }
        try {
            for (List<String> aliases : byTarget.values()) {
                for (String alias : aliases) {
                    String name = alias == null ? "" : alias.trim().replaceFirst("^/", "");
                    if (name.isEmpty()) {
                        continue;
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

    private static String label(String target) {
        return switch (target) {
            case SystemAliasStore.LIST -> "列出全部命令";
            case SystemAliasStore.HELP -> "格式与说明";
            case SystemAliasStore.PROMPT -> "补充提示";
            default -> target;
        };
    }
}
