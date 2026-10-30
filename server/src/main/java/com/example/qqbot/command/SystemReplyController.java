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
    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(SystemReplyStore.LIST, view(SystemReplyStore.LIST, catalog.listText()));
        out.put(SystemReplyStore.HELP, view(SystemReplyStore.HELP, catalog.helpText()));
        return out;
    }

    @PostMapping("")
    public ResponseEntity<Map<String, Object>> save(@RequestBody SystemReplyRequest body) {
        if (body.key() == null || body.key().isBlank()) {
            throw ApiException.badRequest("需要 key");
        }
        try {
            store.save(body.key(), body.content() == null ? "" : body.content());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private Map<String, Object> view(String key, String defaultContent) {
        Map<String, Object> item = new LinkedHashMap<>();
        String content = store.get(key);
        item.put("content", content);
        item.put("defaultContent", defaultContent);
        return item;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record SystemReplyRequest(String key, String content) {
    }
}
