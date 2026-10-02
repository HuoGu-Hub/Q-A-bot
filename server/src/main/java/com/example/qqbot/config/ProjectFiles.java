package com.example.qqbot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工程根目录定位，以及「散落在根目录旁边的配置文件」的绝对路径。
 *
 * <p><b>为什么需要它（踩过的坑）</b>：Spring Boot 的 {@code spring.config.import}
 * 只能写<b>相对路径</b>，而相对的是<b>进程工作目录</b>。
 * 一旦工作目录既不是仓库根、也不是 {@code server/}（IDEA 自定义 working directory、
 * 从 {@code target/} 里起 jar、脚本 cd 错地方……），{@code .env} 就<b>静默</b>读不到：
 *
 * <pre>
 *   表现：管理后台整个返回 503「请在 .env 里设置 ADMIN_PASSWORD」
 *   实际：密码早就设了，只是进程没找到 .env；同时所有 API Key 也一起丢了
 * </pre>
 *
 * <p><b>做法</b>：从工作目录逐级向上找<b>工程标记</b>（{@code AGENTS.md} 或
 * {@code server/pom.xml}），找到的那一级就是工程根。
 * 找不到（说明进程根本不在工程里）返回 {@code null}，调用方退回旧的相对路径行为。
 */
public final class ProjectFiles {

    private static final Logger log = LoggerFactory.getLogger(ProjectFiles.class);

    /** 工程根判据：这两个文件一定在仓库根目录 */
    private static final String[] ROOT_MARKERS = {"AGENTS.md", "server/pom.xml"};

    private static final String ENV_FILE = ".env";
    /** 配置中心写入的覆盖层（当前规范位置） */
    private static final String OVERRIDES_REL = "server/config/overrides.yml";
    /** 历史位置：从仓库根启动时曾写在 根/config/ 下 */
    private static final String OVERRIDES_LEGACY_REL = "config/overrides.yml";

    private static volatile boolean resolved = false;
    private static Path root;

    private ProjectFiles() {
    }

    /** 工程根目录；不在工程内则为 {@code null} */
    public static Path projectRoot() {
        if (!resolved) {
            synchronized (ProjectFiles.class) {
                if (!resolved) {
                    root = resolve();
                    resolved = true;
                }
            }
        }
        return root;
    }

    private static Path resolve() {
        Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path p = dir; p != null; p = p.getParent()) {
            for (String marker : ROOT_MARKERS) {
                if (Files.exists(p.resolve(marker))) {
                    log.info("[CONFIG] 工程根目录：{}（依据 {}）", p, marker);
                    return p;
                }
            }
        }
        log.warn("[CONFIG] 从 {} 向上没找到工程根（判据：{}）—— 退回相对路径查找配置",
                dir, String.join(" / ", ROOT_MARKERS));
        return null;
    }

    /** 工程根下的 {@code .env}；找不到返回 {@code null} */
    public static Path dotenv() {
        Path r = projectRoot();
        if (r == null) {
            return null;
        }
        Path f = r.resolve(ENV_FILE);
        return Files.isRegularFile(f) ? f : null;
    }

    /**
     * 配置覆盖层 {@code overrides.yml}。
     *
     * <p>优先 {@code <根>/server/config/overrides.yml}；只有当它不存在、而历史的
     * {@code <根>/config/overrides.yml} 存在时才用后者（避免升级后"设置全部丢失"）。
     */
    public static Path overrides() {
        Path r = projectRoot();
        if (r == null) {
            return null;
        }
        Path preferred = r.resolve(OVERRIDES_REL);
        Path legacy = r.resolve(OVERRIDES_LEGACY_REL);
        if (!Files.exists(preferred) && Files.exists(legacy)) {
            return legacy;
        }
        return preferred;
    }

    /**
     * 给「后台未启用」这类提示用的一句话诊断 ——
     * 让下次再出问题时，页面和日志直接说清楚它在哪儿找的、没找到什么。
     * <b>只含路径，不含任何密钥值。</b>
     */
    public static String describe() {
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path r = projectRoot();
        Path env = dotenv();
        return "当前工作目录=" + cwd
                + "；工程根=" + (r == null ? "未找到" : r.toString())
                + "；.env=" + (env == null ? "未找到" : env.toString());
    }
}
