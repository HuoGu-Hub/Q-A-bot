package com.example.qqbot.command;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.web.ApiException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 全局 /list、/help 的回复编辑。空 content = 使用自动生成版本。 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/system-replies")
public class SystemReplyController {

    private final SystemReplyStore store;
    private final CommandCatalog catalog;

    public SystemReplyController(SystemReplyStore store, CommandCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    @GetMapping("")
    public SystemReplyList get() {
        return new SystemReplyList(
                view(SystemReplyStore.LIST, catalog.listText()),
                view(SystemReplyStore.HELP, catalog.helpText()));
    }

    @PostMapping("")
    public ResponseEntity<Void> save(@RequestBody SystemReplyRequest body) {
        if (body.key() == null || body.key().isBlank()) {
            throw ApiException.badRequest("需要 key");
        }
        try {
            store.save(body.key(), body.content() == null ? "" : body.content());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private SystemReplyItem view(String key, String defaultContent) {
        return new SystemReplyItem(store.get(key), defaultContent);
    }

    public record SystemReplyItem(String content, String defaultContent) {
    }

    public record SystemReplyList(SystemReplyItem list, SystemReplyItem help) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record SystemReplyRequest(String key, String content) {
    }
}
