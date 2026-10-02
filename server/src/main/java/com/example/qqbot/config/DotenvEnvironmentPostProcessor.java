package com.example.qqbot.config;

import com.example.qqbot.files.ProjectFiles;
import org.apache.commons.logging.Log;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 从<b>工程根目录</b>加载 {@code .env} —— 不依赖进程工作目录。
 *
 * <p>这是 {@code spring.config.import: optional:file:./.env} 之上的一道硬保险：
 * 那几行只能按工作目录往上试固定层数，试不到就<b>静默</b>放弃（optional 不报错），
 * 于是 ADMIN_PASSWORD 与各家 API Key 一起消失，而故障现象却是一句误导人的
 * 「请在 .env 里设置 ADMIN_PASSWORD」。
 *
 * <p><b>优先级</b>：系统环境变量 &gt; {@code .env} &gt; {@code application.yml} 默认值。
 * 与 {@code spring.config.import} 的语义一致 —— 线上用真环境变量时不会被文件盖掉。
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private final Log log;

    /**
     * 构造参数由 Spring Boot 注入。本类跑在「日志系统初始化」之前，
     * 所以必须用 {@link DeferredLogFactory} 拿 Log：它先把日志攒着，等
     * {@code ApplicationPreparedEvent} 时由 Boot 统一补印
     * （见 EnvironmentPostProcessorApplicationListener）。
     *
     * <p>踩过的坑：自己 {@code new} 一个普通 logger，这几行会<b>静默消失</b> ——
     * 于是"到底从哪个文件读的"又变成盲区，正是本类要消灭的东西。
     * 同理，{@code implements ApplicationListener} 对 EnvironmentPostProcessor
     * <b>没有用</b>：Boot 只把 spring.factories 里的 EPP 实例拿去 postProcess，
     * 不会把它注册成事件监听器。
     */
    public DotenvEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DotenvEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, org.springframework.boot.SpringApplication application) {
        Path file = ProjectFiles.dotenv();
        if (file == null) {
            log.warn("[CONFIG] 没找到 .env（" + ProjectFiles.describe()
                    + "）—— ADMIN_PASSWORD / API Key 只能来自系统环境变量了");
            return;
        }
        try {
            Properties props = load(file);
            if (props.isEmpty()) {
                log.warn("[CONFIG] " + file + " 里没有任何配置项");
                return;
            }
            PropertiesPropertySource source = new PropertiesPropertySource("dotenvFile", props);
            if (environment.getPropertySources()
                    .contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
                environment.getPropertySources()
                        .addBefore(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
            } else {
                environment.getPropertySources().addLast(source);
            }
            boolean hasAdminPwd = props.containsKey("ADMIN_PASSWORD")
                    && props.getProperty("ADMIN_PASSWORD") != null
                    && !props.getProperty("ADMIN_PASSWORD").isBlank();
            log.info("[CONFIG] 已从工程根加载 .env：" + file + "（" + props.size() + " 项，ADMIN_PASSWORD "
                    + (hasAdminPwd ? "✓ 已读到" : "✗ 未设置") + "）");
        } catch (Exception e) {
            log.warn("[CONFIG] 读 .env 失败（不影响启动）：" + file + " — " + e.getMessage());
        }
    }

    /**
     * 解析 .env。
     *
     * <p>比 {@code Properties} 原生解析多容忍一件事：行首的 {@code export }。
     * 很多人习惯把 .env 写成 shell 能 source 的样子，原生解析会把
     * {@code export ADMIN_PASSWORD=xxx} 的键解析成 {@code export ADMIN_PASSWORD}，
     * 于是「看起来设了其实没生效」。
     */
    private static Properties load(Path file) throws Exception {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        StringBuilder cleaned = new StringBuilder(text.length());
        for (String line : text.split("\\R", -1)) {
            String t = line.stripLeading();
            if (t.startsWith("export ")) {
                line = line.substring(0, line.length() - t.length()) + t.substring("export ".length());
            }
            cleaned.append(line).append('\n');
        }
        Properties props = new Properties();
        props.load(new StringReader(cleaned.toString()));
        return props;
    }

    @Override
    public int getOrder() {
        // 晚于 ConfigDataEnvironmentPostProcessor（加载 application.yml），
        // 早于属性绑定 —— 这样占位符一定能看到它。
        return Ordered.LOWEST_PRECEDENCE;
    }
}
