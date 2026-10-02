package com.example.qqbot.logs;

import com.example.qqbot.config.LogProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把 Spring 容器里的 {@link LogBuffer} 接到 logback 的 {@link MemoryLogAppender} 上。
 *
 * <p><b>为什么要这个桥</b>：logback 在 Spring 容器**之前**初始化，
 * 那时候还拿不到任何 bean。而 appender 需要往 buffer 里写 ——
 * 所以用静态字段做中转，等容器起来后再注入。
 *
 * <p>这期间（容器启动的头一两秒）的日志只会进控制台和文件，不进内存缓冲 ——
 * 无所谓，反正启动日志本来就要去文件里看。
 */
@Component
public class LogBufferBridge {

    private static final Logger log = LoggerFactory.getLogger(LogBufferBridge.class);

    private final LogBuffer buffer;
    private final LogProperties props;

    public LogBufferBridge(LogBuffer buffer, LogProperties props) {
        this.buffer = buffer;
        this.props = props;
    }

    @PostConstruct
    void attach() {
        MemoryLogAppender.attach(buffer);
        if (props.isEnabled()) {
            log.info("[LOGS] 日志界面已启用：缓冲上限 {} 条，脱敏={}",
                    props.getBufferSize(), props.isMaskSensitive() ? "开" : "关");
        } else {
            log.info("[LOGS] 日志界面已关闭（app.logs.enabled=false）");
        }
    }
}
