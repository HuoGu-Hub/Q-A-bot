package com.example.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 日志界面的配置，对应 application.yml 里的 app.logs.*
 *
 * <p>只收**本进程（业务层）**的日志：内存环形缓冲 + SSE 实时推送。
 *
 * <p>原先还读另一个容器里 NapCat 的日志文件，已经删掉 —— 那一层的日志对
 * 管理员排查「机器人答得对不对」没有帮助，反而让日志页多了一整套文件选择与
 * 分页的界面，喧宾夺主。
 */
@ConfigurationProperties(prefix = "app.logs")
public class LogProperties {

    private boolean enabled = true;

    /**
     * 内存里保留多少条日志。
     *
     * <p>2000 条约占 1~2 MB。再多没意义 —— 真要查历史，
     * 文件里的 §grep§ 比任何界面都快。
     */
    private int bufferSize = 2000;

    /** SSE 连接最长存活（分钟），防止连接泄漏 */
    private int streamTimeoutMinutes = 30;

    /** 同时最多几个日志流连接 */
    private int maxStreams = 10;

    /**
     * 是否对日志做脱敏。
     *
     * <p>日志里可能出现 token、密钥、QQ 号、完整 URL。
     * 日志页可能被截图外发，所以默认开。
     */
    private boolean maskSensitive = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public int getStreamTimeoutMinutes() {
        return streamTimeoutMinutes;
    }

    public void setStreamTimeoutMinutes(int streamTimeoutMinutes) {
        this.streamTimeoutMinutes = streamTimeoutMinutes;
    }

    public int getMaxStreams() {
        return maxStreams;
    }

    public void setMaxStreams(int maxStreams) {
        this.maxStreams = maxStreams;
    }

    public boolean isMaskSensitive() {
        return maskSensitive;
    }

    public void setMaskSensitive(boolean maskSensitive) {
        this.maskSensitive = maskSensitive;
    }




}
