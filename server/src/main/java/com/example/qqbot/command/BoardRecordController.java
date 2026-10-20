package com.example.qqbot.command;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 板块记录的管理接口（管理后台专用）。
 *
 * <p>只读 + 撤销/恢复。记录内容属于群友，后台不改正文。
 */
@RestController
@RequestMapping("/admin/api/board-records")
public class BoardRecordController {

    private final BoardRecordAdminService service;

    public BoardRecordController(BoardRecordAdminService service) {
        this.service = service;
    }

    /**
     * 某个板块的记录。
     *
     * @param board       板块名，必填
     * @param groupId     0 = 所有群
     * @param withDeleted 连已撤销的一起看
     */
    @GetMapping("")
    public Map<String, Object> list(@RequestParam String board,
                                    @RequestParam(defaultValue = "0") long groupId,
                                    @RequestParam(defaultValue = "false") boolean withDeleted,
                                    @RequestParam(defaultValue = "100") int limit) {
        var records = service.list(board, groupId, withDeleted, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("board", board);
        out.put("records", records);
        out.put("count", records.size());
        return out;
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        try {
            service.softDelete(longOf(body.get("id")));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/restore")
    public ResponseEntity<Map<String, Object>> restore(@RequestBody Map<String, Object> body) {
        try {
            service.restore(longOf(body.get("id")));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
