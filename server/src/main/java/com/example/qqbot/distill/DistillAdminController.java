package com.example.qqbot.distill;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.web.ApiException;

import com.example.qqbot.llm.LlmRouter;
import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillBlocklistRepository;
import com.example.qqbot.persistence.DistillJobRepository;
import com.example.qqbot.persistence.DistillNoteRepository;
import com.example.qqbot.persistence.DistillSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天蒸馏的管理端接口。全部在 {@code /admin} 下，自动受现有登录会话保护。
 *
 * <p>M1 只做"采集 / 导入 / 分批任务"，不调模型；模型执行在 M2 的 worker 里。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/distill")
public class DistillAdminController {

    private static final Logger log = LoggerFactory.getLogger(DistillAdminController.class);

    private final ChatCaptureService capture;
    private final ChatImportService imports;
    private final DistillJobService jobs;
    private final DistillWorker worker;
    private final DistillAutoService auto;
    private final DistillLlm llm;
    private final DistillNoteService notes;
    private final ChatRepository chat;
    private final ChatImageStore imageStore;
    private final DistillBlocklistRepository blocklist;

    public DistillAdminController(ChatCaptureService capture, ChatImportService imports,
                                  DistillJobService jobs, DistillWorker worker,
                                  DistillAutoService auto, DistillLlm llm,
                                  DistillNoteService notes, ChatRepository chat,
                                  ChatImageStore imageStore,
                                  DistillBlocklistRepository blocklist) {
        this.capture = capture;
        this.imports = imports;
        this.jobs = jobs;
        this.worker = worker;
        this.auto = auto;
        this.llm = llm;
        this.notes = notes;
        this.chat = chat;
        this.imageStore = imageStore;
        this.blocklist = blocklist;
    }

    /** 概览：采集状态 + 各类计数 */
    @GetMapping("/stats")
    public DistillStats stats() {
        ChatCaptureService.Status s = capture.status();
        return new DistillStats(jobs.available(),
                new CaptureStatus(s.enabled(), s.groups(), s.queueSize(), s.recorded(),
                        s.dropped(), s.imageSaved()),
                jobs.countMessages(), jobs.countImages(), jobs.countImports(),
                jobs.countJobs(), jobs.countBatches(), notes.count(null),
                notes.count("draft"), notes.count("published"));
    }

    /** 最近采集到的消息，用来确认采集是否真的通 */
    @GetMapping("/messages")
    public MessageList messages(@RequestParam(defaultValue = "50") int limit) {
        List<MessageRow> rows = new ArrayList<>();
        for (ChatRepository.Message m : chat.recentMessages(limit)) {
            rows.add(new MessageRow(m.id(), m.ts(), m.groupId(), m.senderName(), m.text(),
                    m.text() == null ? 0 : m.text().length(), m.imageCount(),
                    m.source(), m.importId()));
        }
        return new MessageList(rows, rows.size());
    }

    /** 一条消息与它的图片元数据 */
    @GetMapping("/messages/{id}")
    public ResponseEntity<MessageDetail> message(@PathVariable long id) {
        ChatRepository.Message m = chat.findMessage(id);
        if (m == null) {
            throw ApiException.notFound("资源不存在");
        }
        List<ImageRow> images = new ArrayList<>();
        for (ChatRepository.Image img : chat.imagesOfMessage(id)) {
            images.add(new ImageRow(img.id(), img.md5(), img.mime(), img.bytes(),
                    img.caption()));
        }
        return ResponseEntity.ok(new MessageDetail(m.id(), m.ts(), m.groupId(),
                m.senderName(), m.text(), m.imageCount(), m.source(), images));
    }

