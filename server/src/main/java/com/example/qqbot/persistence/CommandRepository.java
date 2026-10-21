package com.example.qqbot.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;

/**
 * 指令表（{@code bot_command}）与使用记录表（{@code bot_command_log}）的**数据访问**。
 *
 * <h2>两件容易在搬迁中丢掉的事</h2>
 * <ul>
 *   <li>{@link #seedBuiltins} 是 {@code INSERT OR IGNORE}：<b>管理员改过内置指令的回复后，
 *       重启不能被覆盖回去</b>。改成普通 INSERT 就是每次重启抹掉人工成果。</li>
 *   <li>{@link #addColumnIfMissing}：SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}，
 *       直接加会抛 {@code duplicate column name}，所以先查 {@code PRAGMA table_info}。
 *       少了它，**之前建过库的机器升级后会一直报 {@code no such column: kind}**。</li>
 * </ul>
 *
 * <h2>为什么 {@code group_ids} 在这里是字符串</h2>
 * 列里存的是 JSON 数组原文。**解析与序列化留在业务侧**（它本来就持有
 * {@code ObjectMapper}）—— 持久层返回「行里到底是什么字」，不猜它的含义。
 *
 * <h2>为什么不缓存</h2>
 * 指令条目是个位数~几十条，每次查一下 SQLite 只要零点几毫秒，
 * 比维护缓存一致性的复杂度划算得多，而且天然"改完立刻生效"。
 */
@Repository
public class CommandRepository {

    private static final Logger log = LoggerFactory.getLogger(CommandRepository.class);

    /** 一行指令（{@code groupIdsJson} 是列里的 JSON 原文） */
    public record Row(long id, String trigger, String reply, String description, String scope,
                      String groupIdsJson, String access, boolean enabled, int sortOrder,
                      boolean builtin, String kind, String mode,
                      String paramsJson, String help, String action) {
    }

    /** 一条内置指令的种子；{@code paramsJson} 与 {@code action} 给动作类命令用 */
    public record Seed(String trigger, String reply, String description, int sortOrder,
                       String paramsJson, String action) {
    }

    /** 使用统计的一行 */
    public record UsageStat(String trigger, long count) {
    }

    private static final String COLUMNS =
            "id, trigger, reply, description, scope, group_ids, min_role,"
                    + " enabled, sort_order, builtin, kind, mode, params, help, action";

    private static final Jdbc.RowMapper<Row> MAP = r -> new Row(
            r.longOf("id"), r.str("trigger"), r.str("reply"), r.str("description"), r.str("scope"),
            r.str("group_ids"), r.str("min_role"), r.intOf("enabled") == 1, r.intOf("sort_order"),
            r.intOf("builtin") == 1, r.str("kind"), r.str("mode"),
            r.str("params"), r.str("help"), r.str("action"));

    private final Jdbc jdbc;

    public CommandRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bot_command ("
                + " id          INTEGER PRIMARY KEY,"
                + " trigger     TEXT NOT NULL UNIQUE,"
                + " reply       TEXT NOT NULL,"
                + " description TEXT DEFAULT '',"
                + " scope       TEXT NOT NULL DEFAULT 'all',"
                + " group_ids   TEXT DEFAULT '[]',"
                + " min_role    TEXT NOT NULL DEFAULT 'member',"
                + " enabled     INTEGER NOT NULL DEFAULT 1,"
                + " sort_order  INTEGER NOT NULL DEFAULT 0,"
                + " builtin     INTEGER NOT NULL DEFAULT 0,"
                + " created_at  TEXT NOT NULL,"
                + " updated_at  TEXT NOT NULL"
                + " )");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_cmd_enabled ON bot_command(enabled)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS bot_command_log ("
                + " id         INTEGER PRIMARY KEY,"
                + " ts         TEXT NOT NULL,"
                + " trigger    TEXT,"
                + " group_id   INTEGER,"
                + " user_id    INTEGER,"
                + " matched    INTEGER"
                + " )");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_cmdlog_ts ON bot_command_log(ts)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_cmdlog_trigger ON bot_command_log(trigger)");

