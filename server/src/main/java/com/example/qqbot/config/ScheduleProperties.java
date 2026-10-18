package com.example.qqbot.config;

import com.example.qqbot.command.SchedulePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时输出的配置，对应 application.yml 里的 app.commands.schedule.*
 *
 * <p>与指令系统其余配置一样是<b>热生效</b>的：调用方持有本对象的引用，每次实时读字段。
 */
@ConfigurationProperties(prefix = "app.commands.schedule")
public class ScheduleProperties implements SchedulePolicy {

    /** 到点发送总开关 */
    private boolean enabled = true;

    /**
     * 错过多久之内还补发。
     *
     * <p>30 分钟是权衡的结果：重启、短暂掉线不该把任务丢掉，
     * 但半夜重启后补发一条「下午三点集合」只会让人困惑。
     */
    private int graceMinutes = 30;

    /** 发送失败最多重试几次 */
    private int maxAttempts = 3;

    /** 两次重试之间的间隔（秒） */
    private int retryDelaySeconds = 60;

    /** 每人最多待发几条 —— 防的是把"定时发送"当骚扰工具 */
    private int maxPendingPerUser = 5;

    /** 一条内容的长度上限 */
    private int maxContentChars = 200;

    /** 最远能定到多少天以后 */
    private int maxFutureDays = 30;

    /** 私聊能不能用。默认关：私聊里没有群管的第二双眼睛 */
    private boolean allowPrivate = false;

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public int getGraceMinutes() {
        return graceMinutes;
    }

    public void setGraceMinutes(int graceMinutes) {
        this.graceMinutes = graceMinutes;
    }

    @Override
    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    @Override
    public int getRetryDelaySeconds() {
        return retryDelaySeconds;
    }

    public void setRetryDelaySeconds(int retryDelaySeconds) {
        this.retryDelaySeconds = retryDelaySeconds;
    }

    @Override
    public int getMaxPendingPerUser() {
        return maxPendingPerUser;
    }

    public void setMaxPendingPerUser(int maxPendingPerUser) {
        this.maxPendingPerUser = maxPendingPerUser;
    }

    @Override
    public int getMaxContentChars() {
        return maxContentChars;
    }

    public void setMaxContentChars(int maxContentChars) {
        this.maxContentChars = maxContentChars;
    }

    @Override
    public int getMaxFutureDays() {
        return maxFutureDays;
    }

    public void setMaxFutureDays(int maxFutureDays) {
        this.maxFutureDays = maxFutureDays;
    }

    @Override
    public boolean isAllowPrivate() {
        return allowPrivate;
    }

    public void setAllowPrivate(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }
}
