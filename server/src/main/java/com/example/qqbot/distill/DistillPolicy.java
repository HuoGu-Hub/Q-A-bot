package com.example.qqbot.distill;

import java.util.List;

/**
 * 聊天蒸馏的配置视图 —— 业务侧声明，{@code config.DistillProperties} 实现。
 *
 * <p>业务包不直接依赖 {@code config}：构造签名能看出这里只关心采集、图片和分批这几项。
 */
public interface DistillPolicy {

    /** 是否采集群消息 */
    boolean isCaptureEnabled();

    /** 采集哪些群；空表示一个都不采 */
    List<Long> getCaptureGroups();

    /** 采集队列容量，满了丢消息并计数 */
    int getQueueCapacity();

    /** 一次批量落库多少条 */
    int getFlushBatchSize();

    /** 单条消息最多下载几张图 */
    int getMaxImagesPerMessage();

    /** 一批最多把几张图送给视觉模型 */
    int getMaxImagesPerBatch();

    /** 聊天图片目录 */
    String getImagesDir();

    /** 单张图片上限，KB */
    int getMaxImageSizeKb();

    /** 原始消息保留天数；0 = 不清理 */
    int getMessageRetentionDays();

    /** 聊天图片保留天数；0 = 不清理 */
    int getImageRetentionDays();

    /** 每批最多多少条消息 */
    int getBatchMaxMessages();

    /** 每批最多多少字 */
    int getBatchMaxChars();

    /** 两条消息间隔超过多少分钟就断开 */
    int getBatchMaxGapMinutes();

    /** 相邻批次重叠多少条消息 */
    int getBatchOverlap();

    /** 一个任务最多多少批，防止成本失控 */
    int getMaxBatchesPerJob();

    /** 一个批次最多产出多少条笔记 */
    int getMaxNotesPerBatch();

    /** 是否自动发布达到质量门槛的笔记 */
    boolean isAutoPublish();

    /** 自动发布的最低置信度 */
    double getMinConfidence();

    /** 提示词版本，写进笔记便于回溯 */
    String getPromptVersion();

    /** 一次导入最多接受多少字符 */
    int getMaxImportChars();
}
