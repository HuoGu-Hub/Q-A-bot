package com.example.qqbot.distill;

import com.example.qqbot.persistence.ChatRepository;
import com.example.qqbot.persistence.DistillJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final ChatRepository chat;
    private final DistillJobRepository jobRepo;
    private final ChatImageStore imageStore;

    public DistillAdminController(ChatCaptureService capture, ChatImportService imports,
                                  DistillJobService jobs, ChatRepository chat,
                                  DistillJobRepository jobRepo, ChatImageStore imageStore) {
        this.capture = capture;
        this.imports = imports;
        this.jobs = jobs;
        this.chat = chat;
        this.jobRepo = jobRepo;
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
