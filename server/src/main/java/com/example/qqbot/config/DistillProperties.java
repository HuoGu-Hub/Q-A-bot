package com.example.qqbot.config;

import com.example.qqbot.distill.DistillPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天蒸馏配置，对应 application.yml 里的 {@code app.distill.*}。
 *
 * <p>采集默认关闭：只有显式配置 {@code capture-groups} 并打开开关，才会落群消息。
 */
@ConfigurationProperties(prefix = "app.distill")
public class DistillProperties implements DistillPolicy {

    /** 采集开关。默认关闭，避免无意中把不想留的群写进库 */
    private boolean captureEnabled = false;

    /** 采集群白名单。空表示一个都不采 */
    private List<Long> captureGroups = new ArrayList<>();

    /** 采集队列容量 */
    private int queueCapacity = 500;

    /** 一次批量落库条数 */
    private int flushBatchSize = 20;

    /** 单条消息最多下载几张图 */
    private int maxImagesPerMessage = 4;

    /** 聊天图片目录 */
    private String imagesDir = "./data/chat-images";

    /** 单张图片上限，KB */
    private int maxImageSizeKb = 5120;

    /** 原始消息保留天数 */
    private int messageRetentionDays = 90;

    /** 聊天图片保留天数 */
    private int imageRetentionDays = 30;

    /** 每批最多多少条消息 */
    private int batchMaxMessages = 80;

    /** 每批最多多少字 */
    private int batchMaxChars = 10000;

    /** 会话间隙，分钟 */
    private int batchMaxGapMinutes = 30;

    /** 相邻批次重叠条数 */
    private int batchOverlap = 5;

    /** 单任务最多批次 */
    private int maxBatchesPerJob = 200;

    /** 单批最多产出笔记数 */
    private int maxNotesPerBatch = 12;

    /** 是否自动发布达到门槛的笔记 */
    private boolean autoPublish = true;

    /** 自动发布最低置信度 */
    private double minConfidence = 0.75;

    /** 提示词版本 */
    private String promptVersion = "v1";

    /** 单次导入最大字符数 */
    private int maxImportChars = 20_000_000;

    @Override
    public boolean isCaptureEnabled() {
        return captureEnabled;
    }

    public void setCaptureEnabled(boolean captureEnabled) {
        this.captureEnabled = captureEnabled;
    }

    @Override
    public List<Long> getCaptureGroups() {
        return captureGroups;
    }

    public void setCaptureGroups(List<Long> captureGroups) {
        this.captureGroups = captureGroups == null ? new ArrayList<>() : captureGroups;
    }

    @Override
    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    @Override
    public int getFlushBatchSize() {
        return flushBatchSize;
    }

    public void setFlushBatchSize(int flushBatchSize) {
        this.flushBatchSize = flushBatchSize;
    }

    @Override
    public int getMaxImagesPerMessage() {
        return maxImagesPerMessage;
    }

    public void setMaxImagesPerMessage(int maxImagesPerMessage) {
        this.maxImagesPerMessage = maxImagesPerMessage;
    }

    @Override
    public String getImagesDir() {
        return imagesDir;
    }

    public void setImagesDir(String imagesDir) {
        this.imagesDir = imagesDir;
    }

    @Override
    public int getMaxImageSizeKb() {
        return maxImageSizeKb;
    }

    public void setMaxImageSizeKb(int maxImageSizeKb) {
        this.maxImageSizeKb = maxImageSizeKb;
    }

    @Override
    public int getMessageRetentionDays() {
        return messageRetentionDays;
    }

    public void setMessageRetentionDays(int messageRetentionDays) {
        this.messageRetentionDays = messageRetentionDays;
    }

    @Override
    public int getImageRetentionDays() {
        return imageRetentionDays;
    }

    public void setImageRetentionDays(int imageRetentionDays) {
        this.imageRetentionDays = imageRetentionDays;
    }

    @Override
    public int getBatchMaxMessages() {
        return batchMaxMessages;
    }

    public void setBatchMaxMessages(int batchMaxMessages) {
        this.batchMaxMessages = batchMaxMessages;
    }

    @Override
    public int getBatchMaxChars() {
        return batchMaxChars;
    }

    public void setBatchMaxChars(int batchMaxChars) {
        this.batchMaxChars = batchMaxChars;
    }

    @Override
    public int getBatchMaxGapMinutes() {
        return batchMaxGapMinutes;
    }

    public void setBatchMaxGapMinutes(int batchMaxGapMinutes) {
        this.batchMaxGapMinutes = batchMaxGapMinutes;
    }

    @Override
    public int getBatchOverlap() {
        return batchOverlap;
    }

    public void setBatchOverlap(int batchOverlap) {
        this.batchOverlap = batchOverlap;
    }

    @Override
    public int getMaxBatchesPerJob() {
        return maxBatchesPerJob;
    }

    public void setMaxBatchesPerJob(int maxBatchesPerJob) {
        this.maxBatchesPerJob = maxBatchesPerJob;
    }

    @Override
    public int getMaxNotesPerBatch() {
        return maxNotesPerBatch;
    }

    public void setMaxNotesPerBatch(int maxNotesPerBatch) {
        this.maxNotesPerBatch = maxNotesPerBatch;
    }

    @Override
    public boolean isAutoPublish() {
        return autoPublish;
    }

    public void setAutoPublish(boolean autoPublish) {
        this.autoPublish = autoPublish;
    }

    @Override
    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    @Override
    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    @Override
    public int getMaxImportChars() {
        return maxImportChars;
    }

    public void setMaxImportChars(int maxImportChars) {
        this.maxImportChars = maxImportChars;
    }
}
