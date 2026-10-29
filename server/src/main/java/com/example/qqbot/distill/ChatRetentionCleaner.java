package com.example.qqbot.distill;

import com.example.qqbot.persistence.ChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 聊天原文与图片的保留期清理。
 *
 * <p>配置里写了保留天数不等于真的会删。没有这个任务，{@code chat_message} 与
 * {@code chat_image} 会一直长，备份也越来越大。按批删除，避免一次长事务锁住 SQLite。
 */
@Component
public class ChatRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(ChatRetentionCleaner.class);

    /** 一批删多少，避免长时间占用全局数据库锁 */
    private static final int BATCH = 500;

    /** 单次最多删多少，余量留给下一次 */
    private static final int MAX_PER_RUN = 5000;

    private final ChatRepository repo;
    private final DistillPolicy policy;
    private final ChatImageStore images;

    private volatile boolean schemaReady;

    public ChatRetentionCleaner(ChatRepository repo, DistillPolicy policy, ChatImageStore images) {
        this.repo = repo;
        this.policy = policy;
        this.images = images;
    }

    /** 一次清理的结果；测试与日志都用它 */
    public record Cleanup(int imageRows, int imageFiles, int messageRows) {
    }

    @Scheduled(cron = "${app.distill.cleanup-cron:0 40 4 * * *}")
    public void tick() {
        try {
            runOnce(Instant.now());
        } catch (Exception e) {
            log.error("[DISTILL] 保留期清理失败", e);
        }
    }

    public Cleanup runOnce(Instant now) {
        if (!ready()) {
            return new Cleanup(0, 0, 0);
        }
        int imageDays = policy.getImageRetentionDays();
        int messageDays = policy.getMessageRetentionDays();
        int imageRows = 0;
        int imageFiles = 0;
        int messageRows = 0;

        if (imageDays > 0) {
            String cutoff = now.minus(imageDays, ChronoUnit.DAYS).toString();
            while (imageRows < MAX_PER_RUN) {
                List<String> files = repo.oldImageFiles(cutoff, BATCH);
                int deleted = repo.deleteImagesBatch(cutoff, BATCH);
                if (deleted <= 0) {
                    break;
                }
                imageRows += deleted;
                for (String name : files) {
                    if (images.delete(name)) {
                        imageFiles++;
                    }
                }
                if (deleted < BATCH) {
                    break;
                }
            }
        }

        if (messageDays > 0) {
            String cutoff = now.minus(messageDays, ChronoUnit.DAYS).toString();
            while (messageRows < MAX_PER_RUN) {
                int deleted = repo.deleteMessagesBatch(cutoff, BATCH);
                if (deleted <= 0) {
                    break;
                }
                messageRows += deleted;
                if (deleted < BATCH) {
                    break;
                }
            }
        }

        if (imageRows > 0 || messageRows > 0) {
            log.info("[DISTILL] 保留期清理：图片 {} 行 / 文件 {} 个，消息 {} 条",
                    imageRows, imageFiles, messageRows);
        }
        if (imageRows >= MAX_PER_RUN || messageRows >= MAX_PER_RUN) {
            log.warn("[DISTILL] 本次清理达到 {} 条上限，余量留到下一次", MAX_PER_RUN);
        }
        return new Cleanup(imageRows, imageFiles, messageRows);
    }

    private boolean ready() {
        if (!repo.isAvailable()) {
            return false;
        }
        if (!schemaReady) {
            repo.initSchema();
            schemaReady = true;
        }
        return true;
    }
}
