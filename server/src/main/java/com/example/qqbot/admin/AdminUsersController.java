package com.example.qqbot.admin;

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
@RestController
@RequestMapping("/admin/api/users")
public class AdminUsersController {

    private final AdminUserService users;
    private final AdminAuditRepository audit;
    private final AdminAuth auth;
    private final AdminQqCodeService qqCodes;
    private final AdminTrustedDeviceService devices;
    private final ObjectMapper mapper;

    public AdminUsersController(AdminUserService users, AdminAuditRepository audit, AdminAuth auth,
                                AdminQqCodeService qqCodes, AdminTrustedDeviceService devices,
                                ObjectMapper mapper) {
        this.users = users;
        this.audit = audit;
        this.auth = auth;
        this.qqCodes = qqCodes;
        this.devices = devices;
        this.mapper = mapper;
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

    /**
     * 创建账号第一步：先给待注册 QQ 发欢迎消息，再给当前管理员发验证码。
     *
     * <p>待创建数据只存密码哈希，不存明文；验证码通过后才落库。
     */
    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> requestCreate(@RequestBody Map<String, Object> body,
                                                             HttpServletRequest request) {
        AdminPrincipal me = AdminAuthFilter.current(request);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        try {
            String username = str(body, "username");
            String displayName = str(body, "displayName");
            String password = str(body, "password");
            String qq = str(body, "qq");
            AdminRole role = AdminRole.parseStrict(str(body, "role"));
            if (users.usernameExists(username)) {
                throw new IllegalArgumentException("账号名已存在：" + username);
            }
            if (users.qqExists(qq)) {
                throw new IllegalArgumentException("这个 QQ 已经绑了其他账号");
            }
            String hash = users.hashPassword(password);
            qqCodes.sendNotice(qq, "qqbot 后台正在为你创建账号，账号名：" + username
                    + "。如果不是你本人申请，请忽略这条消息。");
            String payload = mapper.writeValueAsString(Map.of(
                    "username", username,
                    "displayName", displayName == null ? "" : displayName,
                    "passwordHash", hash,
                    "role", role == null ? AdminRole.VIEWER.id() : role.id(),
                    "qq", qq));
            AdminQqCodeService.Issued issued = qqCodes.issue(
                    me.id(), me.qq(), AdminQqCodeService.PURPOSE_CREATE_USER, payload);
            audit.record(me.id(), me.username(), "user-create-code", "POST",
                    "/admin/api/users/request", "qq=" + issued.qqMasked(), "");
            return ResponseEntity.ok(Map.of("ok", true,
                    "challengeId", issued.challengeId(), "adminQq", issued.qqMasked()));
        } catch (IllegalArgumentException | AdminQqCodeService.CodeException e) {
            return bad(new IllegalArgumentException(e.getMessage()));
        } catch (Exception e) {
            return bad(new IllegalArgumentException("创建请求失败：" + e.getMessage()));
        }
    }

    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirmCreate(@RequestBody Map<String, Object> body,
                                                             HttpServletRequest request) {
        AdminPrincipal me = AdminAuthFilter.current(request);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        try {
            AdminQqCodeService.Consumed consumed = qqCodes.consume(
                    str(body, "challengeId"), str(body, "code"),
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
            qqCodes.sendNotice(u.qq(), "qqbot 后台账号已创建，账号名：" + u.username()
                    + "。请用管理员给你的初始密码登录，并立即修改密码。");
            audit.record(me.id(), me.username(), "user-create", "POST",
                    "/admin/api/users/confirm", "target=" + u.username(), "");
            return ResponseEntity.ok(dto(u));
        } catch (IllegalArgumentException | AdminQqCodeService.CodeException e) {
            return bad(new IllegalArgumentException(e.getMessage()));
        } catch (Exception e) {
            return bad(new IllegalArgumentException("创建失败：" + e.getMessage()));
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
            devices.revokeAll(id);
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
            devices.revokeAll(id);
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
            devices.revokeAll(id);
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
        m.put("qq", u.qq());
        m.put("qqVerified", u.qqVerified());
        m.put("createdAt", u.createdAt());
        m.put("lastLoginAt", u.lastLoginAt());
        return m;
    }
}
