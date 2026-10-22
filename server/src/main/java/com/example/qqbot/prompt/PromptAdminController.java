package com.example.qqbot.prompt;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 补充提示的后台接口。
 *
 * <p>只给「编辑 /prompt」弹窗用：列出全部群提示与个人提示，并按编号删除。
 * 新增提示仍然走群里的 /prompt /create，不在这里重复一套表单。
 */
@RestController
@RequestMapping("/admin/api/prompts")
public class PromptAdminController {

    private final PromptStore store;

    public PromptAdminController(PromptStore store) {
        this.store = store;
    }

    public record PromptView(long id, String scope, long groupId, long userId, String content) {
        static PromptView of(PromptStore.Prompt prompt) {
            return new PromptView(prompt.id(), prompt.scope().code(), prompt.groupId(),
                    prompt.userId(), prompt.content());
        }
    }

    @GetMapping("")
    public Map<String, Object> list() {
        List<PromptView> views = store.listAll().stream().map(PromptView::of).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", store.isAvailable());
        out.put("prompts", views);
        return out;
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        Object raw = body.get("id");
        long id;
        try {
            id = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要提示编号"));
        }
        return store.delete(id)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(404).body(Map.of("error", "没有这条提示"));
    }
}
