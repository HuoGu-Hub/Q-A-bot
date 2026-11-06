package com.example.qqbot.config;

import com.example.qqbot.trigger.TriggerPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 概率回复配置 —— 对应 {@code app.reply.*}。
 *
 * <p>全部热生效：{@link com.example.qqbot.trigger.ReplyTrigger} 每轮现读。
 */
@ConfigurationProperties(prefix = "app.reply")
public class ReplyTriggerProperties implements TriggerPolicy {

    /** 主动参与度，0 到 1。0 表示只回呼叫 */
    private double probability = 0.0;

    /** 只在哪些群做概率回复，空表示所有群都不做；呼叫不受它限制 */
    private List<Long> enabledGroups = new ArrayList<>();

    /** 同群最小间隔，0 = 不限 */
    private int minGapSeconds = 0;

    /** 同一人冷却，0 = 不限 */
    private int userCooldownSeconds = 0;

    /** 每群每日上限，0 = 不限 */
    private int dailyMaxPerGroup = 40;

    /** 只记日志，不发送 */
    private boolean shadowMode = false;

    @Override
    public double probability() {
        return probability;
    }

    public void setProbability(double probability) {
        this.probability = probability;
    }

    @Override
    public List<Long> enabledGroups() {
        return enabledGroups;
    }

    public void setEnabledGroups(List<Long> enabledGroups) {
        this.enabledGroups = enabledGroups == null ? new ArrayList<>() : enabledGroups;
    }

    @Override
    public int minGapSeconds() {
        return minGapSeconds;
    }

    public void setMinGapSeconds(int minGapSeconds) {
        this.minGapSeconds = minGapSeconds;
    }

    @Override
    public int userCooldownSeconds() {
        return userCooldownSeconds;
    }

    public void setUserCooldownSeconds(int userCooldownSeconds) {
        this.userCooldownSeconds = userCooldownSeconds;
    }

    @Override
    public int dailyMaxPerGroup() {
        return dailyMaxPerGroup;
    }

    public void setDailyMaxPerGroup(int dailyMaxPerGroup) {
        this.dailyMaxPerGroup = dailyMaxPerGroup;
    }

    @Override
    public boolean shadowMode() {
        return shadowMode;
    }

    public void setShadowMode(boolean shadowMode) {
        this.shadowMode = shadowMode;
    }
}
