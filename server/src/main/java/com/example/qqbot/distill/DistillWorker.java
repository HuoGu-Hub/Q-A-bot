package com.example.qqbot.distill;

import com.example.qqbot.kb.category.CategoryService;
import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillJobRepository;
import com.example.qqbot.persistence.DistillNoteRepository;
import com.example.qqbot.persistence.DistillSettingRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 蒸馏 worker：逐批取消息、调模型、写笔记、按门槛发布。
 *
 * <p>状态全部在库里：进程重启后把 running 批次放回 pending，用户再点开始即可续跑。
 * 同一任务同一时刻只允许一个 worker，避免重复产笔记。
 */
@Service
public class DistillWorker {

    private static final Logger log = LoggerFactory.getLogger(DistillWorker.class);

    /** 一批最多自动重试几次 */
    private static final int MAX_ATTEMPTS = 2;

    private final DistillJobRepository jobs;
    private final ChatRepository chat;
    private final DistillNoteRepository notes;
    private final DistillLlm llm;
    private final DistillResponseParser parser;
    private final DistillPublisher publisher;
    private final CategoryService categories;
    private final ChatImageStore imageStore;
    private final DistillSettingRepository settings;
    private final DistillPolicy props;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "distill-worker");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final Set<Long> paused = ConcurrentHashMap.newKeySet();

    public DistillWorker(DistillJobRepository jobs, ChatRepository chat, DistillNoteRepository notes,
                         DistillLlm llm, DistillResponseParser parser, DistillPublisher publisher,
                         CategoryService categories, ChatImageStore imageStore,
                         DistillSettingRepository settings, DistillPolicy props) {
        this.jobs = jobs;
        this.chat = chat;
        this.notes = notes;
        this.llm = llm;
        this.parser = parser;
        this.publisher = publisher;
        this.categories = categories;
        this.imageStore = imageStore;
        this.settings = settings;
        this.props = props;
    }

    @PreDestroy
    void stop() {
        exec.shutdownNow();
    }

    /** 开始或继续一个任务；同一任务重复点不会起两个 worker */
    public boolean start(long jobId) {
        DistillJobRepository.Job job = jobs.findJob(jobId);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在：" + jobId);
        }
        if (!running.add(jobId)) {
            return false;
        }
        paused.remove(jobId);
        jobs.resetRunningBatches(jobId);
        jobs.updateJobStatus(jobId, "running", "");
        exec.submit(() -> run(jobId));
        log.info("[DISTILL] 任务 {} 开始", jobId);
        return true;
    }

    /** 暂停：当前批次跑完就停 */
    public void pause(long jobId) {
        paused.add(jobId);
        jobs.updateJobStatus(jobId, "paused", "");
        log.info("[DISTILL] 任务 {} 请求暂停", jobId);
    }

    public record RetryResult(int reset, boolean started) {
    }

    /** 重试失败批次并继续；已无产出且完成的任务允许整批重跑 */
    public RetryResult retry(long jobId) {
        DistillJobRepository.Job job = jobs.findJob(jobId);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在：" + jobId);
        }
        int reset = jobs.resetFailedBatches(jobId);
        if (reset == 0 && "done".equals(job.status()) && job.noteCount() == 0) {
            reset = jobs.resetAllBatches(jobId);
        }
        boolean canStart = reset > 0 || !"done".equals(job.status()) || job.noteCount() == 0;
        boolean started = canStart && start(jobId);
        log.info("[DISTILL] 任务 {} 重试：重置 {} 批，启动 {}", jobId, reset, started);
        return new RetryResult(reset, started);
    }

    private void run(long jobId) {
        try {
            while (!paused.contains(jobId)) {
                DistillJobRepository.Batch batch = jobs.nextPending(jobId);
                if (batch == null) {
                    break;
                }
                jobs.markBatchRunning(batch.id(), Instant.now().toString());
                try {
                    process(jobId, batch);
                    jobs.markBatchDone(batch.id(), lastModel, lastTokensIn, lastTokensOut);
                } catch (Exception e) {
                    int attempts = batch.attempts() + 1;
                    if (attempts >= MAX_ATTEMPTS) {
                        jobs.markBatchFailed(batch.id(), e.getMessage());
                        log.warn("[DISTILL] 批次 {} 失败（第 {} 次）：{}", batch.id(), attempts, e.getMessage());
                    } else {
                        jobs.requeueBatch(batch.id(), e.getMessage());
                        log.warn("[DISTILL] 批次 {} 失败，自动重试：{}", batch.id(), e.getMessage());
                    }
                }
            }
            long failed = jobs.countByStatus(jobId, "failed");
            if (paused.contains(jobId)) {
                jobs.updateJobStatus(jobId, "paused", "");
            } else if (failed > 0) {
                jobs.finishJob(jobId, "done", failed + " 个批次失败，可在页面重试");
            } else {
                jobs.finishJob(jobId, "done", "");
            }
            log.info("[DISTILL] 任务 {} 结束，失败批次 {}", jobId, failed);
        } catch (Exception e) {
            jobs.finishJob(jobId, "failed", e.getMessage());
            log.warn("[DISTILL] 任务 {} 异常结束：{}", jobId, e.getMessage());
        } finally {
            running.remove(jobId);
            paused.remove(jobId);
        }
    }

    /** 本轮模型名与 token 数，供 markBatchDone 使用 —— 单 worker 串行，不会有并发串值 */
    private volatile String lastModel = "";
    private volatile long lastTokensIn;
    private volatile long lastTokensOut;

    private void process(long jobId, DistillJobRepository.Batch batch) {
        List<ChatRepository.Message> messages = chat.messagesBetween(batch.messageFrom(), batch.messageTo());
        if (messages.isEmpty()) {
            throw new IllegalStateException("批次范围内没有消息");
        }
        Set<Long> allowedMessages = new LinkedHashSet<>();
        for (ChatRepository.Message m : messages) {
            allowedMessages.add(m.id());
        }
        List<ChatRepository.Image> allImages = chat.imagesOfMessages(new ArrayList<>(allowedMessages));
        // 视觉模型才需要读字节；没有视觉能力时保留"未识别"文本，不让整批失败
        boolean vision = llm.hasVision();
        int maxImages = Math.max(1, props.getMaxImagesPerBatch());
        List<ChatRepository.Image> images = new ArrayList<>();
        List<DistillLlm.Attachment> attachments = new ArrayList<>();
        for (ChatRepository.Image img : allImages) {
            if (vision) {
                if (attachments.size() >= maxImages) {
                    break;
                }
                byte[] bytes = imageStore.read(img.path());
                if (bytes == null) {
                    continue;
                }
                images.add(img);
                attachments.add(new DistillLlm.Attachment(img.id(), img.mime(), bytes));
            } else {
                images.add(img);
            }
        }
        Map<Long, List<ChatRepository.Image>> imagesByMessage = new HashMap<>();
        Set<Long> allowedImages = new LinkedHashSet<>();
        for (ChatRepository.Image img : images) {
            imagesByMessage.computeIfAbsent(img.messageId(), k -> new ArrayList<>()).add(img);
            allowedImages.add(img.id());
        }
        Map<String, String> aliases = senderAliases(messages);
        List<String> cats = new ArrayList<>(categories.catalogNames());
        if (!cats.contains(CategoryService.CONTRIB)) {
            cats.add(CategoryService.CONTRIB);
        }
        var setting = settings.getOrCreate();
        List<DistillNoteRepository.Note> recentNotes = notes.recent(300);
        List<String> knownTitles = recentNotes.stream()
                .map(DistillNoteRepository.Note::title)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .limit(80)
                .toList();
        String prompt = DistillPrompts.batchPrompt(messages, aliases, imagesByMessage, cats,
                setting.focus(), knownTitles, !attachments.isEmpty());

        DistillLlm.Result result = llm.summarize(prompt, attachments, setting.modelProvider());
        lastModel = result.model();
        lastTokensIn = result.tokensIn();
        lastTokensOut = result.tokensOut();
        List<DistillDraft> drafts = parser.parse(result.text(), allowedMessages, allowedImages,
                new HashSet<>(cats), props.getMaxNotesPerBatch());
        if (drafts.isEmpty()) {
            log.info("[DISTILL] 批次 {} 没有产出笔记", batch.id());
            return;
        }
        Map<Long, ChatRepository.Message> byId = new LinkedHashMap<>();
        for (ChatRepository.Message m : messages) {
            byId.put(m.id(), m);
        }
        for (DistillDraft d : drafts) {
            List<DistillNoteRepository.Source> sources = new ArrayList<>();
            for (Long mid : d.sourceMessageIds()) {
                ChatRepository.Message m = byId.get(mid);
                if (m != null) {
                    sources.add(new DistillNoteRepository.Source(0, m.id(), m.ts(),
                            m.senderName(), m.text()));
                }
            }
            if (sources.isEmpty()) {
                continue;
            }
            DistillNoteRepository.Note duplicate = DistillDeduper.find(recentNotes, d.title(), d.body());
            if (duplicate != null) {
                mergeInto(duplicate, d, setting.autoPublish(), recentNotes);
                notes.appendSources(duplicate.id(), sources, d.imageIds());
                log.info("[DISTILL] 笔记与已有 #{} 主题重复，已合并正文/来源：{}", duplicate.id(), d.title());
                continue;
            }
            DistillNoteRepository.Inserted ins = notes.insert(new DistillNoteRepository.NewNote(
                    jobId, batch.id(), d.title(), d.titleNorm(), d.aliases(), d.body(),
                    d.category(), d.confidence(), "draft", result.model(), props.getPromptVersion(),
                    d.bodyHash(), Instant.now().toString()), sources, d.imageIds());
            if (!ins.created()) {
                notes.appendSources(ins.id(), sources, d.imageIds());
                log.debug("[DISTILL] 笔记重复，已合并来源：{}", d.title());
                continue;
            }
            DistillNoteRepository.Note saved = notes.find(ins.id());
            if (saved != null) {
                recentNotes.add(0, saved);
            }
            // 二选一：开启就全部自动发布，关闭就全部留草稿等人工整理。
            // 不再用置信度偷偷拦截，否则用户看到"开了自动发布"却仍有草稿。
            if (setting.autoPublish()) {
                DistillNoteRepository.Note note = notes.find(ins.id());
                try {
                    publisher.publish(note);
                } catch (Exception e) {
                    // 笔记已经是草稿，发布失败不影响整批
                    log.warn("[DISTILL] 笔记 {} 自动发布失败：{}", note.id(), e.getMessage());
                }
            }
        }
        log.info("[DISTILL] 批次 {} 完成：{} 条候选笔记", batch.id(), drafts.size());
    }

    /** 旧笔记 + 新笔记合并：正文补新行、别名并集、置信度取高，发布过的同步更新块 */
    private void mergeInto(DistillNoteRepository.Note old, DistillDraft candidate,
                           boolean autoPublish, List<DistillNoteRepository.Note> recentNotes) {
        String body = DistillDeduper.mergeBody(old.body(), candidate.body());
        String aliases = DistillDeduper.mergeAliases(old.aliases(), candidate.aliases());
        if (body.equals(old.body()) && aliases.equals(old.aliases())) {
            return;
        }
        try {
            notes.updateContent(old.id(), old.title(), old.titleNorm(), aliases, body,
                    DistillResponseParser.hash(DistillResponseParser.normalizeBody(body)),
                    old.category(), Math.max(old.confidence(), candidate.confidence()));
            DistillNoteRepository.Note updated = notes.find(old.id());
            if (updated == null) {
                return;
            }
            int index = recentNotes.indexOf(old);
            if (index >= 0) {
                recentNotes.set(index, updated);
            } else {
                recentNotes.add(0, updated);
            }
            if ("published".equals(updated.status()) || autoPublish) {
                try {
                    publisher.publish(updated);
                } catch (Exception e) {
                    log.warn("[DISTILL] 合并后发布失败，笔记仍是草稿：{}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[DISTILL] 合并笔记 {} 失败，仅追加来源：{}", old.id(), e.getMessage());
        }
    }

    private static Map<String, String> senderAliases(List<ChatRepository.Message> messages) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ChatRepository.Message m : messages) {
            String key = m.senderHash() == null || m.senderHash().isBlank() ? "" : m.senderHash();
            if (!out.containsKey(key)) {
                int i = out.size();
                String alias = i < 26 ? "群友" + (char) ('A' + i) : "群友" + (i + 1);
                out.put(key, alias);
            }
        }
        return out;
    }
}
