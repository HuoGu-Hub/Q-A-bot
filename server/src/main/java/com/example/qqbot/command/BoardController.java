package com.example.qqbot.command;

import com.example.qqbot.web.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 板块管理接口（管理后台专用）。
 *
 * <p>认证由 {@link com.example.qqbot.admin.AdminAuthFilter} 统一拦在前面。
 * 校验与保存规则在 {@link BoardAdminService}，这里只做 HTTP 形状。
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/boards")
public class BoardController {

    private final BoardAdminService service;

    public BoardController(BoardAdminService service) {
        this.service = service;
    }

    /** 全部板块（含停用的） */
    @GetMapping("")
    public BoardList list() {
        return new BoardList(service.list());
    }

    /** 新建或更新。保存后把完整的一条回给前端，省一次列表请求 */
    @PostMapping("")
    public ResponseEntity<BoardSaved> save(@RequestBody BoardAdminService.BoardInput body) {
        try {
            return ResponseEntity.ok(new BoardSaved(service.save(body)));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> toggle(@PathVariable long id,
                                       @RequestBody ToggleBoardRequest body) {
        BoardAdminService.BoardView board = find(id);
        boolean enabled = body.enabled() == null ? !board.enabled() : body.enabled();
        try {
            service.toggle(board.path(), enabled);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        BoardAdminService.BoardView board = find(id);
        try {
            service.delete(board.path());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private BoardAdminService.BoardView find(long id) {
        return service.list().stream()
                .filter(b -> b.id() == id)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("没有这个板块"));
    }

    public record BoardList(List<BoardAdminService.BoardView> boards) {
    }

    public record BoardSaved(BoardAdminService.BoardView board) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ToggleBoardRequest(Boolean enabled) {
    }
}
