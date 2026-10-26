package com.example.qqbot.distill;

import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillJobRepository;
import com.example.qqbot.persistence.DistillSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动蒸馏触发器。
 *
 * <p>规则只有一句话：<b>新消息累计到 N 条，或新图片累计到 M 张，就切一段出来蒸馏。</b>
 * 两个条件谁先满足用谁，不要同时开两个任务。
 *
 * <p>游标存在 distill_setting.last_message_id：只处理游标之后的新消息。
 * 任务建好后立刻推进游标，失败重试由任务自己的批次状态负责，不会重复喂整段。
 */
@Service
public class DistillAutoService {

    private static final Logger log = LoggerFactory.getLogger(DistillAutoService.class);

    private final DistillSettingRepository settingsRepo;
    private final DistillJobRepository jobRepo;
    private final ChatRepository chat;
    private final DistillJobService jobs;
    private final DistillWorker worker;
    private final DistillPolicy props;

    public DistillAutoService(DistillSettingRepository settingsRepo, DistillJobRepository jobRepo,
                              ChatRepository chat, DistillJobService jobs, DistillWorker worker,
                              DistillPolicy props) {
        this.settingsRepo = settingsRepo;
        this.jobRepo = jobRepo;
        this.chat = chat;
        this.jobs = jobs;
        this.worker = worker;
        this.props = props;
    }

    public DistillSettingRepository.Settings settings() {
        return settingsRepo.getOrCreate();
    }

    /** 保存设置；游标不动，避免丢弃已经采集但还没蒸馏的消息 */
    public DistillSettingRepository.Settings save(DistillSettingRepository.Settings raw) {
        DistillSettingRepository.Settings old = settingsRepo.getOrCreate();
        // 触发条数就是每批上限，不再维护两个会互相打架的旋钮
        int triggerMessages = clamp(raw.triggerMessages(), 1, 500);
        int batchMessages = triggerMessages;
        int overlap = clamp(raw.overlap(), 0, Math.max(0, triggerMessages - 1));
        String focus = raw.focus() == null ? "" : raw.focus().strip();
        if (focus.length() > 1000) {
            focus = focus.substring(0, 1000);
        }
        String provider = raw.modelProvider() == null ? "" : raw.modelProvider().strip();
        if (provider.length() > 64) {
            provider = provider.substring(0, 64);
        }
        DistillSettingRepository.Settings clean = new DistillSettingRepository.Settings(
                raw.autoEnabled(),
                triggerMessages,
                clamp(raw.triggerImages(), 0, Math.max(1, props.getMaxImagesPerBatch())),
                batchMessages,
                clamp(raw.batchChars(), 1_000, 100_000),
                overlap,
                clamp(raw.maxGapMinutes(), 0, 1_440),
                raw.autoPublish(),
                Math.max(0, Math.min(1, raw.minConfidence())),
                focus,
                provider,
                old.lastMessageId(),
                old.updatedAt());
        DistillSettingRepository.Settings saved = settingsRepo.save(clean);
        if (!old.autoEnabled() && saved.autoEnabled()) {
            log.info("[DISTILL] 自动蒸馏已开启，从游标 {} 之后累计", saved.lastMessageId());
        }
        return saved;
    }

    /** 手动「立即蒸馏一次」：忽略阈值，把游标之后的新消息直接切一段 */
    public long runNow() {
        return create(true);
    }

    @Scheduled(fixedDelayString = "${app.distill.auto-check-ms:30000}", initialDelay = 15_000)
    public void tick() {
        try {
            create(false);
        } catch (Exception e) {
            log.warn("[DISTILL] 自动蒸馏检查失败：{}", e.getMessage());
        }
    }

    private synchronized long create(boolean force) {
        DistillSettingRepository.Settings s = settingsRepo.getOrCreate();
        if (!force && !s.autoEnabled()) {
            return 0;
        }
        List<Long> groups = props.getCaptureGroups();
        if (groups == null || groups.isEmpty()) {
            if (force) {
                throw new IllegalStateException("没有配置采集群，无法蒸馏");
            }
            return 0;
        }
        if (jobRepo.countActiveAutoJobs() > 0) {
            if (force) {
                throw new IllegalStateException("已有蒸馏任务在运行，等它结束再试");
            }
            return 0;
        }
        if (!force && s.triggerMessages() <= 0 && s.triggerImages() <= 0) {
            return 0;
        }
        int need = Math.max(s.triggerMessages(), Math.max(1, s.triggerImages()));
        int limit = force ? 2000 : Math.min(2000, Math.max(500, need * 10 + 50));
        List<ChatRepository.Message> messages = chat.messagesAfter(s.lastMessageId(), groups, limit);
        if (messages.isEmpty()) {
            if (force) {
                throw new IllegalStateException("没有新消息可蒸馏");
            }
            return 0;
        }

        long cutoff = force ? messages.get(messages.size() - 1).id() : chooseCutoff(s, messages);
        if (cutoff <= 0) {
            return 0;
        }
        ChatRepository.Message first = messages.get(0);
        ChatRepository.Message last = messages.get(messages.size() - 1);
        int processed = messages.size();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id() == cutoff) {
                last = messages.get(i);
                processed = i + 1;
                break;
            }
        }

        DistillJobService.JobRequest req = new DistillJobService.JobRequest(
                "live", groups, first.ts(), last.ts(), 0, "count",
                s.triggerMessages(), s.overlap(), s.maxGapMinutes());
        long jobId = jobs.create(req);
        worker.start(jobId);
        long next = nextCursor(s.lastMessageId(), cutoff, s.overlap());
        settingsRepo.updateCursor(next);
        log.info("[DISTILL] 自动蒸馏创建任务 {}：消息 {} 条，范围 {}–{}，触发条件 {} 条 / {} 图，游标推进到 {}",
                jobId, processed, first.id(), cutoff, s.triggerMessages(), s.triggerImages(), next);
        return jobId;
    }

    /** 返回截断消息 id；0 表示还没到阈值 */
    private long chooseCutoff(DistillSettingRepository.Settings s, List<ChatRepository.Message> messages) {
        long byMessages = 0;
        if (s.triggerMessages() > 0 && messages.size() >= s.triggerMessages()) {
            byMessages = messages.get(s.triggerMessages() - 1).id();
        }
        long byImages = 0;
        if (s.triggerImages() > 0) {
            Map<Long, Integer> counts = new HashMap<>();
            for (ChatRepository.Image img : chat.imagesOfMessages(messages.stream().map(ChatRepository.Message::id).toList())) {
                counts.merge(img.messageId(), 1, Integer::sum);
            }
            int seen = 0;
            for (ChatRepository.Message m : messages) {
                seen += counts.getOrDefault(m.id(), 0);
                if (seen >= s.triggerImages()) {
                    byImages = m.id();
                    break;
                }
            }
        }
        if (byMessages <= 0) {
            return byImages;
        }
        if (byImages <= 0) {
            return byMessages;
        }
        return Math.min(byMessages, byImages);
    }

    /**
     * 下一轮从哪继续：保留 overlap 条上下文，但至少向前推进一条，避免小批次原地打转。
     */
    static long nextCursor(long start, long cutoff, int overlap) {
        long next = Math.max(start + 1, cutoff - Math.max(0, overlap));
        return Math.min(cutoff, next);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
