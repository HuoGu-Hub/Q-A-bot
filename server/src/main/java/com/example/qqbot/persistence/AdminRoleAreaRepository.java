package com.example.qqbot.persistence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 角色可访问范围的存储。
 *
 * <p>一行一个角色，{@code areas} 是逗号分隔的区域 id。
 * **没有行 = 还没配过**，此时用 {@code AdminArea} 里的默认值；
 * **空串 = 明确配成了"什么都没勾"**。这两种状态必须分得开 ——
 * 否则管理员把某一级全部取消之后，下次启动会悄悄恢复成默认值。
 */
@Component
public class AdminRoleAreaRepository {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleAreaRepository.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Jdbc jdbc;

    public AdminRoleAreaRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** 建表。公开是为了让测试能在临时库上自己装配，不依赖 Spring */
    @PostConstruct
    public void init() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS admin_role_area ("
                    + " role       TEXT PRIMARY KEY,"
                    + " areas      TEXT NOT NULL DEFAULT '',"
                    + " updated_at TEXT NOT NULL"
                    + " )");
        } catch (Exception e) {
            // 库不可用时不让整个应用起不来：范围退回默认值，后台照常能开
            log.warn("[ADMIN] 角色范围表初始化失败：{}", e.getMessage());
        }
    }

    /** 角色 id → 区域 id 串。没配过的角色不出现在结果里 */
    public Map<String, String> load() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT role, areas FROM admin_role_area", r -> {
                out.put(r.str("role"), r.str("areas"));
                return null;
            });
        } catch (Exception e) {
            log.warn("[ADMIN] 读取角色范围失败，先用默认值：{}", e.getMessage());
            return Map.of();
        }
        return out;
    }

    /** 整表覆盖：范围是一套"当前生效"的配置，不留历史版本 */
    public void saveAll(Map<String, String> byRole) {
        String now = LocalDateTime.now().format(TS);
        jdbc.transaction(() -> {
            jdbc.execute("DELETE FROM admin_role_area");
            for (Map.Entry<String, String> e : byRole.entrySet()) {
                jdbc.update("INSERT INTO admin_role_area (role, areas, updated_at) VALUES (?, ?, ?)",
                        e.getKey(), e.getValue() == null ? "" : e.getValue(), now);
            }
            return null;
        });
    }
}
