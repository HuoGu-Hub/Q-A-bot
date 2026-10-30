package com.example.qqbot.prompt;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.example.qqbot.web.ApiException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
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
    public PromptList list() {
        return new PromptList(store.isAvailable(),
                store.listAll().stream().map(PromptView::of).toList());
    }

    public record PromptList(boolean available, List<PromptView> prompts) {
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        if (!store.delete(id)) {
            throw ApiException.notFound("没有这条提示");
        }
        return ResponseEntity.noContent().build();
    }
}
