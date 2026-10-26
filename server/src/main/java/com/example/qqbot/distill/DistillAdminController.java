package com.example.qqbot.distill;

import com.example.qqbot.persistence.ChatRepository;
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

    public DistillAdminController(ChatCaptureService capture, ChatImportService imports,
                                  DistillJobService jobs, DistillWorker worker,
                                  DistillAutoService auto, DistillLlm llm,
                                  DistillNoteService notes, ChatRepository chat,
                                  ChatImageStore imageStore) {
        this.capture = capture;
        this.imports = imports;
        this.jobs = jobs;
        this.worker = worker;
        this.auto = auto;
        this.llm = llm;
        this.notes = notes;
        this.chat = chat;
        this.imageStore = imageStore;
    }

    /** 概览：采集状态 + 各类计数 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        ChatCaptureService.Status s = capture.status();
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("enabled", s.enabled());
        cap.put("groups", s.groups());
        cap.put("queueSize", s.queueSize());
        cap.put("recorded", s.recorded());
        cap.put("dropped", s.dropped());
        cap.put("imageSaved", s.imageSaved());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", jobs.available());
        out.put("capture", cap);
        out.put("messages", jobs.countMessages());
        out.put("images", jobs.countImages());
        out.put("imports", jobs.countImports());
        out.put("jobs", jobs.countJobs());
        out.put("batches", jobs.countBatches());
        out.put("notes", notes.count(null));
        out.put("drafts", notes.count("draft"));
        out.put("publishedNotes", notes.count("published"));
        return out;
    }

    /** 最近采集到的消息，用来确认采集是否真的通 */
    @GetMapping("/messages")
    public Map<String, Object> messages(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChatRepository.Message m : chat.recentMessages(limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.id());
            row.put("ts", m.ts());
            row.put("groupId", m.groupId());
            row.put("senderName", m.senderName());
            row.put("text", m.text());
            row.put("textLength", m.text() == null ? 0 : m.text().length());
            row.put("imageCount", m.imageCount());
            row.put("source", m.source());
            row.put("importId", m.importId());
            rows.add(row);
        }
        return Map.of("rows", rows, "count", rows.size());
    }

    /** 一条消息与它的图片元数据 */
    @GetMapping("/messages/{id}")
    public ResponseEntity<?> message(@PathVariable long id) {
        ChatRepository.Message m = chat.findMessage(id);
        if (m == null) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> images = new ArrayList<>();
        for (ChatRepository.Image img : chat.imagesOfMessage(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", img.id());
            row.put("md5", img.md5());
            row.put("mime", img.mime());
            row.put("bytes", img.bytes());
            row.put("caption", img.caption());
            images.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id());
        out.put("ts", m.ts());
        out.put("groupId", m.groupId());
        out.put("senderName", m.senderName());
        out.put("text", m.text());
        out.put("imageCount", m.imageCount());
        out.put("source", m.source());
        out.put("images", images);
        return ResponseEntity.ok(out);
    }

    /** 导入预览：只解析，不写库 */
    @PostMapping(value = "/import/preview", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> previewImport(@RequestParam(defaultValue = "chat.txt") String filename,
                                           @RequestBody String content) {
        try {
            return ResponseEntity.ok(imports.preview(filename, content));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 导入聊天文件；请求体就是文件原文 */
    @PostMapping(value = "/import", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> importFile(@RequestParam(defaultValue = "chat.txt") String filename,
                                        @RequestParam(defaultValue = "0") long groupId,
                                        @RequestBody String content) {
        try {
            return ResponseEntity.ok(imports.importFile(filename, content, groupId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[DISTILL] 导入失败：{}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/imports")
    public Map<String, Object> imports(@RequestParam(defaultValue = "50") int limit) {
        List<ChatRepository.ImportRow> rows = chat.recentImports(limit);
        return Map.of("rows", rows, "count", rows.size());
    }

    /** 分批预览：算批次数与 token 估算，不建任务 */
    @PostMapping("/jobs/preview")
    public ResponseEntity<?> previewJob(@RequestBody DistillJobService.JobRequest request) {
        try {
            return ResponseEntity.ok(jobs.preview(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 创建任务与批次 */
    @PostMapping("/jobs")
    public ResponseEntity<?> createJob(@RequestBody DistillJobService.JobRequest request) {
        try {
            long id = jobs.create(request);
            return ResponseEntity.ok(Map.of("ok", true, "id", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[DISTILL] 建任务失败：{}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/jobs")
    public Map<String, Object> jobs(@RequestParam(defaultValue = "50") int limit) {
        List<DistillJobRepository.Job> rows = jobs.jobs(limit);
        return Map.of("rows", rows, "count", rows.size());
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> job(@PathVariable long id) {
        DistillJobService.JobDetail detail = jobs.detail(id);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    /** 开始或继续一个任务 */
    @PostMapping("/jobs/{id}/start")
    public ResponseEntity<?> start(@PathVariable long id) {
        try {
            boolean started = worker.start(id);
            return ResponseEntity.ok(Map.of("ok", true, "started", started));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/jobs/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable long id) {
        worker.pause(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable long id) {
        try {
            boolean started = worker.retry(id);
            return ResponseEntity.ok(Map.of("ok", true, "started", started));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 自动蒸馏设置 + 采集群 */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        DistillSettingRepository.Settings s = auto.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", s);
        out.put("groups", capture.status().groups());
        out.put("providers", llm.providers());
        out.put("pendingMessages", chat.countAfter(s.lastMessageId(), capture.status().groups()));
        return out;
    }

    /** 保存触发条件与分批参数 */
    @PutMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, Object> body) {
        try {
            DistillSettingRepository.Settings old = auto.settings();
            DistillSettingRepository.Settings next = new DistillSettingRepository.Settings(
                    bool(body.get("autoEnabled")),
                    intOf(body.get("triggerMessages"), 0),
                    intOf(body.get("triggerImages"), 0),
                    intOf(body.get("batchMessages"), 80),
                    intOf(body.get("batchChars"), 10_000),
                    intOf(body.get("overlap"), 0),
                    intOf(body.get("maxGapMinutes"), 30),
                    bool(body.get("autoPublish")),
                    dblOf(body.get("minConfidence"), 0.75),
                    str(body.get("focus")) == null ? "" : str(body.get("focus")),
                    str(body.get("modelProvider")) == null ? "" : str(body.get("modelProvider")),
                    old.lastMessageId(), old.updatedAt());
            return ResponseEntity.ok(Map.of("ok", true, "settings", auto.save(next)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 立即蒸馏一次；忽略阈值，处理游标之后的新消息 */
    @PostMapping("/settings/run-now")
    public ResponseEntity<?> runNow() {
        try {
            return ResponseEntity.ok(Map.of("ok", true, "jobId", auto.runNow()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[DISTILL] 手动蒸馏失败：{}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 笔记列表 */
    @GetMapping("/notes")
    public Map<String, Object> notes(@RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "0") long jobId,
                                     @RequestParam(defaultValue = "100") int limit) {
        List<DistillNoteRepository.Note> rows = notes.list(status, jobId, limit);
        return Map.of("rows", rows, "count", rows.size());
    }

    /** 笔记详情：正文 + 来源 + 图片 */
    @GetMapping("/notes/{id}")
    public ResponseEntity<?> note(@PathVariable long id) {
        DistillNoteService.Detail detail = notes.detail(id);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    /** 编辑标题 / 别名 / 分类 / 正文 / 置信度 */
    @PutMapping("/notes/{id}")
    public ResponseEntity<?> updateNote(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            var note = notes.update(id, str(body.get("title")), str(body.get("aliases")),
                    str(body.get("category")), str(body.get("body")), dbl(body.get("confidence")));
            return note == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(note);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[DISTILL] 保存笔记失败：{}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/notes/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable long id) {
        try {
            var note = notes.publish(id);
            return note == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(note);
        } catch (Exception e) {
            log.warn("[DISTILL] 发布笔记失败：{}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/notes/{id}/unpublish")
    public ResponseEntity<?> unpublish(@PathVariable long id) {
        var note = notes.unpublish(id);
        return note == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(note);
    }

    @DeleteMapping("/notes/{id}")
    public ResponseEntity<?> deleteNote(@PathVariable long id) {
        boolean ok = notes.delete(id);
        return ok ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.notFound().build();
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
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = imageStore.read(meta.path());
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, meta.mime() == null || meta.mime().isBlank()
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE : meta.mime())
                .body(bytes);
    }
}
