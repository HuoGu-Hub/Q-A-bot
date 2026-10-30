package com.example.qqbot.admin;

import com.example.qqbot.voice.SpeechSynthesizer;
import com.example.qqbot.voice.VoiceAsset;
import com.example.qqbot.voice.VoiceException;
import com.example.qqbot.voice.VoiceLibrary;
import com.example.qqbot.voice.VoicePolicy;
import com.example.qqbot.voice.VoiceSender;
import com.example.qqbot.voice.VoiceTriggerService;
import com.example.qqbot.web.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 语音素材库的管理接口 —— 上传、试听、试发、删除。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/voice")
public class VoiceAdminController {

    private static final Logger log = LoggerFactory.getLogger(VoiceAdminController.class);

    private final VoiceLibrary library;
    private final VoiceSender sender;
    private final VoicePolicy policy;
    private final SpeechSynthesizer synthesizer;
    private final VoiceTriggerService triggers;

    public VoiceAdminController(VoiceLibrary library, VoiceSender sender, VoicePolicy policy,
                                SpeechSynthesizer synthesizer, VoiceTriggerService triggers) {
        this.library = library;
        this.sender = sender;
        this.policy = policy;
        this.synthesizer = synthesizer;
        this.triggers = triggers;
    }

    /** 清单 + 上限，面板要显示"最多多大 / 多长" */
    @GetMapping("")
    public VoiceList list() {
        return new VoiceList(views(library.list()), 8192,
                policy.getMaxSeconds(), policy.isEnabled());
    }