    /** 导入预览：只解析，不写库 */
    @PostMapping(value = "/import/preview", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<ChatImportService.Preview> previewImport(@RequestParam(defaultValue = "chat.txt") String filename,
                                           @RequestBody String content) {
        try {
            return ResponseEntity.ok(imports.preview(filename, content));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 导入聊天文件；请求体就是文件原文 */
    @PostMapping(value = "/import", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<ChatImportService.ImportResult> importFile(@RequestParam(defaultValue = "chat.txt") String filename,
                                        @RequestParam(defaultValue = "0") long groupId,
                                        @RequestBody String content) {
        try {
            return ResponseEntity.ok(imports.importFile(filename, content, groupId));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (Exception e) {
            log.warn("[DISTILL] 导入失败：{}", e.getMessage());
            throw ApiException.internal(e.getMessage());
        }
    }

    @GetMapping("/imports")
    public ImportList imports(@RequestParam(defaultValue = "50") int limit) {
        List<ChatRepository.ImportRow> rows = chat.recentImports(limit);
        return new ImportList(rows, rows.size());
    }

    /** 分批预览：算批次数与 token 估算，不建任务 */
    @PostMapping("/jobs/preview")
    public ResponseEntity<DistillJobService.Preview> previewJob(@RequestBody DistillJobService.JobRequest request) {
        try {
            return ResponseEntity.ok(jobs.preview(request));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 创建任务与批次 */
    @PostMapping("/jobs")
    public ResponseEntity<JobCreated> createJob(@RequestBody DistillJobService.JobRequest request) {
        try {
            long id = jobs.create(request);
            return ResponseEntity.ok(new JobCreated(true, id));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (Exception e) {
            log.warn("[DISTILL] 建任务失败：{}", e.getMessage());
            throw ApiException.internal(e.getMessage());
        }
    }

    @GetMapping("/jobs")
    public JobPage jobs(@RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        List<DistillJobRepository.Job> rows = jobs.jobs(p, s);
        long total = jobs.countJobs();
        long pages = Math.max(1, (total + s - 1) / s);
        return new JobPage(rows, total, p, s, pages);
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<DistillJobService.JobDetail> job(@PathVariable long id) {
        DistillJobService.JobDetail detail = jobs.detail(id);
        if (detail == null) {
            throw ApiException.notFound("没有这个任务");
        }
        return ResponseEntity.ok(detail);
    }

    /** 开始或继续一个任务 */
    @PostMapping("/jobs/{id}/start")
    public ResponseEntity<StartedResult> start(@PathVariable long id) {
        try {
            boolean started = worker.start(id);
            return ResponseEntity.ok(new StartedResult(true, started));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @PostMapping("/jobs/{id}/pause")
    public ResponseEntity<Void> pause(@PathVariable long id) {
        worker.pause(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<RetryView> retry(@PathVariable long id) {
        try {
            DistillWorker.RetryResult r = worker.retry(id);
            return ResponseEntity.ok(new RetryView(true, r.reset(), r.started()));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 禁学名单：已屏蔽成员 + 最近发言成员 */
    @GetMapping("/blocklist")
    public BlocklistView blocklist() {
        return new BlocklistView(blocklist.list(), blocklist.senders(300));
    }

    @PostMapping("/blocklist")
    public ResponseEntity<Void> block(@RequestBody BlockSenderRequest body) {
        String hash = body.senderHash();
        if (hash == null || hash.isBlank()) {
            throw ApiException.badRequest("缺少 senderHash");
        }
        blocklist.add(hash, body.senderName());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/blocklist/{hash}")
    public ResponseEntity<Void> unblock(@PathVariable String hash) {
        blocklist.remove(hash);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/blocklist/qq")
    public ResponseEntity<BlockQqResult> blockQq(@RequestBody BlockQqRequest body) {
        try {
            int updated = blocklist.blockQq(body.qq(), body.senderName());
            return ResponseEntity.ok(new BlockQqResult(true, updated));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/blocklist/qq/{qq}")
    public ResponseEntity<Void> unblockQq(@PathVariable String qq) {
        blocklist.removeQq(qq);
        return ResponseEntity.noContent().build();
    }

    /** 自动蒸馏设置 + 采集群 */
    @GetMapping("/settings")
    public SettingsView settings() {
        DistillSettingRepository.Settings s = auto.settings();
        return new SettingsView(s, capture.status().groups(), llm.providers(),
                chat.countAfter(s.lastMessageId(), capture.status().groups()));
    }

    /** 保存触发条件与分批参数 */
    @PutMapping("/settings")
    public ResponseEntity<SettingsSaved> saveSettings(@RequestBody DistillSettingsRequest body) {
        try {
            DistillSettingRepository.Settings old = auto.settings();
            DistillSettingRepository.Settings next = new DistillSettingRepository.Settings(
                    Boolean.TRUE.equals(body.autoEnabled()),
                    intOr(body.triggerMessages(), 0),
                    intOr(body.triggerImages(), 0),
                    intOr(body.batchMessages(), 80),
                    intOr(body.batchChars(), 10_000),
                    intOr(body.overlap(), 0),
                    intOr(body.maxGapMinutes(), 30),
                    Boolean.TRUE.equals(body.autoPublish()),
                    body.minConfidence() == null ? 0.75 : body.minConfidence(),
                    body.focus() == null ? "" : body.focus(),
                    body.modelProvider() == null ? "" : body.modelProvider(),
                    body.captureGroups() == null ? "" : body.captureGroups(),
                    Boolean.TRUE.equals(body.captureAllGroups()),
                    old.lastMessageId(), old.updatedAt());
            return ResponseEntity.ok(new SettingsSaved(true, auto.save(next)));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 立即蒸馏一次；忽略阈值，处理游标之后的新消息 */
    @PostMapping("/settings/run-now")
    public ResponseEntity<RunNowResult> runNow() {
        try {
            return ResponseEntity.ok(new RunNowResult(true, auto.runNow()));
        } catch (IllegalStateException e) {
            throw ApiException.conflict("CONFLICT", e.getMessage());
        } catch (Exception e) {
            log.warn("[DISTILL] 手动蒸馏失败：{}", e.getMessage());
            throw ApiException.internal(e.getMessage());
        }
    }

    /** 笔记列表 */
    @GetMapping("/notes")
    public NoteList notes(@RequestParam(required = false) String status,
                          @RequestParam(defaultValue = "0") long jobId,
                          @RequestParam(defaultValue = "100") int limit) {
        List<DistillNoteRepository.Note> rows = notes.list(status, jobId, limit);
        return new NoteList(rows, rows.size());
    }

    /** 笔记详情：正文 + 来源 + 图片 */
    @GetMapping("/notes/{id}")
    public ResponseEntity<DistillNoteService.Detail> note(@PathVariable long id) {
        DistillNoteService.Detail detail = notes.detail(id);
        if (detail == null) {
            throw ApiException.notFound("没有这条笔记");
        }
        return ResponseEntity.ok(detail);
    }

    /** 编辑标题 / 别名 / 分类 / 正文 / 置信度 */
    @PutMapping("/notes/{id}")
    public ResponseEntity<DistillNoteRepository.Note> updateNote(@PathVariable long id, @RequestBody UpdateNoteRequest body) {
        try {
            var note = notes.update(id, body.title(), body.aliases(), body.category(),
                    body.body(), body.confidence());
            if (note == null) {
                throw ApiException.notFound("没有这条笔记");
            }
            return ResponseEntity.ok(note);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (Exception e) {
            log.warn("[DISTILL] 保存笔记失败：{}", e.getMessage());
            throw ApiException.internal(e.getMessage());
        }
    }

    @PostMapping("/notes/{id}/publish")
    public ResponseEntity<DistillNoteRepository.Note> publish(@PathVariable long id) {
        try {
            var note = notes.publish(id);
            if (note == null) {
                throw ApiException.notFound("没有这条笔记");
            }
            return ResponseEntity.ok(note);
        } catch (Exception e) {
            log.warn("[DISTILL] 发布笔记失败：{}", e.getMessage());
            throw ApiException.internal(e.getMessage());
        }
    }

    @PostMapping("/notes/{id}/unpublish")
    public ResponseEntity<DistillNoteRepository.Note> unpublish(@PathVariable long id) {
        var note = notes.unpublish(id);
        if (note == null) {
            throw ApiException.notFound("没有这条笔记");
        }
        return ResponseEntity.ok(note);
    }

    @DeleteMapping("/notes/{id}")
    public ResponseEntity<Void> deleteNote(@PathVariable long id) {
        if (!notes.delete(id)) {
            throw ApiException.notFound("没有这条笔记");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 批量彻底删除。语义与单条删除完全一致：块、图片、笔记一起删，不可恢复。
     *
     * <p>上限 500：一次点太多说明操作方式不对，也避免一个请求把 SQLite 写锁占太久。
     * 逐条删而不是一条 SQL 全删，是为了让 {@code DistillPublisher.delete} 里的
     * 块与图片清理保持同一套逻辑 —— 两套删除路径迟早会不一致。
     */
    @PostMapping("/notes/actions/batch-delete")
    public ResponseEntity<BatchDeleteResult> deleteNotes(@RequestBody(required = false) BatchDeleteRequest body) {
        List<Long> ids = body == null ? null : body.ids();
        if (ids == null || ids.isEmpty()) {
            throw ApiException.badRequest("需要 ids 数组");
        }
        if (ids.size() > 500) {
            throw ApiException.badRequest("一次最多删除 500 条");
        }
        int deleted = 0;
        int missing = 0;
        for (Long id : ids) {
            if (id == null) {
                missing++;
                continue;
            }
            if (notes.delete(id)) {
                deleted++;
            } else {
                missing++;
            }
        }
        log.info("[DISTILL] 批量删除：成功 {}，缺失 {}", deleted, missing);
        return ResponseEntity.ok(new BatchDeleteResult(true, deleted, missing));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static int intOf(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? fallback : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double dblOf(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? fallback : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Double dbl(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? null : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 受鉴权的聊天图片字节 */
    @GetMapping("/images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable long id) {
        ChatRepository.Image meta = chat.findImage(id);
        if (meta == null) {
            throw ApiException.notFound("资源不存在");
        }
        byte[] bytes = imageStore.read(meta.path());
        if (bytes == null) {
            throw ApiException.notFound("资源不存在");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, meta.mime() == null || meta.mime().isBlank()
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE : meta.mime())
                .body(bytes);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record BlockSenderRequest(String senderHash, String senderName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record BlockQqRequest(String qq, String senderName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record DistillSettingsRequest(
            Boolean autoEnabled,
            Integer triggerMessages,
            Integer triggerImages,
            Integer batchMessages,
            Integer batchChars,
            Integer overlap,
            Integer maxGapMinutes,
            Boolean autoPublish,
            Double minConfidence,
            String focus,
            String modelProvider,
            String captureGroups,
            Boolean captureAllGroups) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record UpdateNoteRequest(String title, String aliases, String category,
                                    String body, Double confidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record BatchDeleteRequest(List<Long> ids) {
    }

    private static int intOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    public record CaptureStatus(boolean enabled, List<Long> groups, int queueSize,
                                long recorded, long dropped, long imageSaved) {
    }

    public record DistillStats(boolean available, CaptureStatus capture, long messages,
                               long images, long imports, long jobs, long batches, long notes,
                               long drafts, long publishedNotes) {
    }

    public record MessageRow(long id, String ts, long groupId, String senderName, String text,
                             int textLength, int imageCount, String source, long importId) {
    }

    public record MessageList(List<MessageRow> rows, int count) {
    }

    public record ImportList(List<ChatRepository.ImportRow> rows, int count) {
    }

    public record JobPage(List<DistillJobRepository.Job> rows, long total, int page,
                          int size, long pages) {
    }

    public record BlocklistView(List<DistillBlocklistRepository.Blocked> blocked,
                                List<DistillBlocklistRepository.Sender> senders) {
    }

    public record SettingsView(DistillSettingRepository.Settings settings, List<Long> groups,
                               List<LlmRouter.ProviderInfo> providers, long pendingMessages) {
    }

    public record NoteList(List<DistillNoteRepository.Note> rows, int count) {
    }

    public record ImageRow(long id, String md5, String mime, int bytes, String caption) {
    }

    public record MessageDetail(long id, String ts, long groupId, String senderName,
                                String text, int imageCount, String source,
                                List<ImageRow> images) {
    }

    public record JobCreated(boolean ok, long id) {
    }

    public record StartedResult(boolean ok, boolean started) {
    }

    public record RetryView(boolean ok, int reset, boolean started) {
    }

    public record BlockQqResult(boolean ok, int updated) {
    }

    public record SettingsSaved(boolean ok, DistillSettingRepository.Settings settings) {
    }

    public record RunNowResult(boolean ok, long jobId) {
    }

    public record BatchDeleteResult(boolean ok, int deleted, int missing) {
    }
}
