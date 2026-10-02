package com.example.qqbot.command;

import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 指令的存储。
 *
 * <p><b>和问答记录共用同一个 SQLite 文件</b>（§app.qa.db§）—— 都是本地小数据，
 * 没必要为它单独开一个库。表由本类自建，和 QaStore 互不干扰。
 *
 * <p>本类**不缓存**：指令条目是个位数~几十条，每次查一下 SQLite 只要零点几毫秒，
 * 比维护缓存一致性的复杂度划算得多。**而且天然就是"改完立刻生效"** ——
 * 这正是配置界面想要的。
 */
@Component
@DependsOn("qaStore")
public class CommandStore {

    private static final Logger log = LoggerFactory.getLogger(CommandStore.class);

    private final QaStore qaStore;
    private final ObjectMapper mapper;

    private volatile boolean available;

    /**
     * ⚠️ 复用 QaStore 的连接，而不是自己开一个。
     *
     * <p><b>为什么</b>：两个连接同时操作同一个 SQLite 文件时，
     * WAL 的共享内存段（-shm）会冲突，实测直接报
     * <code>SQLITE_IOERR_SHMOPEN / disk I/O error</code>。
     * 在 9p/drvfs（WSL 挂载 Windows 盘）上尤其容易触发。
     *
     * <p>SQLite 本来就是单写者模型，共用连接既避免冲突，
     * 也省一个文件句柄。
     */
    public CommandStore(QaStore qaStore, ObjectMapper mapper) {
        this.qaStore = qaStore;
        this.mapper = mapper;
    }

    private Connection conn() {
        return qaStore.connection();
    }

