package com.example.qqbot.settings;

import com.example.qqbot.config.ProjectFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置覆盖层文件（config/overrides.yml）的**唯一读写入口**。
 *
 * <p>为什么必须只有一份实现：这个文件是 YAML，**不能有重复的顶层键**。
 * 之前 SettingsService 和 ModelController 各写各的，各自重写时只知道自己那部分，
 * 结果生成了两个 app: 块 —— YAML 解析直接失败，服务起不来。
 *
 * <p>本类的做法：**读全量 → 合并 → 构建路径树 → 整份重写**，
 * 保证任何时刻文件里每个键只出现一次。
 */
@Component
public class OverridesFile {

    private static final Logger log = LoggerFactory.getLogger(OverridesFile.class);

    private static final String FILE = "./config/overrides.yml";

    private final Object lock = new Object();

    /**
     * 覆盖层文件的路径。
     *
     * <p>优先用 {@link ProjectFiles} 定位到的工程根下的
     * {@code server/config/overrides.yml} —— 否则「后台改的设置写到哪个文件」
     * 会随进程工作目录漂移，出现「改了设置、重启又变回去」。
     * 定位不到（进程不在工程内）才退回相对工作目录的旧行为。
     */
    public Path path() {
        Path resolved = ProjectFiles.overrides();
        if (resolved != null) {
            return resolved;
        }
        return Paths.get(FILE).toAbsolutePath().normalize();
    }

    /** 设置一项（幂等） */
    public void put(String key, Object value) {
        synchronized (lock) {
            Map<String, Object> all = readAll();
            all.put(key, value);
            writeAll(all);
        }
    }

    /** 批量设置 */
    public void putAll(Map<String, Object> items) {
        synchronized (lock) {
            Map<String, Object> all = readAll();
            all.putAll(items);
            writeAll(all);
        }
    }

    /** 读全部（打平为 a.b.c 形式） */
    public Map<String, Object> readAll() {
        synchronized (lock) {
            Map<String, Object> out = new LinkedHashMap<>();
            Path file = path();
            if (!Files.isRegularFile(file)) {
                return out;
            }
            try {
                List<String> stack = new ArrayList<>();
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.trim().startsWith("#")) {
                        continue;
                    }
                    int indent = (line.length() - line.stripLeading().length()) / 2;
                    String trimmed = line.trim();
                    int colon = trimmed.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    String k = trimmed.substring(0, colon).trim();
                    String v = trimmed.substring(colon + 1).trim();
                    while (stack.size() > indent) {
                        stack.remove(stack.size() - 1);
                    }
                    if (v.isEmpty()) {
                        stack.add(k);
                    } else {
                        List<String> full = new ArrayList<>(stack);
                        full.add(k);
                        out.put(String.join(".", full), parseScalar(v));
                    }
                }
            } catch (Exception e) {
                log.warn("[SETTINGS] 解析 overrides.yml 失败，将以空开始：{}", e.getMessage());
            }
            return out;
        }
    }

    /**
     * 整份重写。
     *
     * <p>关键：**先构建路径树，再按树输出** ——
     * 这样 app.guard.x 和 app.llm.y 会归到同一个 app: 下面，
     * 不会再出现重复顶层键。
     */
    private void writeAll(Map<String, Object> flat) {
        try {
            Path file = path();
            Files.createDirectories(file.getParent());

            Node root = new Node();
            for (Map.Entry<String, Object> e : flat.entrySet()) {
                String[] parts = e.getKey().split("\\.");
                Node cur = root;
                for (int i = 0; i < parts.length - 1; i++) {
                    cur = cur.children.computeIfAbsent(parts[i], k -> new Node());
                }
                cur.values.put(parts[parts.length - 1], e.getValue());
            }

            String nl = String.valueOf((char) 10);
            StringBuilder sb = new StringBuilder();
            sb.append("# ============================================================").append(nl);
            sb.append("#  配置覆盖层 —— 由管理后台写入，请勿手改").append(nl);
            sb.append("#").append(nl);
            sb.append("#  这里的值【优先级高于 application.yml】。").append(nl);
            sb.append("#  想回到默认值：直接删掉本文件然后重启。").append(nl);
            sb.append("# ============================================================").append(nl).append(nl);
            root.dump(sb, 0, nl);

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            log.info("[SETTINGS] 已写入 {}（{} 项）", file, flat.size());
        } catch (IOException e) {
            log.warn("[SETTINGS] 写入 overrides.yml 失败：{}", e.getMessage());
        }
    }

    /** 路径树节点 */
    private static class Node {
        final Map<String, Node> children = new LinkedHashMap<>();
        final Map<String, Object> values = new LinkedHashMap<>();

        void dump(StringBuilder sb, int depth, String nl) {
            String indent = "  ".repeat(depth);
            for (Map.Entry<String, Node> e : children.entrySet()) {
                sb.append(indent).append(e.getKey()).append((char) 58).append(nl);
                e.getValue().dump(sb, depth + 1, nl);
            }
            for (Map.Entry<String, Object> e : values.entrySet()) {
                sb.append(indent).append(e.getKey()).append(": ")
                  .append(scalar(e.getValue())).append(nl);
            }
        }
    }

    private static String scalar(Object v) {
        if (v == null) {
            return String.valueOf((char) 34) + (char) 34;
        }
        if (v instanceof Boolean || v instanceof Number) {
            return String.valueOf(v);
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append((char) 34).append(list.get(i)).append((char) 34);
            }
            return sb.append((char) 93).toString();
        }
        String s = String.valueOf(v);
        String lower = s.toLowerCase();
        boolean needQuote = s.isEmpty() || lower.equals("off") || lower.equals("on")
                || lower.equals("yes") || lower.equals("no") || lower.equals("true")
                || lower.equals("false") || lower.equals("null")
                || s.indexOf((char) 58) >= 0 || s.indexOf((char) 35) >= 0;
        return needQuote ? String.valueOf((char) 34) + s + (char) 34 : s;
    }

    private static Object parseScalar(String v) {
        if (v.equals("true") || v.equals("false")) {
            return Boolean.parseBoolean(v);
        }
        String s = v;
        if (s.length() >= 2) {
            char c0 = s.charAt(0);
            char c1 = s.charAt(s.length() - 1);
            if ((c0 == 34 || c0 == 39) && c1 == c0) {
                return s.substring(1, s.length() - 1);
            }
        }
        try {
            return s.contains(".") ? Double.parseDouble(s) : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return s;
        }
    }
}
