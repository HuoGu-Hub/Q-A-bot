package com.example.qqbot.kb.category;

import com.example.qqbot.persistence.SqliteConnectionProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类映射的存储：把 Wiki 的原始分类映射到玩家视角的大类。

 * <p><b>只有被管理员改过的分类才存库</b> —— 没存过的走 {@link KbGroups#autoGroup} 自动规则。
 * 这样：
 * <ul>
 *   <li>冷启动时零配置就能用（自动规则打底）</li>
 *   <li>只存差异，改一条存一条</li>
 *   <li>规则升级后，没被手动改过的分类会自动跟着变</li>
 * </ul>
 */
@Component
public class CategoryStore {

    private static final Logger log = LoggerFactory.getLogger(CategoryStore.class);

    private final SqliteConnectionProvider db;
    private volatile boolean available;

    public CategoryStore(SqliteConnectionProvider db) {
        this.db = db;
    }

    private Connection conn() {
        return db.connection();
    }

    public boolean isAvailable() {
        return available;
    }

    @PostConstruct
    void init() {
        try {
            if (!db.isAvailable()) {
                log.warn("[KB-CAT] 问答库不可用，分类配置功能关闭");
                return;
            }
            try (Statement st = conn().createStatement()) {
                // 用普通字符串而不是文本块 —— 文本块的结束符必须独占一行，
                // 生成代码时极易写错，而且报错信息很难懂。
                st.execute("CREATE TABLE IF NOT EXISTS kb_category ("
                        + " raw        TEXT PRIMARY KEY,"
                        + " group_key  TEXT NOT NULL,"
                        + " label_zh   TEXT DEFAULT '',"
                        + " updated_at TEXT NOT NULL)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_cat_group ON kb_category(group_key)");
            }
            available = true;
            log.info("[KB-CAT] 分类配置就绪：已手动设置 {} 条", count());
        } catch (Exception e) {
            log.warn("[KB-CAT] 初始化失败（不影响问答）：{}", e.getMessage());
            available = false;
        }
    }

    /** 一条映射 */
    public record Mapping(String raw, String groupKey, String labelZh) {
    }

    /** 读全部映射：raw → Mapping */
    public Map<String, Mapping> loadAll() {
        Map<String, Mapping> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        synchronized (this) {
            try (Statement st = conn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT raw, group_key, label_zh FROM kb_category")) {
                while (rs.next()) {
                    out.put(rs.getString(1),
                            new Mapping(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            } catch (Exception e) {
                log.warn("[KB-CAT] 读取映射失败：{}", e.getMessage());
            }
        }
        return out;
    }

    /** upsert 用的 SQL（普通字符串，不用文本块 —— 文本块的结束符必须独占一行，容易写错） */
    private static final String UPSERT =
            "INSERT INTO kb_category (raw, group_key, label_zh, updated_at) VALUES (?,?,?,?) "
            + "ON CONFLICT(raw) DO UPDATE SET "
            + "group_key = excluded.group_key, "
            + "label_zh = excluded.label_zh, "
            + "updated_at = excluded.updated_at";

    /** 设置一条（upsert） */
    public boolean set(String raw, String groupKey, String labelZh) {
        if (!available || raw == null || raw.isBlank()) {
            return false;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(UPSERT)) {
                ps.setString(1, raw);
                ps.setString(2, groupKey);
                ps.setString(3, labelZh == null ? "" : labelZh);
                ps.setString(4, Instant.now().toString());
                ps.executeUpdate();
                return true;
            } catch (Exception e) {
                log.warn("[KB-CAT] 保存失败：{}", e.getMessage());
                return false;
            }
        }
    }

    /**
     * 只改大类、**保留中文标签**的 upsert。
     *
     * <p>为什么不能复用上面那条 {@link #UPSERT}：它会把 {@code label_zh} 一起覆盖成
     * 传进来的值。而批量改归属时前端只知道大类（中文标签是逐条人工填的），
     * 复用那条 SQL 就等于把选中的几十条中文标签一次性清空 —— 静默的数据丢失，
     * 而且看不出来（列表上没标签的行本来也长这样）。
     */
    private static final String UPSERT_GROUP_ONLY =
            "INSERT INTO kb_category (raw, group_key, label_zh, updated_at) VALUES (?,?,?,?) "
            + "ON CONFLICT(raw) DO UPDATE SET "
            + "group_key = excluded.group_key, "
            + "updated_at = excluded.updated_at";

    /**
     * 批量只改大类，保留已有中文标签（一个事务）。
     *
     * <p>冲突时不写 {@code label_zh}，所以已有标签原样留着；只有原本不存在的那一行
     * 才会以空标签落库。
     */
    public int setGroupBatch(List<String> raws, String groupKey) {
        if (!available || raws == null || raws.isEmpty()) {
            return 0;
        }
        int n = 0;
        synchronized (this) {
            try {
                conn().setAutoCommit(false);
                try (PreparedStatement ps = conn().prepareStatement(UPSERT_GROUP_ONLY)) {
                    String now = Instant.now().toString();
                    for (String raw : raws) {
                        if (raw == null || raw.isBlank()) {
                            continue;
                        }
                        ps.setString(1, raw);
                        ps.setString(2, groupKey);
                        ps.setString(3, "");
                        ps.setString(4, now);
                        ps.addBatch();
                        n++;
                    }
                    ps.executeBatch();
                }
                conn().commit();
            } catch (Exception e) {
                try {
                    conn().rollback();
                } catch (SQLException ignored) {
                }
                log.warn("[KB-CAT] 批量改归属失败：{}", e.getMessage());
                n = 0;
            } finally {
                try {
                    conn().setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }
        return n;
    }

    /** 批量设置（一个事务） */
    public int setBatch(List<Mapping> items) {
        if (!available || items == null || items.isEmpty()) {
            return 0;
        }
        int n = 0;
        synchronized (this) {
            try {
                conn().setAutoCommit(false);
                try (PreparedStatement ps = conn().prepareStatement(UPSERT)) {
                    String now = Instant.now().toString();
                    for (Mapping m : items) {
                        ps.setString(1, m.raw());
                        ps.setString(2, m.groupKey());
                        ps.setString(3, m.labelZh() == null ? "" : m.labelZh());
                        ps.setString(4, now);
                        ps.addBatch();
                        n++;
                    }
                    ps.executeBatch();
                }
                conn().commit();
            } catch (Exception e) {
                try {
                    conn().rollback();
                } catch (SQLException ignored) {
                }
                log.warn("[KB-CAT] 批量保存失败：{}", e.getMessage());
                n = 0;
            } finally {
                try {
                    conn().setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }
        return n;
    }
    /** 删掉一条映射（恢复成自动规则） */
    public boolean remove(String raw) {
        if (!available) {
            return false;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "DELETE FROM kb_category WHERE raw = ?")) {
                ps.setString(1, raw);
                return ps.executeUpdate() > 0;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** 全部清空（恢复成纯自动规则） */
    public int clearAll() {
        if (!available) {
            return 0;
        }
        synchronized (this) {
            try (Statement st = conn().createStatement()) {
                return st.executeUpdate("DELETE FROM kb_category");
            } catch (Exception e) {
                return 0;
            }
        }
    }

    public long count() {
        if (!available) {
            return 0;
        }
        synchronized (this) {
            try (Statement st = conn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM kb_category")) {
                return rs.next() ? rs.getLong(1) : 0;
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