        // 升级**已经存在**的表：CREATE TABLE IF NOT EXISTS 对老表什么都不做
        addColumnIfMissing("kind", "TEXT NOT NULL DEFAULT 'template'");
        addColumnIfMissing("mode", "TEXT NOT NULL DEFAULT 'kb'");
        // 参数表、手写帮助、绑定的执行器 —— 老库升级后这三列才有内容
        addColumnIfMissing("params", "TEXT NOT NULL DEFAULT '[]'");
        addColumnIfMissing("help", "TEXT");
        addColumnIfMissing("action", "TEXT");
    }

    /** 给已存在的表补列 —— 见类注释 */
    private void addColumnIfMissing(String column, String ddl) {
        boolean exists = jdbc.query("PRAGMA table_info(bot_command)", r -> r.str("name")).stream()
                .anyMatch(n -> column.equalsIgnoreCase(n));
        if (exists) {
            return;
        }
        jdbc.execute("ALTER TABLE bot_command ADD COLUMN " + column + " " + ddl);
        log.info("[CMD] bot_command 补列：{} {}", column, ddl);
    }

    /** 写入内置指令（幂等，一个事务） */
    public int seedBuiltins(List<Seed> seeds, String now) {
        List<Object[]> rows = seeds.stream()
                .map(s -> new Object[]{s.trigger(), s.reply(), s.description(), s.sortOrder(),
                        s.paramsJson() == null ? "[]" : s.paramsJson(), s.action(), now, now})
                .toList();
        return jdbc.batch("INSERT OR IGNORE INTO bot_command"
                + " (trigger, reply, description, scope, min_role, enabled, sort_order, builtin,"
                + "  params, action, created_at, updated_at)"
                + " VALUES (?,?,?,'all','member',1,?,1,?,?,?,?)", rows);
    }

    /** 全部指令（含停用的），按 sort_order 排 */
    public List<Row> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_command ORDER BY sort_order, id", MAP);
    }

    /** 启用的指令 */
    public List<Row> enabled() {
        return jdbc.query("SELECT " + COLUMNS
                + " FROM bot_command WHERE enabled = 1 ORDER BY sort_order, id", MAP);
    }

    /**
     * 按触发词精确查找（**大小写不敏感**）。
     *
     * <p>大小写不敏感是有意的：{@code /Help} 和 {@code /help} 应该都能用，
     * 不然群友会困惑"为什么大写就不行"。
     */
    public Row findByTrigger(String trigger) {
        return jdbc.queryOne("SELECT " + COLUMNS
                + " FROM bot_command WHERE LOWER(trigger) = LOWER(?)", MAP, trigger);
    }

    public Row findById(long id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM bot_command WHERE id = ?", MAP, id);
    }

    public long count() {
        return jdbc.count("SELECT COUNT(*) FROM bot_command");
    }

    /**
     * 新增或更新（按 trigger 判断），返回这一条的 id。
     *
     * <p>「先查再决定写哪条」是**读-改-写**，必须在同一个事务里 ——
     * 否则两个管理员同时保存同一个触发词，两边都查不到、都去 INSERT，
     * 其中一个撞上 {@code trigger} 的唯一约束报错。
     */
    public long upsert(Row row, String now) {
        return jdbc.transaction(() -> {
            Row existing = findByTrigger(row.trigger());
            if (existing != null) {
                jdbc.update("UPDATE bot_command SET reply=?, description=?, scope=?, group_ids=?,"
                                + " min_role=?, enabled=?, sort_order=?, kind=?, mode=?,"
                                + " params=?, help=?, action=?, updated_at=?"
                                + " WHERE id=?",
                        row.reply(), row.description(), row.scope(), row.groupIdsJson(),
                        row.access(), row.enabled(), row.sortOrder(), row.kind(), row.mode(),
                        row.paramsJson(), row.help(), row.action(), now, existing.id());
                return existing.id();
            }
            // builtin 固定 0：内置指令由 seedBuiltins 写入，走不到这条路径
            return jdbc.insert("INSERT INTO bot_command"
                            + " (trigger, reply, description, scope, group_ids, min_role,"
                            + "  enabled, sort_order, builtin, kind, mode, params, help, action,"
                            + "  created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,0,?,?,?,?,?,?,?)",
                    row.trigger(), row.reply(), row.description(), row.scope(), row.groupIdsJson(),
                    row.access(), row.enabled(), row.sortOrder(), row.kind(), row.mode(),
                    row.paramsJson(), row.help(), row.action(), now, now);
        });
    }

    /**
     * 按主键更新，**允许改 trigger**。
     *
     * <p>这是"改名"和"保存"共用的一条路：命令名就是调用名，改名必须原地改，
     * 另起一条新行会让旧调用名继续生效、后台出现两条几乎一样的命令。
     */
    public int updateById(long id, Row row, String now) {
        return jdbc.update("UPDATE bot_command SET trigger=?, reply=?, description=?, scope=?,"
                        + " group_ids=?, min_role=?, enabled=?, sort_order=?, kind=?, mode=?,"
                        + " params=?, help=?, action=?, updated_at=?"
                        + " WHERE id=?",
                row.trigger(), row.reply(), row.description(), row.scope(), row.groupIdsJson(),
                row.access(), row.enabled(), row.sortOrder(), row.kind(), row.mode(),
                row.paramsJson(), row.help(), row.action(), now, id);
    }

    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM bot_command WHERE id = ?", id) > 0;
    }

    /**
     * 删一条内置指令（迁移用）。
     *
     * <p>带 {@code builtin = 1} 条件：管理员自建的同名指令不该被迁移顺手带走。
     */
    public int deleteBuiltin(String trigger) {
        return jdbc.update("DELETE FROM bot_command WHERE builtin = 1 AND LOWER(trigger) = LOWER(?)",
                trigger);
    }

    public boolean setEnabled(String trigger, boolean enabled, String now) {
        return jdbc.update("UPDATE bot_command SET enabled=?, updated_at=? WHERE LOWER(trigger)=LOWER(?)",
                enabled, now, trigger) > 0;
    }

    // ==================== 使用记录 ====================

    /** 记一次命令使用（含**未命中**的） */
    public void logUsage(String trigger, long groupId, long userId, boolean matched, String ts) {
        jdbc.update("INSERT INTO bot_command_log (ts, trigger, group_id, user_id, matched)"
                + " VALUES (?,?,?,?,?)", ts, trigger, groupId, userId, matched);
    }

    /** 把历史使用记录里的旧命令名改成新名字；改名后统计才不会断成两截 */
    public int renameUsage(String oldTrigger, String newTrigger) {
        return jdbc.update("UPDATE bot_command_log SET trigger = ? WHERE LOWER(trigger) = LOWER(?)",
                newTrigger.toLowerCase(Locale.ROOT), oldTrigger);
    }

    /**
     * 使用排行：命中次数。
     *
     * <p>历史日志里还留着已退休的 {@code help} / {@code list} / 旧定时命令；
     * 它们已经不在命令表里，继续显示会让"使用情况"和上面那张命令表对不上，
     * 所以在查询层直接排除。
     */
    public List<UsageStat> topUsed(int days, int limit) {
        return statQuery("SELECT trigger, COUNT(*) c FROM bot_command_log"
                + " WHERE matched=1"
                + " AND LOWER(trigger) NOT IN ('help','list','set/output','list/output','del/output')"
                + daysClause(days) + " GROUP BY trigger ORDER BY c DESC LIMIT ?", limit);
    }

    /** ★ 未配置的命令排行：群友发了但没配 —— 直接告诉你该加什么 */
    public List<UsageStat> unmatched(int days, int limit) {
        return statQuery("SELECT trigger, COUNT(*) c FROM bot_command_log"
                + " WHERE matched=0" + daysClause(days) + " GROUP BY trigger ORDER BY c DESC LIMIT ?", limit);
    }

    /** {@code days} 是 int，直接拼进 SQL 没有注入面；≤0 表示不限时间 */
    private static String daysClause(int days) {
        return days > 0 ? " AND ts >= datetime('now', '-" + days + " days')" : "";
    }

    private List<UsageStat> statQuery(String sql, int limit) {
        return jdbc.query(sql, r -> new UsageStat(r.str("trigger"), r.longOf("c")), limit);
    }
}
