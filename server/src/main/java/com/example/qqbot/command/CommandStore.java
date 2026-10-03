package com.example.qqbot.command;

import com.example.qqbot.persistence.CommandRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 指令的存储。
 *
 * <p><b>和问答记录共用同一个 SQLite 文件</b>（§app.persistence.db§）—— 都是本地小数据，
 * 没必要为它单独开一个库。表由本类自建，和其他 Store 互不干扰。
 *
 * <p>本类**不缓存**：指令条目是个位数~几十条，每次查一下 SQLite 只要零点几毫秒，
 * 比维护缓存一致性的复杂度划算得多。**而且天然就是"改完立刻生效"** ——
 * 这正是配置界面想要的。
 *
 * <h2>本类只剩「语义」</h2>
 * SQL 与 {@code java.sql} 全部搬进了 {@link CommandRepository}。这里管的是：
 * 内置指令的内容、{@code group_ids} 的 JSON 编解码（列的编码方式留给持久层不合适，
 * 它只该知道"这一列是文本"）、以及把 {@code IOException} 作为对外错误类型。
 */
@Component
public class CommandStore {

    private static final Logger log = LoggerFactory.getLogger(CommandStore.class);

    /** 数据访问全部委托给它 —— SQL 与 java.sql 都在 persistence */
    private final CommandRepository repo;

    /** 只用来编解码 {@code group_ids} 这一列的 JSON */
    private final ObjectMapper mapper;

    private volatile boolean available;

