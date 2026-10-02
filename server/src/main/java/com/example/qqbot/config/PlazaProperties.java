package com.example.qqbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 问答广场（公开站）的配置，对应 application.yml 里的 app.plaza.*
 *
 * <p>这些都是**热生效**的 —— 改完立即起作用。
 */
@ConfigurationProperties(prefix = "app.plaza")
public class PlazaProperties {

    /** 广场总开关。关掉后公开站不显示问答内容 */
    private boolean enabled = true;

    /**
     * ★ 只展示「被点赞过」的问答。
     *
     * <p>默认 true —— 这是隐私保护的核心：
     * 有人点赞 = 主动认可公开，相当于社区审核。
     * 关掉的话所有问答都会公开（风险自负）。
     */
    private boolean onlyVoted = true;

    /**
     * 触发「全被踩」的最小踩数。
     *
     * <p>设成 2 是为了避免「一个人点踩就触发降级」——太敏感。
     */
    private int downvoteThreshold = 2;

    /** 每个关键词最多展示几条回答 */
    private int maxAnswersPerKeyword = 3;

    /**
     * 展示前做去重：相似度高于这个值就合并。
     *
     * <p>0.9 = 很相似才合并。太低会把不同的答案误合并。
     */
    private double dedupThreshold = 0.9;

    /** 「问问新答案」每关键词每天最多几次 */
    private int askLimitPerKeywordPerDay = 3;

    /** 「问问新答案」全局每天最多几次（防刷爆） */
    private int askLimitGlobalPerDay = 100;

    /** 「求助大佬」同一问题每天最多几次 */
    private int helpLimitPerQuestionPerDay = 1;

    /** 「求助大佬」每用户每天最多几次 */
    private int helpLimitPerUserPerDay = 3;

    /** 「求助大佬」每群每小时最多几条 */
    private int helpLimitPerGroupPerHour = 1;

    /** 投票者哈希用的盐。留空则用固定串（开发环境） */
    private String voteSalt = "";

    // ==================== getter / setter ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isOnlyVoted() {
        return onlyVoted;
    }

    public void setOnlyVoted(boolean onlyVoted) {
        this.onlyVoted = onlyVoted;
    }

    public int getDownvoteThreshold() {
        return downvoteThreshold;
    }

    public void setDownvoteThreshold(int downvoteThreshold) {
        this.downvoteThreshold = downvoteThreshold;
    }

    public int getMaxAnswersPerKeyword() {
        return maxAnswersPerKeyword;
    }

    public void setMaxAnswersPerKeyword(int maxAnswersPerKeyword) {
        this.maxAnswersPerKeyword = maxAnswersPerKeyword;
    }

    public double getDedupThreshold() {
        return dedupThreshold;
    }

    public void setDedupThreshold(double dedupThreshold) {
        this.dedupThreshold = dedupThreshold;
    }

    public int getAskLimitPerKeywordPerDay() {
        return askLimitPerKeywordPerDay;
    }

    public void setAskLimitPerKeywordPerDay(int askLimitPerKeywordPerDay) {
        this.askLimitPerKeywordPerDay = askLimitPerKeywordPerDay;
    }

    public int getAskLimitGlobalPerDay() {
        return askLimitGlobalPerDay;
    }

    public void setAskLimitGlobalPerDay(int askLimitGlobalPerDay) {
        this.askLimitGlobalPerDay = askLimitGlobalPerDay;
    }

    public int getHelpLimitPerQuestionPerDay() {
        return helpLimitPerQuestionPerDay;
    }

    public void setHelpLimitPerQuestionPerDay(int helpLimitPerQuestionPerDay) {
        this.helpLimitPerQuestionPerDay = helpLimitPerQuestionPerDay;
    }

    public int getHelpLimitPerUserPerDay() {
        return helpLimitPerUserPerDay;
    }

    public void setHelpLimitPerUserPerDay(int helpLimitPerUserPerDay) {
        this.helpLimitPerUserPerDay = helpLimitPerUserPerDay;
    }

    public int getHelpLimitPerGroupPerHour() {
        return helpLimitPerGroupPerHour;
    }

    public void setHelpLimitPerGroupPerHour(int helpLimitPerGroupPerHour) {
        this.helpLimitPerGroupPerHour = helpLimitPerGroupPerHour;
    }

    public String getVoteSalt() {
        return voteSalt;
    }

    public void setVoteSalt(String voteSalt) {
        this.voteSalt = voteSalt;
    }
}