    /** 上传一段音频 */
    @PostMapping("")
    public ResponseEntity<UploadResult> upload(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "title", required = false) String title,
                                               @RequestParam(value = "aliases", required = false) String aliases,
                                               @RequestParam(value = "tags", required = false) String tags) {
        try {
            VoiceLibrary.Uploaded saved = library.upload(file.getOriginalFilename(), file.getContentType(),
                    file.getBytes(), title, aliases, tags);
            return ResponseEntity.ok(new UploadResult(saved.duplicate(), view(saved.asset())));
        } catch (VoiceException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (IOException e) {
            log.warn("[VOICE] 上传读写失败：{}", e.getMessage());
            throw ApiException.internal("读取上传文件失败：" + e.getMessage());
        }
    }

    /** 试听：直接把原始音频流回去，浏览器 audio 标签能用 */
    @GetMapping("/{id}/audio")
    public ResponseEntity<byte[]> audio(@PathVariable long id) {
        VoiceAsset asset = library.get(id);
        if (asset == null) {
            throw ApiException.notFound("资源不存在");
        }
        try {
            MediaType type = MediaType.parseMediaType(asset.mime() == null || asset.mime().isBlank()
                    ? "audio/mpeg" : asset.mime());
            return ResponseEntity.ok().contentType(type).body(library.audio(id));
        } catch (VoiceException e) {
            throw ApiException.internal(e.getMessage());
        }
    }

    /** 改名 / 改别名 / 改标签 / 启停 */
    @PatchMapping("/{id}")
    public ResponseEntity<ItemResult> update(@PathVariable long id,
                                             @RequestBody(required = false) UpdateVoiceRequest body) {
        String title = body == null ? null : body.title();
        String aliases = body == null ? null : body.aliases();
        String tags = body == null ? null : body.tags();
        Boolean enabled = body == null ? null : body.enabled();

        VoiceAsset updated = library.update(id, title, aliases, tags, enabled);
        if (updated == null) {
            throw ApiException.notFound("没有这条语音");
        }
        return ResponseEntity.ok(new ItemResult(view(updated)));
    }

    /** 试发：立刻发到指定群，用来确认"听起来对不对" */
    @PostMapping("/{id}/actions/send")
    public ResponseEntity<SendResult> send(@PathVariable long id,
                                           @RequestBody(required = false) SendVoiceRequest body) {
        VoiceAsset asset = library.get(id);
        if (asset == null) {
            throw ApiException.notFound("没有这条语音");
        }
        long groupId = body == null || body.groupId() == null ? 0L : body.groupId();
        if (groupId <= 0) {
            throw ApiException.badRequest("需要 groupId");
        }
        try {
            VoiceSender.Sent sent = sender.sendToGroup(groupId, library.pathOf(asset));
            return ResponseEntity.ok(new SendResult(sent.messageId(), sent.seconds()));
        } catch (VoiceException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 删除，连磁盘文件一起 */
    @DeleteMapping("/{id}")
    public ResponseEntity<ItemsResult> delete(@PathVariable long id) {
        VoiceAsset gone = library.delete(id);
        if (gone == null) {
            throw ApiException.notFound("没有这条语音");
        }
        return ResponseEntity.ok(new ItemsResult(views(library.list())));
    }

    // ==================== 触发词 ====================

    /** 规则列表。关键词只决定"要不要语音"，不绑定内容 */
    @GetMapping("/triggers")
    public TriggerList listTriggers() {
        return new TriggerList(triggerViews(triggers.rules()));
    }

    /** 新增关键词 */
    @PostMapping("/triggers")
    public ResponseEntity<TriggerResult> addTrigger(
            @RequestBody(required = false) CreateTriggerRequest body) {
        String keyword = body == null || body.keyword() == null ? "" : body.keyword().trim();
        if (keyword.isBlank()) {
            throw ApiException.badRequest("关键词不能为空");
        }
        int cooldown = body.cooldownSeconds() == null ? 120 : body.cooldownSeconds();
        String scope = body.groupScope() == null ? "" : body.groupScope();
        String note = body.note() == null ? "" : body.note();
        VoiceTriggerService.Rule added = triggers.add(keyword, cooldown, scope, note);
        if (added == null) {
            throw ApiException.badRequest("新增失败，关键词可能重复");
        }
        return ResponseEntity.ok(new TriggerResult(triggerView(added)));
    }

    /** 改冷却 / 作用域 / 备注 / 启停 */
    @PatchMapping("/triggers/{id}")
    public ResponseEntity<TriggerResult> updateTrigger(
            @PathVariable long id,
            @RequestBody(required = false) UpdateTriggerRequest body) {
        VoiceTriggerService.Rule old = triggers.rules().stream()
                .filter(r -> r.id() == id).findFirst().orElse(null);
        if (old == null) {
            throw ApiException.notFound("没有这条规则");
        }
        int cooldown = body == null || body.cooldownSeconds() == null
                ? old.cooldownSeconds() : body.cooldownSeconds();
        String scope = body == null || body.groupScope() == null ? old.groupScope() : body.groupScope();
        String note = body == null || body.note() == null ? old.note() : body.note();
        Boolean enabled = body == null || body.enabled() == null ? old.enabled() : body.enabled();
        VoiceTriggerService.Rule updated = triggers.update(id, cooldown, scope, note, enabled);
        if (updated == null) {
            throw ApiException.notFound("没有这条规则");
        }
        return ResponseEntity.ok(new TriggerResult(triggerView(updated)));
    }

    @DeleteMapping("/triggers/{id}")
    public ResponseEntity<TriggerList> deleteTrigger(@PathVariable long id) {
        if (!triggers.delete(id)) {
            throw ApiException.notFound("没有这条规则");
        }
        return ResponseEntity.ok(new TriggerList(triggerViews(triggers.rules())));
    }

    // ==================== TTS ====================

    /** 可选的声线，给下拉框 */
    @GetMapping("/tts/voices")
    public TtsVoices ttsVoices() {
        return new TtsVoices(synthesizer.isAvailable(), synthesizer.voices(),
                synthesizer.defaultVoice());
    }

    /** 试听：合成后直接回音频流，不落素材库，也不发群 */
    @PostMapping("/tts/previews")
    public ResponseEntity<byte[]> ttsPreview(@RequestBody(required = false) TtsRequest body) {
        String text = body == null || body.text() == null ? "" : body.text();
        if (text.isBlank()) {
            throw ApiException.badRequest("先说点什么");
        }
        try {
            byte[] audio = synthesizer.synthesize(text, body.voice());
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg")).body(audio);
        } catch (VoiceException e) {
            log.warn("[VOICE] TTS 试听失败：{}", e.getMessage());
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 合成并直接发到群 */
    @PostMapping("/tts/actions/send")
    public ResponseEntity<SendResult> ttsSend(@RequestBody(required = false) TtsSendRequest body) {
        String text = body == null || body.text() == null ? "" : body.text();
        long groupId = body == null || body.groupId() == null ? 0L : body.groupId();
        if (text.isBlank()) {
            throw ApiException.badRequest("先说点什么");
        }
        if (groupId <= 0) {
            throw ApiException.badRequest("需要 groupId");
        }
        try {
            byte[] audio = synthesizer.synthesize(text, body.voice());
            VoiceSender.Sent sent = sender.sendToGroup(groupId, audio, "mp3", null);
            return ResponseEntity.ok(new SendResult(sent.messageId(), sent.seconds()));
        } catch (VoiceException e) {
            log.warn("[VOICE] TTS 发送失败：{}", e.getMessage());
            throw ApiException.badRequest(e.getMessage());
        }
    }

    // ==================== 出参 ====================

    public record VoiceItem(long id, String title, String aliases, String tags, String mime,
                            long sizeKb, long durationMs, String durationText,
                            boolean enabled, String createdAt, String audioUrl) {
    }

    public record VoiceList(List<VoiceItem> items, int maxSizeKb, int maxSeconds, boolean enabled) {
    }

    public record UploadResult(boolean duplicate, VoiceItem item) {
    }

    public record ItemResult(VoiceItem item) {
    }

    public record ItemsResult(List<VoiceItem> items) {
    }

    public record SendResult(long messageId, double seconds) {
    }

    public record TriggerRuleView(long id, String keyword, int cooldownSeconds,
                                  String groupScope, boolean enabled, String note) {
    }

    public record TriggerList(List<TriggerRuleView> rules) {
    }

    public record TriggerResult(TriggerRuleView rule) {
    }

    public record TtsVoices(boolean enabled, List<String> voices,
                            @JsonProperty("default") String defaultVoice) {
    }

    // ==================== 工具 ====================

    private static VoiceItem view(VoiceAsset a) {
        return new VoiceItem(a.id(), a.title(), a.aliases(), a.tags(), a.mime(),
                Math.round(a.sizeBytes() / 1024.0), a.durationMs(),
                String.format(Locale.ROOT, "%.1f 秒", a.durationMs() / 1000.0),
                a.enabled(), a.createdAt(), "/admin/api/voice/" + a.id() + "/audio");
    }

    private static List<VoiceItem> views(List<VoiceAsset> assets) {
        List<VoiceItem> out = new ArrayList<>();
        for (VoiceAsset a : assets) {
            out.add(view(a));
        }
        return out;
    }

    private static TriggerRuleView triggerView(VoiceTriggerService.Rule r) {
        return new TriggerRuleView(r.id(), r.keyword(), r.cooldownSeconds(),
                r.groupScope(), r.enabled(), r.note());
    }

    private static List<TriggerRuleView> triggerViews(List<VoiceTriggerService.Rule> rules) {
        List<TriggerRuleView> out = new ArrayList<>();
        for (VoiceTriggerService.Rule r : rules) {
            out.add(triggerView(r));
        }
        return out;
    }

    // ==================== 入参 ====================

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UpdateVoiceRequest(String title, String aliases, String tags, Boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SendVoiceRequest(Long groupId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateTriggerRequest(String keyword, Integer cooldownSeconds,
                                       String groupScope, String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UpdateTriggerRequest(Integer cooldownSeconds, String groupScope,
                                       String note, Boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TtsRequest(String text, String voice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TtsSendRequest(String text, Long groupId, String voice) {
    }
}