    public CommandStore(CommandRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @PostConstruct
    void init() {
        try {
            if (!repo.isAvailable()) {
                log.warn("[CMD] 问答库不可用，指令功能一并关闭");
                available = false;
                return;
            }
            repo.initSchema();
            seedBuiltins();
            available = true;
            log.info("[CMD] 指令库就绪：{} 条指令", count());
        } catch (Exception e) {
            log.warn("[CMD] 初始化指令库失败，指令功能将不可用（不影响正常问答）：{}", e.getMessage());
            available = false;
        }
    }

    /**
     * 写入内置指令（幂等）。
     *
     * <p>用 §INSERT OR IGNORE§：已经存在就什么都不做 ——
     * 所以你**改过内置指令的回复后，重启不会被覆盖回去**。
     */
    private void seedBuiltins() {
        List<CommandRepository.Seed> builtins = List.of(
                new CommandRepository.Seed("help", "{cmd.list}", "查看所有指令", 1),
                new CommandRepository.Seed("list", "{cmd.list}", "列出所有指令", 2),
                new CommandRepository.Seed("ping", "在的喵～（已运行 {uptime}）", "看看我在不在", 3),
                new CommandRepository.Seed("stats", """
                        知识库：{kb.count} 条资料
                        术语表：{kb.terms} 条
                        累计问答：{qa.total} 次（命中率 {qa.hitrate}）
                        今天已回答：{qa.today} 次""", "查看知识库统计", 4));

        repo.seedBuiltins(builtins, Instant.now().toString());
    }

    // ==================== 查询 ====================

    public boolean isAvailable() {
        return available;
    }

    /** 全部指令（含停用的），按 sort_order 排 */
    public List<BotCommand> listAll() {
        return list(repo::all);
    }

    /** 启用的指令 */
    public List<BotCommand> listEnabled() {
        return list(repo::enabled);
    }

    private List<BotCommand> list(java.util.function.Supplier<List<CommandRepository.Row>> source) {
        if (!available) {
            return List.of();
        }
        try {
            return toCommands(source.get());
        } catch (Exception e) {
            log.warn("[CMD] 查询指令失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按触发词精确查找（**大小写不敏感**）。
     *
     * <p>大小写不敏感是有意的：§/Help§ 和 §/help§ 应该都能用，
     * 不然群友会困惑"为什么大写就不行"。
     */
    public BotCommand findByTrigger(String trigger) {
        if (!available || trigger == null || trigger.isBlank()) {
            return null;
        }
        try {
            CommandRepository.Row r = repo.findByTrigger(trigger.trim());
            return r == null ? null : toCommand(r);
        } catch (Exception e) {
            log.warn("[CMD] 查询指令失败：{}", e.getMessage());
            return null;
        }
    }

    private List<BotCommand> toCommands(List<CommandRepository.Row> rows) {
        List<BotCommand> out = new ArrayList<>(rows.size());
        for (CommandRepository.Row r : rows) {
            out.add(toCommand(r));
        }
        return out;
    }

    private BotCommand toCommand(CommandRepository.Row r) {
        return new BotCommand(r.id(), r.trigger(), r.reply(), r.description(), r.scope(),
                parseGroupIds(r.groupIdsJson()), r.minRole(), r.enabled(), r.sortOrder(),
                r.builtin(), r.kind(), r.mode());
    }

    /** {@code group_ids} 列里是 JSON 数组；坏数据当空处理（旧实现就是这么容错的） */
    private List<Long> parseGroupIds(String raw) {
        List<Long> groups = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return groups;
        }
        try {
            for (var node : mapper.readTree(raw)) {
                groups.add(node.asLong());
            }
        } catch (Exception ignored) {
            // 坏数据当空处理
        }
        return groups;
    }

    public int count() {
        if (!available) {
            return 0;
        }
        try {
            return (int) repo.count();
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 写入 ====================

    /** 新增或更新（按 trigger 判断） */
    public long upsert(BotCommand cmd) throws IOException {
        if (!available) {
            throw new IOException("指令库不可用");
        }
        String now = Instant.now().toString();
        String groupsJson;
        try {
            groupsJson = mapper.writeValueAsString(cmd.groupIds() == null ? List.of() : cmd.groupIds());
        } catch (Exception e) {
            throw new IOException("群列表序列化失败：" + e.getMessage(), e);
        }
        try {
            return repo.upsert(new CommandRepository.Row(0, cmd.trigger(), cmd.reply(),
                    cmd.description(), cmd.scope(), groupsJson, cmd.minRole(), cmd.enabled(),
                    cmd.sortOrder(), false, cmd.kindName(), cmd.modeName()), now);
        } catch (Exception e) {
            throw new IOException("写入指令失败：" + e.getMessage(), e);
        }
    }

    /**
     * 删除。
     *
     * <p><b>内置命令不允许删</b>：它们是 {@code {cmd.list}} 之类变量的数据源，
     * 删掉之后那些变量会静默变空，而管理员只是想"关掉它"（用
     * {@link #setEnabled} 就行）。这条判断是业务规则，所以在这一层。
     */
    public boolean delete(String trigger) throws IOException {
        if (!available) {
            throw new IOException("指令库不可用");
        }
        BotCommand existing = findByTrigger(trigger);
        if (existing == null) {
            return false;
        }
        if (existing.builtin()) {
            throw new IOException("内置指令不能删除（可以改回复内容）");
        }
        try {
            return repo.deleteById(existing.id());
        } catch (Exception e) {
            throw new IOException("删除失败：" + e.getMessage(), e);
        }
    }

    public boolean setEnabled(String trigger, boolean enabled) throws IOException {
        if (!available) {
            throw new IOException("指令库不可用");
        }
        try {
            return repo.setEnabled(trigger, enabled, Instant.now().toString());
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
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
        try {
            repo.logUsage(trigger == null ? "" : trigger.toLowerCase(Locale.ROOT),
                    groupId, userId, matched, Instant.now().toString());
        } catch (Exception e) {
            log.debug("[CMD] 记录使用失败（不影响回复）：{}", e.getMessage());
        }
    }

    /** 使用排行：命中次数 */
    public List<Map<String, Object>> topUsed(int days, int limit) {
        return stats(() -> repo.topUsed(days, limit));
    }

    /** ★ 未配置的命令排行：群友发了但没配 —— 直接告诉你该加什么 */
    public List<Map<String, Object>> unmatched(int days, int limit) {
        return stats(() -> repo.unmatched(days, limit));
    }

    /**
     * 统计结果的对外形状就是 JSON（前端直接吃 {@code [{trigger, count}]}），
     * 所以这里保留 {@code Map} 而不是换个记录 —— 换掉只会让三个调用点跟着改一遍。
     */
    private List<Map<String, Object>> stats(
            java.util.function.Supplier<List<CommandRepository.UsageStat>> source) {
        if (!available) {
            return List.of();
        }
        try {
            List<CommandRepository.UsageStat> rows = source.get();
            List<Map<String, Object>> out = new ArrayList<>(rows.size());
            for (CommandRepository.UsageStat s : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("trigger", s.trigger());
                m.put("count", s.count());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("[CMD] 统计失败：{}", e.getMessage());
            return List.of();
        }
    }
}
