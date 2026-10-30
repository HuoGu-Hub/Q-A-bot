package com.example.qqbot.admin;

import com.example.qqbot.persistence.AdminAuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台账号管理。
 *
 * <p>只有「管理」角色能进 —— 这条在 {@link AdminAuthFilter#requiredRole} 里统一判，
 * 本类不重复写权限判断，避免两处规则漂移。
 */
@RestController
@RequestMapping("/admin/api/users")
public class AdminUsersController {

    private final AdminUserService users;
    private final AdminAuditRepository audit;
    private final AdminAuth auth;

    public AdminUsersController(AdminUserService users, AdminAuditRepository audit, AdminAuth auth) {
        this.users = users;
        this.audit = audit;
        this.auth = auth;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return users.list().stream().map(AdminUsersController::dto).toList();
    }

    /** 最近的后台操作记录，用来回答"这条知识是谁改的" */
    @GetMapping("/audit")
    public List<AdminAuditRepository.Entry> audit(@RequestParam(defaultValue = "100") int limit) {
        return audit.recent(limit);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        try {
            AdminUser u = users.create(
                    str(body, "username"),
                    str(body, "displayName"),
                    str(body, "password"),
                    AdminRole.parseStrict(str(body, "role")));
            return ResponseEntity.ok(dto(u));
        } catch (IllegalArgumentException e) {
            return bad(e);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody Map<String, Object> body) {
        try {
            AdminRole role = AdminRole.parseStrict(str(body, "role"));
            Boolean enabled = body.get("enabled") instanceof Boolean b ? b : null;
            AdminUser current = users.find(id).orElseThrow(
                    () -> new IllegalArgumentException("账号不存在：" + id));
            AdminUser u = users.update(id, str(body, "displayName"),
                    role == null ? current.role() : role,
                    enabled == null || enabled);
            auth.invalidateUser(id);
            return ResponseEntity.ok(dto(u));
        } catch (IllegalArgumentException e) {
            return bad(e);
        }
    }

    @PostMapping("/{id}/password")
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable long id,
                                                             @RequestBody Map<String, Object> body) {
        try {
            users.changePassword(id, str(body, "password"));
            auth.invalidateUser(id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id,
                                                      HttpServletRequest request) {
        AdminPrincipal me = AdminAuthFilter.current(request);
        long actorId = me == null ? -1 : me.id();
        try {
            users.delete(id, actorId);
            auth.invalidateUser(id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e);
        }
    }

    private static ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage() == null ? "参数不合法" : e.getMessage()));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Map<String, Object> dto(AdminUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("username", u.username());
        m.put("displayName", u.display());
        m.put("role", u.role().id());
        m.put("roleLabel", u.role().label());
        m.put("enabled", u.enabled());
        m.put("createdAt", u.createdAt());
        m.put("lastLoginAt", u.lastLoginAt());
        return m;
    }
}
