package com.example.qqbot.command;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.example.qqbot.web.ApiException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 板块记录的管理接口（管理后台专用）。
 *
 * <p>只读 + 撤销/恢复。记录内容属于群友，后台不改正文。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
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
    public RecordList list(@RequestParam String board,
                           @RequestParam(defaultValue = "0") long groupId,
                           @RequestParam(defaultValue = "false") boolean withDeleted,
                           @RequestParam(defaultValue = "100") int limit) {
        var records = service.list(board, groupId, withDeleted, limit);
        return new RecordList(board, records, records.size());
    }

    public record RecordList(String board,
                             List<BoardRecordAdminService.RecordView> records, int count) {
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        try {
            service.softDelete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable long id) {
        try {
            service.restore(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }
}
