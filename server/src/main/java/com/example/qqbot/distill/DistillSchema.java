package com.example.qqbot.distill;

import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillJobRepository;
import com.example.qqbot.persistence.DistillNoteRepository;
import com.example.qqbot.persistence.DistillSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 蒸馏相关表的建表入口。
 *
 * <p>即使采集关闭，管理页的导入与分批也要能用，所以建表不能挂在采集服务上。
 */
@Component
public class DistillSchema {

    private static final Logger log = LoggerFactory.getLogger(DistillSchema.class);

    private final ChatRepository chat;
    private final DistillJobRepository jobs;
    private final DistillNoteRepository notes;
    private final DistillSettingRepository settings;

    public DistillSchema(ChatRepository chat, DistillJobRepository jobs,
                         DistillNoteRepository notes, DistillSettingRepository settings) {
        this.chat = chat;
        this.jobs = jobs;
        this.notes = notes;
        this.settings = settings;
    }

    @PostConstruct
    void init() {
        if (!chat.isAvailable()) {
            log.warn("[DISTILL] 数据库不可用，聊天蒸馏功能关闭");
            return;
        }
        try {
            chat.initSchema();
            jobs.initSchema();
            notes.initSchema();
            settings.initSchema();
            log.info("[DISTILL] 聊天蒸馏表就绪：消息 {}，图片 {}，任务 {}，笔记 {}，自动蒸馏 {}",
                    chat.countMessages(), chat.countImages(), jobs.countJobs(), notes.count(),
                    settings.getOrCreate().autoEnabled() ? "开" : "关");
        } catch (Exception e) {
            log.warn("[DISTILL] 建表失败（不影响问答）：{}", e.getMessage());
        }
    }
}
