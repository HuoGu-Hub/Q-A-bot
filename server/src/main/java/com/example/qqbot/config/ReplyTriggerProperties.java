package com.example.qqbot.config;

import com.example.qqbot.trigger.TriggerPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 概率回复配置 —— 对应 {@code app.reply.*}。
 *
 * <p>全部热生效：{@link com.example.qqbot.trigger.ReplyTrigger} 每轮现读。
 * 频率交给 {@code app.guard.rate-limit.*}，这里只留概率、群白名单与每日上限。
 */
@ConfigurationProperties(prefix = "app.reply")
public class ReplyTriggerProperties implements TriggerPolicy {

    /** 主动参与度，0 到 1。0 表示只回呼叫 */
    private double probability = 0.0;

    /** 只在哪些群做概率回复，空表示所有群都不做；呼叫不受它限制 */
    private List<Long> enabledGroups = new ArrayList<>();

    /** 主动回复概率低于此值时，非呼叫消息必须 @；0 = 始终免 @ */
    private double mentionFreeThreshold = 0.0;

    /** 低概率档的关键词；命中任意一个就回复 */
    private List<String> keywords = new ArrayList<>();

    /** 低概率档判断「与游戏相关」的补充词；内置词表之外再加 */
    private List<String> gameKeywords = new ArrayList<>();

    /** 每群每日主动回复上限，0 = 不限 */
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
    public double mentionFreeThreshold() {
        return mentionFreeThreshold;
    }

    public void setMentionFreeThreshold(double mentionFreeThreshold) {
        this.mentionFreeThreshold = Math.max(0.0, Math.min(1.0, mentionFreeThreshold));
    }

    @Override
    public List<String> keywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords == null ? new ArrayList<>() : keywords;
    }

    @Override
    public List<String> gameKeywords() {
        return gameKeywords;
    }

    public void setGameKeywords(List<String> gameKeywords) {
        this.gameKeywords = gameKeywords == null ? new ArrayList<>() : gameKeywords;
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
