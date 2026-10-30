package com.example.qqbot.admin;

import com.example.qqbot.persistence.AdminAuditRepository;
import com.example.qqbot.persistence.AdminUserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 后台账号的业务层。
 *
 * <p>三条规则集中在这里，不散到 Controller：
 * <ol>
 *   <li>账号名与密码的格式校验</li>
 *   <li><b>永远至少留一个可用的管理员</b> —— 删除、降级、停用之前都检查。
 *       少了这条，一次误操作就能把所有人锁在门外，而且没有"救回来"的入口</li>
 *   <li>首个管理员从 {@code ADMIN_PASSWORD} 引导创建，老部署升级不用手工建库</li>
 * </ol>
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.-]{3,32}$");
    private static final Pattern QQ = Pattern.compile("^[1-9]\\d{4,11}$");
    private static final int MIN_PASSWORD = 8;

    private final AdminUserRepository users;
    private final AdminAuditRepository audit;
    private final AdminPolicy policy;

    /** 是否已有账号。每个请求都要问一次，所以缓存；建/删时刷新 */
    private volatile boolean hasUsers;

    public AdminUserService(AdminUserRepository users, AdminAuditRepository audit, AdminPolicy policy) {
        this.users = users;
        this.audit = audit;
        this.policy = policy;
    }

    @PostConstruct
    void init() {
        try {
            bootstrapFirstAdmin();
            hasUsers = users.countAll() > 0;
        } catch (Exception e) {
            log.warn("[ADMIN] 账号体系初始化失败，后台将不可用：{}", e.getMessage());
            hasUsers = false;
        }
    }

    /**
     * 空表且有 {@code ADMIN_PASSWORD} 时，用它建一个 {@code admin} 账号。
     *
     * <p>为什么保留这个口子：老部署升级上来时库里没有账号，
     * 不给引导就只能手工写库。建完之后 {@code ADMIN_PASSWORD} 不再参与登录 ——
     * 它只是"第一次的钥匙"，后续一切以账号表为准。
     */
    private void bootstrapFirstAdmin() {
        if (users.countAll() > 0) {
            return;
        }
        String seed = policy.getPassword();
        if (!StringUtils.hasText(seed)) {
            log.warn("[ADMIN] 还没有任何后台账号，且 ADMIN_PASSWORD 为空 —— 后台保持关闭（503）");
            return;
        }
        if (seed.length() < MIN_PASSWORD) {
            // 老密码可能很短，这里不拒绝，只在日志里提示一次
            log.warn("[ADMIN] ADMIN_PASSWORD 少于 {} 位，建议登录后改成更强的密码", MIN_PASSWORD);
        }
        String adminQq = policy.getAdminQq() == null ? "" : policy.getAdminQq().trim();
        long id = adminQq.matches("^[1-9]\\d{4,11}$")
                ? users.create("admin", "初始管理员", AdminPasswords.hash(seed), AdminRole.ADMIN.id(), adminQq)
                : users.create("admin", "初始管理员", AdminPasswords.hash(seed), AdminRole.ADMIN.id());
        if (adminQq.isBlank()) {
            log.warn("[ADMIN] 未配置 ADMIN_QQ，首次登录后必须绑定 QQ 才能进行其他操作");
        }
        audit.record(id, "admin", "bootstrap", "-", "-", "由 ADMIN_PASSWORD 创建初始管理员", "");
        log.warn("[ADMIN] 已创建初始管理员账号 admin（密码取自 ADMIN_PASSWORD）。"
                + "请尽快登录后台建个人账号，并把 admin 的密码换掉");
    }

    public boolean hasUsers() {
        return hasUsers;
    }

    public List<AdminUser> list() {
        return users.list().stream().map(AdminUserService::toUser).toList();
    }

    public Optional<AdminUser> find(long id) {
        return Optional.ofNullable(users.findById(id)).map(AdminUserService::toUser);
    }

    /**
     * 校验账号密码。
     *
     * <p>账号不存在与密码错误返回同一个结果 —— 不给撞库的人一个免费的"这个账号存在"信号。
     */
    public Optional<AdminUser> authenticate(String username, String rawPassword) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(rawPassword)) {
            return Optional.empty();
        }
        AdminUserRepository.Row row = users.findByUsername(username);
        if (row == null) {
            row = users.findByQq(username);
        }
        if (row == null || !row.enabled()) {
            // 账号不存在时也跑一次哈希，避免用响应时间判断账号是否存在
            AdminPasswords.verify(rawPassword, AdminPasswords.hash("dummy-password"));
            return Optional.empty();
        }
        if (!AdminPasswords.verify(rawPassword, row.passwordHash())) {
            return Optional.empty();
        }
        users.touchLogin(row.id());
        return Optional.of(toUser(row));
    }

    public AdminUser create(String username, String displayName, String rawPassword, AdminRole role) {
        return createInternal(username, displayName, rawPassword, role, "");
    }

    public AdminUser create(String username, String displayName, String rawPassword, AdminRole role,
                            String qq) {
        String q = qq == null ? "" : qq.trim();
        if (!QQ.matcher(q).matches()) {
            throw new IllegalArgumentException("QQ 号格式不对，应为 5–12 位数字");
        }
        return createInternal(username, displayName, rawPassword, role, q);
    }

    private AdminUser createInternal(String username, String displayName, String rawPassword,
                                     AdminRole role, String qq) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("账号名只能包含字母、数字、下划线、点和横线，长度 3–32");
        }
        requirePassword(rawPassword);
        AdminRole r = role == null ? AdminRole.VIEWER : role;
        if (users.findByUsername(name) != null) {
            throw new IllegalArgumentException("账号名已存在：" + name);
        }
        if (!qq.isBlank()) {
            if (users.findByQq(qq) != null) {
                throw new IllegalArgumentException("这个 QQ 已经绑了其他账号");
            }
            if (users.findByUsername(qq) != null) {
                throw new IllegalArgumentException("QQ 号和一个已有账号名冲突");
            }
        }
        if (users.findByQq(name) != null) {
            throw new IllegalArgumentException("账号名不能和已有账号的 QQ 号相同");
        }
        long id = users.create(name, trimToNull(displayName), AdminPasswords.hash(rawPassword),
                r.id(), qq);
        hasUsers = true;
        log.info("[ADMIN] 新建账号 {}（{}）", name, r.id());
        return toUser(users.findById(id));
    }

    /** 首次绑定或换绑 QQ：只允许写未验证状态，验证码通过后再标记 verified */
    public void bindQq(long id, String qq) {
        AdminUserRepository.Row row = require(id);
        String q = qq == null ? "" : qq.trim();
        if (!QQ.matcher(q).matches()) {
            throw new IllegalArgumentException("QQ 号格式不对，应为 5–12 位数字");
        }
        AdminUserRepository.Row other = users.findByQq(q);
        if (other != null && other.id() != id) {
            throw new IllegalArgumentException("这个 QQ 已经绑了其他账号");
        }
        if (users.findByUsername(q) != null) {
            throw new IllegalArgumentException("QQ 号和一个已有账号名冲突");
        }
        users.updateQq(id, q, false);
    }

    public void markQqVerified(long id) {
        require(id);
        users.markQqVerified(id);
    }

    public boolean usernameExists(String username) {
        return username != null && users.findByUsername(username) != null;
    }

    public boolean qqExists(String qq) {
        return qq != null && users.findByQq(qq) != null;
    }

    public String hashPassword(String raw) {
        requirePassword(raw);
        return AdminPasswords.hash(raw);
    }

    /** 账号创建走两步：先算好哈希，验证码通过后再落库 */
    public AdminUser createWithHash(String username, String displayName, String passwordHash,
                                    AdminRole role, String qq) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("账号名只能包含字母、数字、下划线、点和横线，长度 3–32");
        }
        String q = qq == null ? "" : qq.trim();
        if (!QQ.matcher(q).matches()) {
            throw new IllegalArgumentException("QQ 号格式不对，应为 5–12 位数字");
        }
        if (users.findByUsername(name) != null) {
            throw new IllegalArgumentException("账号名已存在：" + name);
        }
        if (users.findByQq(q) != null) {
            throw new IllegalArgumentException("这个 QQ 已经绑了其他账号");
        }
        if (users.findByUsername(q) != null || users.findByQq(name) != null) {
            throw new IllegalArgumentException("账号名和 QQ 号冲突");
        }
        long id = users.create(name, trimToNull(displayName), passwordHash,
                (role == null ? AdminRole.VIEWER : role).id(), q);
        hasUsers = true;
        log.info("[ADMIN] 新建账号 {}（{}）", name, role == null ? "viewer" : role.id());
        return toUser(users.findById(id));
    }

    public AdminUser update(long id, String displayName, AdminRole role, boolean enabled) {
        AdminUserRepository.Row row = require(id);
        AdminRole target = role == null ? AdminRole.parse(row.role()) : role;
        boolean losingAdmin = AdminRole.parse(row.role()) == AdminRole.ADMIN
                && (target != AdminRole.ADMIN || !enabled);
        if (losingAdmin && users.countEnabledAdmins() <= 1) {
            throw new IllegalArgumentException("这是最后一个可用的管理员，不能降级或停用");
        }
        users.updateProfile(id, trimToNull(displayName), target.id(), enabled);
        return toUser(users.findById(id));
    }

    public void changePassword(long id, String rawPassword) {
        require(id);
        requirePassword(rawPassword);
        users.updatePassword(id, AdminPasswords.hash(rawPassword));
    }

    public void delete(long id, long actorId) {
        if (id == actorId) {
            throw new IllegalArgumentException("不能删除自己");
        }
        AdminUserRepository.Row row = require(id);
        if (AdminRole.parse(row.role()) == AdminRole.ADMIN && row.enabled()
                && users.countEnabledAdmins() <= 1) {
            throw new IllegalArgumentException("这是最后一个可用的管理员，不能删除");
        }
        users.delete(id);
        hasUsers = users.countAll() > 0;
    }

    private AdminUserRepository.Row require(long id) {
        AdminUserRepository.Row row = users.findById(id);
        if (row == null) {
            throw new IllegalArgumentException("账号不存在：" + id);
        }
        return row;
    }

    private static void requirePassword(String raw) {
        if (raw == null || raw.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("密码至少 " + MIN_PASSWORD + " 位");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static AdminUser toUser(AdminUserRepository.Row r) {
        return new AdminUser(r.id(), r.username(), r.displayName(),
                AdminRole.parse(r.role()), r.enabled(), r.qq(), r.qqVerified(),
                r.createdAt(), r.lastLoginAt());
    }

    /** 给登录失败日志用：账号是否存在的统一脱敏写法 */
    static String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "?";
        }
        String u = username.trim();
        return u.length() <= 2 ? u : u.substring(0, 1) + "***" + u.substring(u.length() - 1);
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
