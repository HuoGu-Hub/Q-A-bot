package com.example.qqbot.command;

import com.example.qqbot.persistence.CommandRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    /** 新库是否种内置指令；后台/生产默认关，测试直接构造时保留种子 */
    private final boolean seedDefaults;

    @Autowired
    public CommandStore(CommandRepository repo, ObjectMapper mapper,
                        @Value("${app.commands.seed-defaults:false}") boolean seedDefaults) {
        this.repo = repo;
        this.mapper = mapper;
        this.seedDefaults = seedDefaults;
    }

    /** 测试用：默认允许种子，保证原有的种子/目录用例不必自己造数据 */
    CommandStore(CommandRepository repo, ObjectMapper mapper) {
        this(repo, mapper, true);
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
            // 种子只播一次：清空命令库后重启不能再把旧内置命令塞回来
            if (seedDefaults && !repo.hasMeta("command-seed-retired")) {
                seedBuiltins();
                repo.setMeta("command-seed-retired", Instant.now().toString());
            }
            retireLegacyBuiltins();
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
        // help / list 已经由板块系统接管（BoardRunner 先于指令表匹配），
        // 再种进指令表只会得到两条永远发不出去、后台却看得见的"死指令"。
        List<CommandRepository.Seed> builtins = List.of(
                new CommandRepository.Seed("ping", "在的～（已运行 {uptime}）", "看看我在不在", 3, "[]", null),
                new CommandRepository.Seed("stats", """
                        知识库：{kb.count} 条资料
                        术语表：{kb.terms} 条
                        累计问答：{qa.total} 次（命中率 {qa.hitrate}）
                        今天已回答：{qa.today} 次""", "查看知识库统计", 4, "[]", null));

        repo.seedBuiltins(builtins, Instant.now().toString());
    }

    /**
     * 清掉两类历史内置指令。
     *
     * <h2>为什么必须在启动时做</h2>
     * ① {@code help} / {@code list}：板块系统接管后，BoardRunner 在指令表之前匹配，
     *    这两条永远轮不到；后台却还显示、还能编辑，改了也没有任何效果。
     * ② {@code set/list/del output}：旧定时任务链路已经移除，它们的 action 指向
     *    已不存在的执行器，{@code kind} 又停在 template，实际只剩一句"写法"提示。
     *    新的定时能力在「公告 / 任务」板块里。
     *
     * <p>只删 {@code builtin=1} 的行：管理员自己建的、恰好叫这些名字的指令不动。
     */
    private void retireLegacyBuiltins() {
        List<String> retired = List.of("help", "list", "set/output", "list/output", "del/output");
        for (String trigger : retired) {
            try {
                if (repo.deleteBuiltin(trigger) > 0) {
                    log.info("[CMD] 已移除被板块接管/旧定时链路的内置指令：/{}", trigger);
                }
            } catch (Exception e) {
                log.warn("[CMD] 移除历史内置指令 /{} 失败（不影响启动）：{}", trigger, e.getMessage());
            }
        }
    }

    /** 把内置命令的参数表序列化成库里那一列 */
    private String paramsJson(ParamSpec... specs) {
        try {
            return mapper.writeValueAsString(List.of(specs));
        } catch (Exception e) {
            log.warn("[CMD] 内置命令参数表序列化失败，按无参数处理：{}", e.getMessage());
            return "[]";
        }
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

    public BotCommand findById(long id) {
        if (!available || id <= 0) {
            return null;
        }
        try {
            CommandRepository.Row r = repo.findById(id);
            return r == null ? null : toCommand(r);
        } catch (Exception e) {
            log.warn("[CMD] 按 id 查询指令失败：{}", e.getMessage());
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
                parseGroupIds(r.groupIdsJson()), r.access(), r.enabled(), r.sortOrder(),
                r.builtin(), r.kind(), r.mode(), parseParams(r.paramsJson()), r.help(), r.action());
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

    /**
     * {@code params} 列里是 JSON 数组；坏数据当空处理。
     *
     * <p>与 {@code group_ids} 同一个容错口径：一条命令的参数表坏了，
     * 不该让它整条消失 —— 退化成"不吃参数"至少还能用。
     */
    private List<ParamSpec> parseParams(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(raw, new TypeReference<List<ParamSpec>>() { });
        } catch (Exception e) {
            log.warn("[CMD] 参数表解析失败，按无参数处理：{}", e.getMessage());
            return List.of();
        }
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
        String paramsJson;
        try {
            paramsJson = mapper.writeValueAsString(cmd.params() == null ? List.of() : cmd.params());
        } catch (Exception e) {
            throw new IOException("参数表序列化失败：" + e.getMessage(), e);
        }
        try {
            return repo.upsert(new CommandRepository.Row(0, cmd.trigger(), cmd.reply(),
                    cmd.description(), cmd.scope(), groupsJson, cmd.access(), cmd.enabled(),
                    cmd.sortOrder(), false, cmd.kindName(), cmd.modeName(),
                    paramsJson, cmd.help(), cmd.action()), now);
        } catch (Exception e) {
            throw new IOException("写入指令失败：" + e.getMessage(), e);
        }
    }

    /**
     * 按主键保存，允许改 trigger。
     *
     * <p>命令名就是调用名：改名必须原地改。走 {@link #upsert} 会因为新 trigger
     * 查不到而 INSERT 一条新命令，旧调用名继续生效。
     */
    public long saveExisting(long id, BotCommand cmd) throws IOException {
        if (!available) {
            throw new IOException("指令库不可用");
        }
        if (id <= 0) {
            throw new IOException("缺少指令 id");
        }
        String now = Instant.now().toString();
        String groupsJson;
        try {
            groupsJson = mapper.writeValueAsString(cmd.groupIds() == null ? List.of() : cmd.groupIds());
        } catch (Exception e) {
            throw new IOException("群列表序列化失败：" + e.getMessage(), e);
        }
        String paramsJson;
        try {
            paramsJson = mapper.writeValueAsString(cmd.params() == null ? List.of() : cmd.params());
        } catch (Exception e) {
            throw new IOException("参数表序列化失败：" + e.getMessage(), e);
        }
        try {
            int updated = repo.updateById(id, new CommandRepository.Row(0, cmd.trigger(), cmd.reply(),
                    cmd.description(), cmd.scope(), groupsJson, cmd.access(), cmd.enabled(),
                    cmd.sortOrder(), false, cmd.kindName(), cmd.modeName(),
                    paramsJson, cmd.help(), cmd.action()), now);
            if (updated == 0) {
                throw new IOException("指令不存在：id=" + id);
            }
            return id;
        } catch (IOException e) {
            throw e;
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

    /** 改名后把历史使用记录也迁到新名字下面 */
    public void renameUsage(String oldTrigger, String newTrigger) {
        if (!available || oldTrigger == null || newTrigger == null) {
            return;
        }
        try {
            repo.renameUsage(oldTrigger, newTrigger.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            log.warn("[CMD] 迁移使用记录失败（不影响改名）：{}", e.getMessage());
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
