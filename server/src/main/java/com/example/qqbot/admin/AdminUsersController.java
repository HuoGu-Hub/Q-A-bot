package com.example.qqbot.admin;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.example.qqbot.web.ApiException;

import com.example.qqbot.persistence.AdminAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@Tag(name = "Admin")
@SecurityRequirement(name = "adminSession")
@RestController
@RequestMapping("/admin/api/users")
public class AdminUsersController {

    private final AdminUserService users;
    private final AdminAuditRepository audit;
    private final AdminAuth auth;
    private final AdminQqCodeService qqCodes;
    private final AdminTrustedDeviceService devices;
    private final AdminRoleScopeService scopes;
    private final ObjectMapper mapper;

    public AdminUsersController(AdminUserService users, AdminAuditRepository audit, AdminAuth auth,
                                AdminQqCodeService qqCodes, AdminTrustedDeviceService devices,
                                AdminRoleScopeService scopes, ObjectMapper mapper) {
        this.users = users;
        this.audit = audit;
        this.auth = auth;
        this.qqCodes = qqCodes;
        this.devices = devices;
        this.scopes = scopes;
        this.mapper = mapper;
    }

    @GetMapping
    public List<AdminUserView> list() {
        return users.list().stream().map(AdminUsersController::dto).toList();
    }

    /**
     * 每个角色当前能进哪几块。
     *
     * <p>把区域清单一起返回：前端不该自己维护一份 id 与中文名，否则加一个区域要改两处，
     * 迟早出现"页面上有个勾选框，后端却不认识它"。
     */
    @GetMapping("/scopes")
    public ScopesView scopes() {
        return currentScopes();
    }

