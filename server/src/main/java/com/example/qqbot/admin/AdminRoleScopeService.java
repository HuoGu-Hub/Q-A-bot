package com.example.qqbot.admin;

import com.example.qqbot.persistence.AdminRoleAreaRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「每个角色能进哪几块」的运行时视图。
 *
 * <p>三层取值：库里配过就用库里的；没配过用 {@link AdminArea} 的默认值；
 * 库读不出来也退回默认值 —— 后台不该因为一张配置表的问题变成全锁死。
 *
 * <p>判定在每次请求时现算，管理员保存后立刻 {@link #reload()}，
 * 在线的访客下一次点击就按新范围走，不需要重建会话。
 */
@Component
public class AdminRoleScopeService {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleScopeService.class);

    private final AdminRoleAreaRepository repo;

    /** 角色 → 允许的区域。整体替换，读的时候只取一次引用 */
    private volatile Map<AdminRole, Set<AdminArea>> cache = Map.of();

    public AdminRoleScopeService(AdminRoleAreaRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void reload() {
        Map<AdminRole, Set<AdminArea>> next = new EnumMap<>(AdminRole.class);
        Map<String, String> stored;
        try {
            stored = repo.load();
        } catch (RuntimeException e) {
            log.warn("[ADMIN] 读取角色范围失败：{}", e.getMessage());
            stored = Map.of();
        }
        for (AdminRole role : AdminRole.values()) {
            String csv = stored.get(role.id());
            if (csv == null) {
                next.put(role, AdminArea.defaultsOf(role));
            } else {
                next.put(role, sanitize(role, AdminArea.parseCsv(csv)));
            }
        }
        cache = Map.copyOf(next);
    }

    /**
     * 把"某个角色该有的集合"收拾成合法的。
     *
     * <p>两条硬规则：管理角色永远保留「账号」，否则改回配置的入口会消失；
     * 其余角色原样返回。
     */
    static Set<AdminArea> sanitize(AdminRole role, Set<AdminArea> areas) {
        Set<AdminArea> out = EnumSet.noneOf(AdminArea.class);
        out.addAll(areas);
        for (AdminArea a : AdminArea.values()) {
            if (a.mustKeepFor(role)) {
                out.add(a);
            }
        }
        return out;
    }

    public Set<AdminArea> areasOf(AdminRole role) {
        return cache.getOrDefault(role, AdminArea.defaultsOf(role));
    }

    /** 这个角色能不能进这个区域 */
    public boolean allows(AdminRole role, AdminArea area) {
        return area == null || areasOf(role).contains(area);
    }

    /** 路径能不能进：不属于任何区域的路径一律放行 */
    public boolean allows(AdminRole role, String path) {
        return AdminArea.of(path).map(a -> allows(role, a)).orElse(true);
    }

    public Map<AdminRole, Set<AdminArea>> snapshot() {
        Map<AdminRole, Set<AdminArea>> out = new EnumMap<>(AdminRole.class);
        for (AdminRole role : AdminRole.values()) {
            out.put(role, areasOf(role));
        }
        return out;
    }

    /**
     * 保存一套范围。
     *
     * <p>只接受三个角色全给齐的请求：漏掉一个角色就等于"把它清空"，
     * 而清空一个角色的全部区域是很容易被误操作出来的状态，
     * 不该由"少发一个字段"隐式达成。
     */
    public Map<AdminRole, Set<AdminArea>> apply(Map<String, List<String>> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("没有收到任何角色");
        }
        Map<AdminRole, Set<AdminArea>> next = new EnumMap<>(AdminRole.class);
        Map<String, String> rows = new java.util.LinkedHashMap<>();
        for (AdminRole role : AdminRole.values()) {
            List<String> ids = requested.get(role.id());
            if (ids == null) {
                throw new IllegalArgumentException("缺少角色：" + role.label());
            }
            Set<AdminArea> areas = EnumSet.noneOf(AdminArea.class);
            for (String raw : ids) {
                AdminArea a = AdminArea.parse(raw);
                if (a == null) {
                    throw new IllegalArgumentException("未知的区域：" + raw);
                }
                areas.add(a);
            }
            areas = sanitize(role, areas);
            next.put(role, areas);
            rows.put(role.id(), AdminArea.join(areas));
        }
        for (String key : requested.keySet()) {
            if (AdminRole.parseStrict(key) == null) {
                throw new IllegalArgumentException("未知的角色：" + key);
            }
        }
        repo.saveAll(rows);
        reload();
        log.info("[ADMIN] 角色范围已更新：查看器 {} / 编辑 {} / 管理 {}",
                AdminArea.join(next.get(AdminRole.VIEWER)),
                AdminArea.join(next.get(AdminRole.EDITOR)),
                AdminArea.join(next.get(AdminRole.ADMIN)));
        return next;
    }
}
