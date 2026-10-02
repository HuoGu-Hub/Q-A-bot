package com.example.qqbot.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 文件访问守卫 —— 防止任何「读文件」的动作碰到不该碰的东西（比如 `.env`）。
 *
 * <p><b>使用契约（很重要）：</b>任何将来要读文件的代码（文件工具、知识库加载、
 * 上传文件解析……）都**必须先调用本类的 {@link #check(String)} 并确认 allowed 才继续**。
 * 这个类自己不会拦住任何东西 —— 它只是一份策略，需要调用方配合。
 *
 * <p>三层防线：
 * <ol>
 *   <li>尾缀黑名单：`.env` / `.key` / `.pem` / `.db` ……</li>
 *   <li>路径片段黑名单：`.git/`、`node_modules/`、`deploy/data/`、`.ssh/` ……</li>
 *   <li>目录白名单：只能访问 allowed-roots（留空 = 程序工作目录）以内的文件</li>
 * </ol>
 */
@Component
public class PathGuard {

    private static final Logger log = LoggerFactory.getLogger(PathGuard.class);

    private final FileAccess config;

    public PathGuard(FileAccess fileAccess) {
        this.config = fileAccess;
    }

    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "");
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    /** 检查一个路径能不能读 */
    public Decision check(String rawPath) {
        if (!config.isEnabled()) {
            return Decision.allow();
        }
        if (!StringUtils.hasText(rawPath)) {
            return Decision.deny("路径为空");
        }

        // 1) 目录穿越
        String normalized = rawPath.replace('\\', '/');
        if (normalized.contains("..")) {
            return Decision.deny("路径包含 .. （目录穿越）");
        }

        // 2) 尾缀黑名单
        String lower = normalized.toLowerCase();
        for (String ext : config.getBlockedExtensions()) {
            if (lower.endsWith(ext.toLowerCase())) {
                return Decision.deny("禁止读取 " + ext + " 类型的文件");
            }
        }

        // 3) 路径片段黑名单
        for (String part : config.getBlockedPathParts()) {
            if (lower.contains(part.toLowerCase())) {
                return Decision.deny("路径命中禁止片段 " + part);
            }
        }

        // 4) 必须落在允许的根目录内
        Path target = Paths.get(rawPath).toAbsolutePath().normalize();
        List<String> roots = config.getAllowedRoots();
        if (roots == null || roots.isEmpty()) {
            roots = List.of(System.getProperty("user.dir"));
        }
        for (String root : roots) {
            try {
                Path rootPath = Paths.get(root).toAbsolutePath().normalize();
                if (target.startsWith(rootPath)) {
                    return Decision.allow();
                }
            } catch (Exception e) {
                log.warn("[GUARD] 无效的 allowed-root：{}（{}）", root, e.getMessage());
            }
        }
        return Decision.deny("路径不在允许的目录范围内");
    }

    /** 需要「不通过就抛异常」的场景（例如工具实现里） */
    public void assertReadable(String rawPath) {
        Decision decision = check(rawPath);
        if (!decision.allowed()) {
            log.warn("[GUARD] 文件访问被拒绝：{} —— {}", rawPath, decision.reason());
            throw new SecurityException("文件访问被拒绝：" + decision.reason());
        }
    }
}
