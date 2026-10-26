package com.example.qqbot.admin;

import com.example.qqbot.voice.VoiceAsset;
import com.example.qqbot.voice.VoiceException;
import com.example.qqbot.voice.VoiceLibrary;
import com.example.qqbot.voice.SpeechSynthesizer;
import com.example.qqbot.voice.VoicePolicy;
import com.example.qqbot.voice.VoiceSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 语音素材库的管理接口 —— 上传、试听、试发、删除。
 *
 * <p>和轮播图一样：音频走 {@code multipart/form-data}，其余字段是普通表单值。
 * 校验与落盘在 {@link VoiceLibrary} 里，这里只把异常翻成人话。
 *
 * <p>认证由 {@link AdminAuthFilter} 统一拦在 {@code /admin/api/**} 前面，
 * 所以试听地址虽然是裸 URL，未登录也拿不到。
 */
@RestController
@RequestMapping("/admin/api/voice")
public class VoiceAdminController {

    private static final Logger log = LoggerFactory.getLogger(VoiceAdminController.class);

    private final VoiceLibrary library;
    private final VoiceSender sender;
    private final VoicePolicy policy;
    private final SpeechSynthesizer synthesizer;

    public VoiceAdminController(VoiceLibrary library, VoiceSender sender, VoicePolicy policy,
                                SpeechSynthesizer synthesizer) {
        this.library = library;
        this.sender = sender;
        this.policy = policy;
        this.synthesizer = synthesizer;
    }

    /** 清单 + 上限，面板要显示"最多多大 / 多长" */
    @GetMapping("")
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("items", library.list().stream().map(VoiceAdminController::view).toList());
        out.put("maxSizeKb", 8192);
        out.put("maxSeconds", policy.getMaxSeconds());
        out.put("enabled", policy.isEnabled());
        return out;
    }

    /** 上传一段音频 */
    @PostMapping("")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "title", required = false) String title,
                                                      @RequestParam(value = "aliases", required = false) String aliases,
                                                      @RequestParam(value = "tags", required = false) String tags) {
        try {
            VoiceLibrary.Uploaded saved = library.upload(file.getOriginalFilename(), file.getContentType(),
                    file.getBytes(), title, aliases, tags);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("duplicate", saved.duplicate());
            out.put("item", view(saved.asset()));
            return ResponseEntity.ok(out);
        } catch (VoiceException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.warn("[VOICE] 上传读写失败：{}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "读取上传文件失败：" + e.getMessage()));
        }
    }

    /** 试听：直接把原始音频流回去，浏览器 audio 标签能用 */
    @GetMapping("/{id}/audio")
    public ResponseEntity<byte[]> audio(@PathVariable long id) {
        VoiceAsset asset = library.get(id);
        if (asset == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            MediaType type = MediaType.parseMediaType(asset.mime() == null || asset.mime().isBlank()
                    ? "audio/mpeg" : asset.mime());
            return ResponseEntity.ok().contentType(type).body(library.audio(id));
        } catch (VoiceException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** 改名 / 改别名 / 改标签 / 启停 */
    @PostMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String title = b.containsKey("title") ? str(b.get("title")) : null;
        String aliases = b.containsKey("aliases") ? str(b.get("aliases")) : null;
        String tags = b.containsKey("tags") ? str(b.get("tags")) : null;
        Boolean enabled = b.containsKey("enabled") ? Boolean.valueOf(String.valueOf(b.get("enabled"))) : null;

        VoiceAsset updated = library.update(id, title, aliases, tags, enabled);
        if (updated == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "没有这条语音"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("item", view(updated));
        return ResponseEntity.ok(out);
    }

    /** 试发：立刻发到指定群，用来确认"听起来对不对" */
    @PostMapping("/{id}/send")
    public ResponseEntity<Map<String, Object>> send(@PathVariable long id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        VoiceAsset asset = library.get(id);
        if (asset == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "没有这条语音"));
        }
        long groupId = 0;
        if (body != null && body.get("groupId") instanceof Number n) {
            groupId = n.longValue();
        }
        if (groupId <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 groupId"));
        }
        try {
            VoiceSender.Sent sent = sender.sendToGroup(groupId, library.pathOf(asset));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("messageId", sent.messageId());
            out.put("seconds", sent.seconds());
            return ResponseEntity.ok(out);
        } catch (VoiceException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除，连磁盘文件一起 */
    @PostMapping("/{id}/delete")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        VoiceAsset gone = library.delete(id);
        if (gone == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "没有这条语音"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("items", library.list().stream().map(VoiceAdminController::view).toList());
        return ResponseEntity.ok(out);
    }

    // ==================== TTS ====================

    /** 可选的声线，给下拉框 */
    @GetMapping("/tts/voices")
    public Map<String, Object> ttsVoices() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("enabled", synthesizer.isAvailable());
        out.put("voices", synthesizer.voices());
        out.put("default", synthesizer.defaultVoice());
        return out;
    }

    /** 试听：合成后直接回音频流，不落素材库，也不发群 */
    @PostMapping("/tts")
    public ResponseEntity<?> ttsPreview(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String text = str(b.get("text"));
        if (text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "先说点什么"));
        }
        try {
            byte[] audio = synthesizer.synthesize(text, str(b.get("voice")));
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg")).body(audio);
        } catch (VoiceException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 合成并直接发到群，用来确认"听起来对不对" */
    @PostMapping("/tts/send")
    public ResponseEntity<Map<String, Object>> ttsSend(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String text = str(b.get("text"));
        long groupId = b.get("groupId") instanceof Number n ? n.longValue() : 0L;
        if (text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "先说点什么"));
        }
        if (groupId <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 groupId"));
        }
        try {
            byte[] audio = synthesizer.synthesize(text, str(b.get("voice")));
            VoiceSender.Sent sent = sender.sendToGroup(groupId, audio, "mp3", null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("messageId", sent.messageId());
            out.put("seconds", sent.seconds());
            return ResponseEntity.ok(out);
        } catch (VoiceException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 工具 ====================

    private static Map<String, Object> view(VoiceAsset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("title", a.title());
        m.put("aliases", a.aliases());
        m.put("tags", a.tags());
        m.put("mime", a.mime());
        m.put("sizeKb", Math.round(a.sizeBytes() / 1024.0));
        m.put("durationMs", a.durationMs());
        m.put("durationText", String.format(Locale.ROOT, "%.1f 秒", a.durationMs() / 1000.0));
        m.put("enabled", a.enabled());
        m.put("createdAt", a.createdAt());
        m.put("audioUrl", "/admin/api/voice/" + a.id() + "/audio");
        return m;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
