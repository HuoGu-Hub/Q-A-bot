package com.example.qqbot.prompt;

import com.example.qqbot.persistence.SystemAliasRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 命令别名 —— 命令名走 command，动作词走 action。
 *
 * <p>动作词只存一份：把 create 声明成“新建”之后，
 * /prompt /新建 和 /预约 /新建 都会展开成各自的 /create，不需要按模块重复配置。
 */
@Component
public class SystemAliasStore {

    public static final String KIND_COMMAND = "command";
    public static final String KIND_ACTION = "action";

    public static final String LIST = "list";
    public static final String HELP = "help";
    public static final String PROMPT = "prompt";

    private static final java.util.Set<String> ACTION_TARGETS =
            java.util.Set.of("create", "delete", "list", "help", "query", "clear");

    private final SystemAliasRepository repo;

    public SystemAliasStore(SystemAliasRepository repo) {
        this.repo = repo;
        if (repo.isAvailable()) {
            repo.initSchema();
        }
    }

    public record Alias(String kind, String target, String alias) {
    }

    public static class AliasException extends RuntimeException {
        public AliasException(String message) {
            super(message);
        }
    }

    public List<Alias> list() {
        if (!repo.isAvailable()) {
            return List.of();
        }
        return repo.list().stream()
                .map(row -> new Alias(row.kind(), row.target(), row.alias()))
                .toList();
    }

    public void replace(List<Alias> aliases) {
        if (!repo.isAvailable()) {
            throw new AliasException("别名暂时存不了");
        }
        Map<String, String> seen = new LinkedHashMap<>();
        List<SystemAliasRepository.Row> rows = new ArrayList<>();
        for (Alias item : aliases) {
            String kind = KIND_ACTION.equals(item.kind()) ? KIND_ACTION : KIND_COMMAND;
            String target = item.target() == null ? "" : item.target().trim();
            String alias = normalize(item.alias());
            if (target.isEmpty() || alias.isEmpty()) {
                continue;
            }
            if (!alias.matches("[\\p{IsHan}A-Za-z0-9_.-]+")) {
                throw new AliasException("别名「" + alias + "」只能含汉字、字母、数字、下划线、点、短横线");
            }
            String lower = alias.toLowerCase(Locale.ROOT);
            String key = kind + ":" + target;
            String old = seen.putIfAbsent(lower, key);
            if (old != null && !old.equals(key)) {
                throw new AliasException("别名「" + alias + "」同时指向了两个命令");
            }
            if (old != null) {
                continue;
            }
            rows.add(new SystemAliasRepository.Row(kind, target, lower));
        }
        repo.replaceAll(rows, Instant.now().toString());
    }

    /**
     * 解析一条消息：先换命令名，再换命令后面的第一个动作词。
     *
     * <p>只动第一个动作词，正文里的 /新建 不会被误改。
     */
    public String resolve(String body) {
        if (body == null || body.isBlank() || !repo.isAvailable()) {
            return body;
        }
        String text = body.trim();
        if (text.startsWith("@")) {
            int space = text.indexOf(' ');
            if (space > 0) {
                text = text.substring(space + 1).trim();
            }
        }
        if (!text.startsWith("/")) {
            return body;
        }

        Map<String, String> commandAliases = new LinkedHashMap<>();
        Map<String, String> actionAliases = new LinkedHashMap<>();
        for (Alias item : list()) {
            Map<String, String> targetMap = KIND_ACTION.equals(item.kind()) ? actionAliases : commandAliases;
            targetMap.put(item.alias(), item.target());
        }

        int firstEnd = text.indexOf(' ');
        if (firstEnd < 0) {
            firstEnd = text.length();
        }
        String first = text.substring(1, firstEnd);
        String rest = text.substring(firstEnd);
        String command = commandAliases.get(first.toLowerCase(Locale.ROOT));
        String prefix = command == null ? "/" + first : "/" + command.replace("/", " /");

        int i = 0;
        while (i < rest.length() && Character.isWhitespace(rest.charAt(i))) {
            i++;
        }
        if (i < rest.length() && rest.charAt(i) == '/') {
            int end = i + 1;
            while (end < rest.length() && !Character.isWhitespace(rest.charAt(end))) {
                end++;
            }
            String action = rest.substring(i + 1, end).toLowerCase(Locale.ROOT);
            String canonical = actionAliases.get(action);
            if (canonical == null) {
                // list / help 既是命令也是动作：命令名上配的别名同样能当动作词用
                String viaCommand = commandAliases.get(action);
                if (viaCommand != null && ACTION_TARGETS.contains(viaCommand)) {
                    canonical = viaCommand;
                }
            }
            if (canonical != null) {
                rest = rest.substring(0, i) + "/" + canonical + rest.substring(end);
            }
        }
        return prefix + rest;
    }

    private static String normalize(String raw) {
        String alias = raw == null ? "" : raw.trim();
        if (alias.startsWith("/")) {
            alias = alias.substring(1).trim();
        }
        return alias;
    }
}
