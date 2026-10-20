package com.example.qqbot.command;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 板块管理接口（管理后台专用）。
 *
 * <p>认证由 {@link com.example.qqbot.admin.AdminAuthFilter} 统一拦在前面。
 * 校验与保存规则在 {@link BoardAdminService}，这里只做 HTTP 形状。
 */
@RestController
@RequestMapping("/admin/api/boards")
public class BoardController {

    private final BoardAdminService service;

    public BoardController(BoardAdminService service) {
        this.service = service;
    }

    /** 全部板块（含停用的） */
    @GetMapping("")
    public Map<String, Object> list() {
        return Map.of("boards", service.list());
    }

    /** 新建或更新。保存后把完整的一条回给前端，省一次列表请求 */
    @PostMapping("")
    public ResponseEntity<Map<String, Object>> save(@RequestBody BoardAdminService.BoardInput body) {
        try {
            return ResponseEntity.ok(Map.of("ok", true, "board", service.save(body)));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@RequestBody Map<String, Object> body) {
        try {
            service.toggle(str(body.get("path")), !Boolean.FALSE.equals(body.get("enabled")));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        try {
            service.delete(str(body.get("path")));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