    @PostConstruct
    void init() {
        try {
            if (!qaStore.isAvailable()) {
                log.warn("[CMD] 问答库不可用，指令功能一并关闭");
                available = false;
                return;
            }
            createTables();
            seedBuiltins();
            available = true;
            log.info("[CMD] 指令库就绪：{} 条指令", count());
        } catch (Exception e) {
            log.warn("[CMD] 初始化指令库失败，指令功能将不可用（不影响正常问答）：{}", e.getMessage());
            available = false;
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS bot_command (
                      id          INTEGER PRIMARY KEY,
                      trigger     TEXT NOT NULL UNIQUE,
                      reply       TEXT NOT NULL,
                      description TEXT DEFAULT '',
                      scope       TEXT NOT NULL DEFAULT 'all',
                      group_ids   TEXT DEFAULT '[]',
                      min_role    TEXT NOT NULL DEFAULT 'member',
                      enabled     INTEGER NOT NULL DEFAULT 1,
                      sort_order  INTEGER NOT NULL DEFAULT 0,
                      builtin     INTEGER NOT NULL DEFAULT 0,
                      created_at  TEXT NOT NULL,
                      updated_at  TEXT NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cmd_enabled ON bot_command(enabled)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS bot_command_log (
                      id         INTEGER PRIMARY KEY,
                      ts         TEXT NOT NULL,
                      trigger    TEXT,
                      group_id   INTEGER,
                      user_id    INTEGER,
                      matched    INTEGER
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cmdlog_ts ON bot_command_log(ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cmdlog_trigger ON bot_command_log(trigger)");
        }
        // 升级**已经存在**的表：CREATE TABLE IF NOT EXISTS 对老表什么都不做，
        // 不加这两列的话，之前建过库的机器升级后会一直报 "no such column: kind"。
        addColumnIfMissing("kind", "TEXT NOT NULL DEFAULT 'template'");
        addColumnIfMissing("mode", "TEXT NOT NULL DEFAULT 'kb'");
    }

    /**
     * 给已存在的表补列。
     *
     * <p>SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}，直接加会抛
     * "duplicate column name"，所以先用 {@code PRAGMA table_info} 查一遍。
     */
    private void addColumnIfMissing(String column, String ddl) throws SQLException {
        synchronized (this) {
            boolean exists = false;
            try (Statement st = conn().createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(bot_command)")) {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("name"))) {
                        exists = true;
                        break;
                    }
                }
            }
            if (exists) {
                return;
            }
            try (Statement st = conn().createStatement()) {
                st.execute("ALTER TABLE bot_command ADD COLUMN " + column + " " + ddl);
                log.info("[CMD] bot_command 补列：{} {}", column, ddl);
            }
        }
    }

    /**
     * 写入内置指令（幂等）。
     *
     * <p>用 §INSERT OR IGNORE§：已经存在就什么都不做 ——
     * 所以你**改过内置指令的回复后，重启不会被覆盖回去**。
     */
    private void seedBuiltins() throws SQLException {
        record Builtin(String trigger, String reply, String desc, int order) {
        }
        List<Builtin> builtins = List.of(
                new Builtin("help", "{cmd.list}", "查看所有指令", 1),
                new Builtin("list", "{cmd.list}", "列出所有指令", 2),
                new Builtin("ping", "在的喵～（已运行 {uptime}）", "看看我在不在", 3),
                new Builtin("stats", """
                        知识库：{kb.count} 条资料
                        术语表：{kb.terms} 条
                        累计问答：{qa.total} 次（命中率 {qa.hitrate}）
                        今天已回答：{qa.today} 次""", "查看知识库统计", 4));

        String now = Instant.now().toString();
        try (PreparedStatement ps = conn().prepareStatement("""
                INSERT OR IGNORE INTO bot_command
                  (trigger, reply, description, scope, min_role, enabled, sort_order, builtin,
                   created_at, updated_at)
                VALUES (?,?,?,'all','member',1,?,1,?,?)""")) {
            for (Builtin b : builtins) {
                ps.setString(1, b.trigger());
                ps.setString(2, b.reply());
                ps.setString(3, b.desc());
                ps.setInt(4, b.order());
                ps.setString(5, now);
                ps.setString(6, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ==================== 查询 ====================

    public boolean isAvailable() {
        return available;
    }

    /** 全部指令（含停用的），按 sort_order 排 */
    public List<BotCommand> listAll() {
        return query("SELECT * FROM bot_command ORDER BY sort_order, id", null);
    }

    /** 启用的指令 */
    public List<BotCommand> listEnabled() {
        return query("SELECT * FROM bot_command WHERE enabled = 1 ORDER BY sort_order, id", null);
    }

    /**
     * 按触发词精确查找（**大小写不敏感**）。
     *
     * <p>大小写不敏感是有意的：§/Help§ 和 §/help§ 应该都能用，
     * 不然群友会困惑"为什么大写就不行"。
     */
    public BotCommand findByTrigger(String trigger) {
        if (trigger == null || trigger.isBlank()) {
            return null;
        }
        List<BotCommand> found = query(
                "SELECT * FROM bot_command WHERE LOWER(trigger) = LOWER(?)", trigger.trim());
        return found.isEmpty() ? null : found.get(0);
    }

    private List<BotCommand> query(String sql, String param) {
        List<BotCommand> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                if (param != null) {
                    ps.setString(1, param);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            } catch (Exception e) {
                log.warn("[CMD] 查询指令失败：{}", e.getMessage());
            }
        }
        return out;
    }

    private BotCommand map(ResultSet rs) throws SQLException {
        List<Long> groups = new ArrayList<>();
        String raw = rs.getString("group_ids");
        if (raw != null && !raw.isBlank()) {
            try {
                for (var node : mapper.readTree(raw)) {
                    groups.add(node.asLong());
                }
            } catch (Exception ignored) {
                // 坏数据当空处理
            }
        }
        return new BotCommand(
                rs.getLong("id"),
                rs.getString("trigger"),
                rs.getString("reply"),
                rs.getString("description"),
                rs.getString("scope"),
                groups,
                rs.getString("min_role"),
                rs.getInt("enabled") == 1,
                rs.getInt("sort_order"),
                rs.getInt("builtin") == 1,
                rs.getString("kind"),
                rs.getString("mode"));
    }

    public int count() {
        if (!available) {
            return 0;
        }
        synchronized (this) {
            try (Statement st = conn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM bot_command")) {
                rs.next();
                return rs.getInt(1);
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    // ==================== 写入 ====================

    /** 新增或更新（按 trigger 判断） */
    public long upsert(BotCommand cmd) throws IOException {
        if (!available) {
            throw new IOException("指令库不可用");
        }
        String now = Instant.now().toString();
        String groupsJson = mapper.writeValueAsString(cmd.groupIds() == null ? List.of() : cmd.groupIds());

        synchronized (this) {
            try {
                BotCommand existing = findByTrigger(cmd.trigger());
                if (existing != null) {
                    try (PreparedStatement ps = conn().prepareStatement("""
                            UPDATE bot_command SET reply=?, description=?, scope=?, group_ids=?,
                              min_role=?, enabled=?, sort_order=?, kind=?, mode=?, updated_at=?
                            WHERE id=?""")) {
                        ps.setString(1, cmd.reply());
                        ps.setString(2, cmd.description());
                        ps.setString(3, cmd.scope());
                        ps.setString(4, groupsJson);
                        ps.setString(5, cmd.minRole());
                        ps.setInt(6, cmd.enabled() ? 1 : 0);
                        ps.setInt(7, cmd.sortOrder());
                        ps.setString(8, cmd.kindName());
                        ps.setString(9, cmd.modeName());
                        ps.setString(10, now);
                        ps.setLong(11, existing.id());
                        ps.executeUpdate();
                    }
                    return existing.id();
                }
                try (PreparedStatement ps = conn().prepareStatement("""
                        INSERT INTO bot_command
                          (trigger, reply, description, scope, group_ids, min_role,
                           enabled, sort_order, builtin, kind, mode, created_at, updated_at)
                        VALUES (?,?,?,?,?,?,?,?,0,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, cmd.trigger());
                    ps.setString(2, cmd.reply());
                    ps.setString(3, cmd.description());
                    ps.setString(4, cmd.scope());
                    ps.setString(5, groupsJson);
                    ps.setString(6, cmd.minRole());
                    ps.setInt(7, cmd.enabled() ? 1 : 0);
                    ps.setInt(8, cmd.sortOrder());
                    ps.setString(9, cmd.kindName());
                    ps.setString(10, cmd.modeName());
                    ps.setString(11, now);
                    ps.setString(12, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        return keys.getLong(1);
                    }
                }
            } catch (SQLException e) {
                throw new IOException("写入指令失败：" + e.getMessage(), e);
            }
        }
    }

    /** 删除（内置命令不允许删） */
    public boolean delete(String trigger) throws IOException {
        if (!available) {
            throw new IOException("指令库不可用");
        }
        synchronized (this) {
            try {
                BotCommand existing = findByTrigger(trigger);
                if (existing == null) {
                    return false;
                }
                if (existing.builtin()) {
                    throw new IOException("内置指令不能删除（可以改回复内容）");
                }
                try (PreparedStatement ps = conn().prepareStatement(
                        "DELETE FROM bot_command WHERE id = ?")) {
                    ps.setLong(1, existing.id());
                    return ps.executeUpdate() > 0;
                }
            } catch (SQLException e) {
                throw new IOException("删除失败：" + e.getMessage(), e);
            }
        }
    }

    public boolean setEnabled(String trigger, boolean enabled) throws IOException {
        if (!available) {
            throw new IOException("指令库不可用");
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE bot_command SET enabled=?, updated_at=? WHERE LOWER(trigger)=LOWER(?)")) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setString(2, Instant.now().toString());
                ps.setString(3, trigger);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
    }

    // ==================== 使用记录 ====================

    /**
     * 记一次命令使用（含**未命中**的）。
     *
     * <p>记录未命中的价值：能看出「群友发了 /xxx 但没配这个命令」——
     * 和术语表未命中排行一个思路，直接告诉你该加什么命令。
     *
     * @param matched true=命中了某条指令；false=发了 / 开头但没匹配上
     */
    public void logUsage(String trigger, long groupId, long userId, boolean matched) {
        if (!available) {
            return;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "INSERT INTO bot_command_log (ts, trigger, group_id, user_id, matched)"
                            + " VALUES (?,?,?,?,?)")) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, trigger == null ? "" : trigger.toLowerCase(Locale.ROOT));
                ps.setLong(3, groupId);
                ps.setLong(4, userId);
                ps.setInt(5, matched ? 1 : 0);
                ps.executeUpdate();
            } catch (Exception e) {
                log.debug("[CMD] 记录使用失败（不影响回复）：{}", e.getMessage());
            }
        }
    }

    /** 使用排行：命中次数 */
    public List<Map<String, Object>> topUsed(int days, int limit) {
        return statQuery("SELECT trigger, COUNT(*) c FROM bot_command_log"
                + " WHERE matched=1" + daysClause(days) + " GROUP BY trigger ORDER BY c DESC LIMIT ?",
                days, limit);
    }

    /** ★ 未配置的命令排行：群友发了但没配 —— 直接告诉你该加什么 */
    public List<Map<String, Object>> unmatched(int days, int limit) {
        return statQuery("SELECT trigger, COUNT(*) c FROM bot_command_log"
                + " WHERE matched=0" + daysClause(days) + " GROUP BY trigger ORDER BY c DESC LIMIT ?",
                days, limit);
    }

    private String daysClause(int days) {
        return days > 0 ? " AND ts >= datetime('now', '-" + days + " days')" : "";
    }

    private List<Map<String, Object>> statQuery(String sql, int days, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("trigger", rs.getString(1));
                        m.put("count", rs.getLong(2));
                        out.add(m);
                    }
                }
            } catch (Exception e) {
                log.warn("[CMD] 统计失败：{}", e.getMessage());
            }
        }
        return out;
    }
}
