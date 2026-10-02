package com.example.qqbot.logs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

/**
 * 把日志同时塞进内存环形缓冲的 logback appender。
 *
 * <p>在 §logback-spring.xml§ 里注册。之所以走 logback 而不是自己包一层 Logger，
 * 是因为这样**不需要改任何业务代码** —— 全项目 100 多处 §log.info(...)§ 自动生效。
 *
 * <p>三条原则：
 * <ol>
 *   <li><b>绝不抛异常</b> —— appender 出错不能影响业务日志</li>
 *   <li><b>DEBUG 默认不进缓冲</b> —— 太吵，会瞬间挤掉有用的 INFO/ERROR</li>
 *   <li>异常只留摘要（前几行），完整堆栈去文件里看</li>
 * </ol>
 */
public class MemoryLogAppender extends AppenderBase<ILoggingEvent> {

    /** 异常堆栈在缓冲里最多留几行 */
    private static final int MAX_STACK_LINES = 8;

    /**
     * 静态持有 —— logback 在 Spring 容器之前初始化，
     * 那时候还拿不到 bean，只能这样桥接。
     */
    private static volatile LogBuffer buffer;

    /** 由 Spring 启动时注入 */
    public static void attach(LogBuffer logBuffer) {
        buffer = logBuffer;
    }

    @Override
    protected void append(ILoggingEvent event) {
        LogBuffer b = buffer;
        if (b == null) {
            return;
        }
        try {
            // DEBUG 不进缓冲：太吵，会挤掉真正有用的日志
            if (event.getLevel() == Level.TRACE || event.getLevel() == Level.DEBUG) {
                return;
            }
            b.append(
                    event.getLevel().toString(),
                    event.getFormattedMessage(),
                    briefThrowable(event.getThrowableProxy()));
        } catch (Exception ignored) {
            // appender 绝不能因为自身问题影响业务
        }
    }


    private static String briefThrowable(IThrowableProxy proxy) {
        if (proxy == null) {
            return null;
        }
        String full = ThrowableProxyUtil.asString(proxy);
        String[] lines = full.split("\n");
        if (lines.length <= MAX_STACK_LINES) {
            return full;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_STACK_LINES; i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append("…（完整堆栈见日志文件）");
        return sb.toString();
    }
}