    /** 保存一套范围。只影响判定，不动任何账号本身 */
    @PutMapping("/scopes")
    public ScopesView saveScopes(@RequestBody ScopesRequest body, HttpServletRequest request) {
        try {
            scopes.apply(body == null ? null : body.scopes());
            AdminPrincipal me = AdminAuthFilter.current(request);
            if (me != null) {
                audit.record(me.id(), me.username(), "role-scope", "PUT",
                        "/admin/api/users/scopes", null, "");
            }
            return currentScopes();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private ScopesView currentScopes() {
        List<AreaView> areas = java.util.Arrays.stream(AdminArea.values())
                .map(a -> new AreaView(a.id(), a.label()))
                .toList();
        Map<String, List<String>> byRole = new LinkedHashMap<>();
        for (AdminRole role : AdminRole.values()) {
            byRole.put(role.id(), scopes.areasOf(role).stream().map(AdminArea::id).toList());
        }
        return new ScopesView(areas, byRole);
    }

    /** 最近的后台操作记录，用来回答"这条知识是谁改的" */
    @GetMapping("/audit")
    public List<AdminAuditRepository.Entry> audit(@RequestParam(defaultValue = "100") int limit) {
        return audit.recent(limit);
    }

    /**
     * 创建账号第一步：先给待注册 QQ 发欢迎消息，再给当前管理员发验证码。
     *
     * <p>待创建数据只存密码哈希，不存明文；验证码通过后才落库。
     */
    @PostMapping("/request")
    public ResponseEntity<CreateCodeResult> requestCreate(@RequestBody CreateUserRequest body,
                                                             HttpServletRequest request) {
        AdminPrincipal me = AdminAuthFilter.current(request);
        if (me == null) {
            throw ApiException.unauthorized("未登录");
        }
        try {
            String username = body.username();
            String displayName = body.displayName();
            String password = body.password();
            String qq = body.qq();
            AdminRole role = AdminRole.parseStrict(body.role());
            AdminRole target = role == null ? AdminRole.VIEWER : role;
            String q = qq == null ? "" : qq.trim();
            if (users.usernameExists(username)) {
                throw new IllegalArgumentException("账号名已存在：" + username);
            }
            if (!q.isBlank() && users.qqExists(q)) {
                throw new IllegalArgumentException("这个 QQ 已经绑了其他账号");
            }
            if (q.isBlank() && target.requiresQq()) {
                throw new IllegalArgumentException("「" + target.label() + "」账号必须填 QQ");
            }
            String hash = users.hashPassword(password);
            // 只读账号可以没有 QQ —— 没有 QQ 就没有"告知对象"，
            // 这一步只是通知，不该因此挡住开户
            if (!q.isBlank()) {
                qqCodes.sendNotice(q, "qqbot 后台正在为你创建账号，账号名：" + username
                        + "。如果不是你本人申请，请忽略这条消息。");
            }
            String payload = mapper.writeValueAsString(Map.of(
                    "username", username,
                    "displayName", displayName == null ? "" : displayName,
                    "passwordHash", hash,
                    "role", target.id(),
                    "qq", q));
            AdminQqCodeService.Issued issued = qqCodes.issue(
                    me.id(), me.qq(), AdminQqCodeService.PURPOSE_CREATE_USER, payload);
            audit.record(me.id(), me.username(), "user-create-code", "POST",
                    "/admin/api/users/request", "qq=" + issued.qqMasked(), "");
            return ResponseEntity.ok(new CreateCodeResult(
                    issued.challengeId(), issued.qqMasked()));
        } catch (IllegalArgumentException | AdminQqCodeService.CodeException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (Exception e) {
            throw ApiException.badRequest("创建请求失败：" + e.getMessage());
        }
    }

    @PostMapping("/confirm")
    public ResponseEntity<AdminUserView> confirmCreate(@RequestBody ConfirmUserRequest body,
                                                             HttpServletRequest request) {
        AdminPrincipal me = AdminAuthFilter.current(request);
        if (me == null) {
            throw ApiException.unauthorized("未登录");
        }
        try {
            AdminQqCodeService.Consumed consumed = qqCodes.consume(
                    body.challengeId(), body.code(),
                    AdminQqCodeService.PURPOSE_CREATE_USER);
            if (consumed.userId() != me.id()) {
                throw new IllegalArgumentException("验证码不属于当前管理员");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = mapper.readValue(consumed.payload(), Map.class);
            AdminUser u = users.createWithHash(
                    String.valueOf(payload.get("username")),
                    String.valueOf(payload.getOrDefault("displayName", "")),
                    String.valueOf(payload.get("passwordHash")),
                    AdminRole.parseStrict(String.valueOf(payload.get("role"))),
                    String.valueOf(payload.get("qq")));
            if (!u.qq().isBlank()) {
                qqCodes.sendNotice(u.qq(), "qqbot 后台账号已创建，账号名：" + u.username()
                        + "。请用管理员给你的初始密码登录，并立即修改密码。");
            }
            audit.record(me.id(), me.username(), "user-create", "POST",
                    "/admin/api/users/confirm", "target=" + u.username(), "");
            return ResponseEntity.ok(dto(u));
        } catch (IllegalArgumentException | AdminQqCodeService.CodeException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (Exception e) {
            throw ApiException.badRequest("创建失败：" + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<AdminUserView> update(@PathVariable long id,
                                                      @RequestBody UpdateUserRequest body) {
        try {
            AdminRole role = AdminRole.parseStrict(body.role());
            Boolean enabled = body.enabled();
            AdminUser current = users.find(id).orElseThrow(
                    () -> new IllegalArgumentException("账号不存在：" + id));
            AdminUser u = users.update(id, body.displayName(),
                    role == null ? current.role() : role,
                    enabled == null || enabled);
            auth.invalidateUser(id);
            devices.revokeAll(id);
            return ResponseEntity.ok(dto(u));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{id}/password")
    public ResponseEntity<Void> setPassword(@PathVariable long id,
                                                             @RequestBody ResetPasswordRequest body) {
        try {
            users.changePassword(id, body.password());
            auth.invalidateUser(id);
            devices.revokeAll(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id,
                                                      HttpServletRequest request) {
        AdminPrincipal me = AdminAuthFilter.current(request);
        long actorId = me == null ? -1 : me.id();
        try {
            users.delete(id, actorId);
            auth.invalidateUser(id);
            devices.revokeAll(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private static AdminUserView dto(AdminUser u) {
        return new AdminUserView(u.id(), u.username(), u.display(), u.role().id(),
                u.role().label(), u.enabled(), u.qq(), u.qqVerified(),
                u.createdAt(), u.lastLoginAt());
    }

    public record AdminUserView(long id, String username, String displayName, String role,
                                String roleLabel, boolean enabled, String qq,
                                boolean qqVerified, String createdAt, String lastLoginAt) {
    }

    public record AreaView(String id, String label) {
    }

    public record ScopesView(List<AreaView> areas, Map<String, List<String>> scopes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record ScopesRequest(Map<String, List<String>> scopes) {
    }

    public record CreateCodeResult(String challengeId, String adminQq) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record CreateUserRequest(String username, String displayName, String password,
                                    String qq, String role) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record ConfirmUserRequest(String challengeId, String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record UpdateUserRequest(String displayName, String role, Boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)

    public record ResetPasswordRequest(String password) {
    }
}
