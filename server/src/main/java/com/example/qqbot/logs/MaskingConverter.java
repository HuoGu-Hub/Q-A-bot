package com.example.qqbot.logs;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback 的 %maskedMsg：控制台与文件也走脱敏，而不是只有后台日志页。
 *
 * <p>logback 在 Spring 容器之前初始化，所以这里不能注入 LogMasker bean，
 * 只能调用它的静态入口。默认开启，Spring 启动后再按配置覆盖。
 */
public class MaskingConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogMasker.maskStatic(event.getFormattedMessage());
    }
}
