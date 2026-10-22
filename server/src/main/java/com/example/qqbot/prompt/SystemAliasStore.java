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
 * 系统命令别名 —— 把 {@code /列表} 映射回 {@code /list}。
 *
 * <p>只认 list / help / prompt。别名在命令分派之前解析，
 * 所以 /列表、/帮助、/提示 和原命令走完全相同的权限、开关与处理逻辑。
 */
@Component
public class SystemAliasStore {

    public static final String LIST = "list";
    public static final String HELP = "help";
    public static final String PROMPT = "prompt";

    private final SystemAliasRepository repo;

    public SystemAliasStore(SystemAliasRepository repo) {
        this.repo = repo;
        if (repo.isAvailable()) {
            repo.initSchema();
        }
    }

    public record Alias(String target, String alias) {
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
                .map(row -> new Alias(row.target(), row.alias()))
                .toList();
    }

    public void replace(Map<String, List<String>> byTarget) {
        if (!repo.isAvailable()) {
            throw new AliasException("别名暂时存不了");
        }
        Map<String, String> seen = new LinkedHashMap<>();
        List<SystemAliasRepository.Row> rows = new ArrayList<>();
        for (var entry : byTarget.entrySet()) {
            String target = entry.getKey();
            if (target == null || target.isBlank()) {
                continue;
            }
            for (String raw : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
                String alias = normalize(raw);
                if (alias.isEmpty()) {
                    continue;
                }
                if (!alias.matches("[\\p{IsHan}A-Za-z0-9_.-]+")) {
                    throw new AliasException("别名「" + alias + "」只能含汉字、字母、数字、下划线、点、短横线");
                }
                String lower = alias.toLowerCase(Locale.ROOT);
                String old = seen.putIfAbsent(lower, target);
                if (old != null && !old.equals(target)) {
                    throw new AliasException("别名「" + alias + "」同时指向了两个命令");
                }
                if (old != null) {
                    continue;
                }
                rows.add(new SystemAliasRepository.Row(lower, target));
            }
        }
        repo.replaceAll(rows, Instant.now().toString());
    }

    /** 把消息开头的中文别名换成系统命令；不是别名就原样返回 */
    public String resolve(String body) {
        if (body == null || body.isBlank() || !repo.isAvailable()) {
            return body;
        }
        String trimmed = body.trim();
        // 允许前置 @机器人
        if (trimmed.startsWith("@")) {
            int space = trimmed.indexOf(' ');
            if (space > 0) {
                trimmed = trimmed.substring(space + 1).trim();
            }
        }
        if (!trimmed.startsWith("/")) {
            return body;
        }
        String[] parts = trimmed.substring(1).split("\\s+", 2);
        String alias = parts[0].toLowerCase(Locale.ROOT);
        for (Alias item : list()) {
            if (item.alias().equals(alias)) {
                String path = item.target().replace("/", " /");
                return "/" + path + (parts.length > 1 ? " " + parts[1] : "");
            }
        }
        return body;
    }

    private static String normalize(String raw) {
        String alias = raw == null ? "" : raw.trim();
        if (alias.startsWith("/")) {
            alias = alias.substring(1).trim();
        }
        return alias;
    }
}
