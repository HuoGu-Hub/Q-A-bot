package com.example.qqbot.kb.term;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.persistence.SqliteConnectionProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 词条存储 —— 知识库「名字层」的唯一真源。
 *
 * <h2>它取代了什么</h2>
 * 改造前这份数据是 {@code data/kb/glossary/zh-en.tsv}（5 列 TSV），被**三处代码各自解析**：
 * <ul>
 *   <li>{@code Glossary} —— 运行时 B 路检索加载</li>
 *   <li>{@code GlossaryStore} —— 后台增删改（整表读改写 + 读写锁 + 临时文件 + 原子替换）</li>
 *   <li>{@code KbTermService.buildAll()} —— 组装词条列表时又读一遍</li>
 * </ul>
 * 同一份数据三个解析点、两条写入路径：改一处行为要记得同步另外两处。现在收成一张表。
 *
 * <h2>为什么只存 3 个字段</h2>
 * 原 TSV 的 {@code page} / {@code category} 两列是**纯派生数据**（实测：
 * 3,359 行里 {@code page == en.replace(' ', '_')} 零处不符；
 * {@code category} 全部能在该词条 chunk 的 {@code cats} 里找到）。存派生数据
 * 只会制造「每次写入都必须人工保持一致」的 bug —— 原来后台改个中文名都得把
 * page / category 原样带回来，否则整条 upsert 会把它们抹掉。
 *
 * <h2>key 空间是「Wiki 页面」，不是「有正文的页面」</h2>
 * 表里的行按语料的 {@code docId} 补齐（{@code reconcile}），
 * 于是 {@code Equipment}、{@code Sets/*} 这类**没有正文但需要翻译**的伞形词也在表里。
 * 「有没有正文」由语料现算，不落库；孤儿词条只由 {@code deleteOrphans} 显式清理。
 *
 * <h2>和 {@link com.example.qqbot.kb.category.CategoryStore} 一样</h2>
 * 共用问答库那个 SQLite 连接（{@code db.connection()}），
 * 库不可用时整体降级为「没有术语表」（B 路退化成只认英文词），**绝不让机器人挂掉**。
 */
@Component
@DependsOn("qaStore")
public class KbTermStore {

    private static final Logger log = LoggerFactory.getLogger(KbTermStore.class);

    /** 合法状态。认不出来的一律归 draft —— 和旧 GlossaryStore 同一口径 */
    public static final List<String> STATUSES = List.of("draft", "verified", "rejected");

    private final SqliteConnectionProvider db;
    private final KbProperties props;
    private final ObjectMapper mapper;
    private final com.example.qqbot.kb.KbCorpus corpus;

    private volatile boolean available;

    /**
     * 每次写入 +1，供 {@link com.example.qqbot.kb.Glossary} 判断"我缓存的那一版是不是旧的"。
     *
     * <p>这样**调用方不需要记得调 reload()** —— 忘了就是线上一直在用旧词，
     * 而且不报错。旧实现靠每个写方法末尾手动 {@code glossary.reload()}，
     * 新增一个写入口就漏一个。
     */
    private final AtomicLong version = new AtomicLong();

    public KbTermStore(SqliteConnectionProvider db, KbProperties props, ObjectMapper mapper,
                       com.example.qqbot.kb.KbCorpus corpus) {
        this.db = db;
        this.props = props;
        this.mapper = mapper;
        this.corpus = corpus;
    }

    /** 一条词条。{@code status} 空串表示"还没有中文名/没收录"，见 {@link #STATUSES} */
    public record Entry(String en, String zh, String status) {
    }

    /** 批量改状态的结果：真改了几条 / 本来就是目标状态几条 / 没对上的英文名 */
    public record BatchResult(int updated, int unchanged, List<String> missing) {
    }

    /** 导入 TSV 的结果 */
    public record ImportResult(int created, int updated, int skipped) {
    }

    public boolean isAvailable() {
        return available;
    }

    public long version() {
        return version.get();
    }

    private Connection conn() {
        return db.connection();
    }

    // ==================== 建表 + 补齐 ====================

    /** 初始化（public 以便测试显式调用，和 QaStore 一致（它也是连接持有者）） */
    @PostConstruct
    public void init() {
        try {
            if (!db.isAvailable()) {
                log.warn("[KB-TERM] 问答库不可用，词条功能关闭（B 路退化为只认英文词）");
                return;
            }
            try (Statement st = conn().createStatement()) {
                // 用普通字符串而不是文本块 —— 与 CategoryStore 保持一致，文本块的结束符
                // 必须独占一行，生成代码时极易写错
                st.execute("CREATE TABLE IF NOT EXISTS kb_term ("
                        + " en         TEXT PRIMARY KEY,"
                        + " zh         TEXT NOT NULL DEFAULT '',"
                        + " status     TEXT NOT NULL DEFAULT 'draft',"
                        + " updated_at TEXT NOT NULL DEFAULT '')");
                st.execute("CREATE INDEX IF NOT EXISTS idx_term_status ON kb_term(status)");
            }
            available = true;
            log.info("[KB-TERM] 存储就绪：{} 条词条", count());
            // 顺序很重要：先搬旧 TSV（只在表为空时），再按语料补齐页面。
            // 反过来的话表已经不空了，旧数据就永远搬不过来。
            int seeded = reconcile();
            if (seeded > 0) {
                log.info("[KB-TERM] 从语料补齐 {} 个页面（新增的页面第一次有了中文名的位置）", seeded);
            }
        } catch (Exception e) {
            log.warn("[KB-TERM] 初始化失败（不影响问答）：{}", e.getMessage());
            available = false;
        }
    }

    /**
     * 一次性搬迁：把旧格式的 TSV（{@code data/kb/glossary/zh-en.tsv}）读进表里。
     *
     * <p><b>只在表为空时做</b>。理由：表非空就说明已经搬过了（或已经进入新模型），
     * 再搬一次会把人工核对过的状态用旧文件的初稿覆盖掉 —— 那是真丢数据。
     *
     * <p>搬完之后 TSV 就只是"可以导出/导入的中间格式"，不再是数据源；
     * 留着它不影响任何行为。
     *
     * @return 导入了多少条（0 = 不需要搬）
     */
    
    /**
     * 按**当前语料**把缺的词条补进表里（缺的才插入）。
     *
     * <p>语料从哪来不归它管：它只认 {@link com.example.qqbot.kb.KbCorpus}
     * （旧实现读的是已归档的 {@code pages.jsonl}，随迁移一起删掉了 —— 这里曾留下过时描述）。
     *
     * <p>幂等，重复执行无副作用。放在启动时跑一次的意义：重新爬过语料之后，
     * 新页面自动获得"可以给它起中文名"的位置，不需要任何额外操作。
     *
     * <p>⚠️ 只在 {@code init()} 时跑一次 —— 所以**运行期新导入的块，要等下次启动才会进词条表**。
     * 这就是导入器在写完之后要显式再调一次它的原因（见 {@code WikiArticleImporter}）。
     *
     * @return 本次新插入的行数
     */
    public synchronized int reconcile() {
        if (!available) {
            return 0;
        }
        List<String[]> titles = knownPages();
        if (titles.isEmpty()) {
            return 0;
        }
        int before = (int) count();
        synchronized (this) {
            try {
                conn().setAutoCommit(false);
                try (PreparedStatement ps = conn().prepareStatement(
                        // 已存在的一行都不动：中文名和核对状态是人工成果，绝不能被语料重建冲掉
                        "INSERT OR IGNORE INTO kb_term (en, zh, status, updated_at) VALUES (?,?,?,?)")) {
                    String now = Instant.now().toString();
                    for (String[] t : titles) {
                        ps.setString(1, t[0]);
                        // 顺手给一个**建议中文名**：名字留空的话，这些页面在词条列表里
                        // 只是"待起名的空行"，关键词路也永远认不出它们。
                        // 有人工核对过的名字时不会走到这里（INSERT OR IGNORE）。
                        ps.setString(2, t[1]);
                        ps.setString(3, "draft");
                        ps.setString(4, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn().commit();
            } catch (Exception e) {
                rollbackQuietly();
                log.warn("[KB-TERM] 补齐页面失败：{}", e.getMessage());
                return 0;
            } finally {
                autoCommitOn();
            }
        }
        int added = (int) count() - before;
        if (added > 0) {
            version.incrementAndGet();
        }
        return added;
    }

    /**
     * 删掉**没有对应块**的词条（孤儿行）。
     *
     * <p>什么时候会长出这种行：块被删了，或者早期版本按"一份文档一个词条"建过一批。
     * 它们在列表里永远显示 0 块，是纯噪音。
     *
     * <p><b>为什么做成手动动作而不是启动时自动清</b>：手工加的、还没导入正文的名字
     * 也长这样（"先起个名字、正文以后再导"）—— 自动清会把它一起删掉。
     *
     * @param knownBlockIds 现在库里有哪些块 id
     * @return 删掉几行
     */
    public int deleteOrphans(java.util.Set<String> knownBlockIds) {
        if (!available) {
            return 0;
        }
        List<String> orphans = new ArrayList<>();
        for (Entry e : list()) {
            if (!knownBlockIds.contains(e.en())) {
                orphans.add(e.en());
            }
        }
        if (orphans.isEmpty()) {
            return 0;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement("DELETE FROM kb_term WHERE en = ?")) {
                for (String en : orphans) {
                    ps.setString(1, en);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (Exception e) {
                log.warn("[KB-TERM] 清理孤儿词条失败：{}", e.getMessage());
                return 0;
            }
            version.incrementAndGet();
            log.warn("[KB-TERM] 已清掉 {} 条没有对应块的词条", orphans.size());
            return orphans.size();
        }
    }

    /**
     * 语料里有哪些页面，以及**给每页建议的中文名**。
     *
     * <p>为什么建议名要在这里算：词条表里一行如果中文名留空，它就只是"待起名的空行" ——
     * 词条列表看着像没名字，关键词路（{@link com.example.qqbot.kb.Glossary} 只读词条表）
     * 也永远认不出这一页。所以补齐时顺手给个能用的名字。
     *
     * <p>取名规则：**文档身份本身是中文就用它**（很多文档的 name 就是中文标题，
     * 如「水体系统与建造（Water）」）；否则退回这一页第一个块的标题。
     * 都不合适也没关系 —— 人可以在「词条」面板里改，改过之后 INSERT OR IGNORE 不会再动它。
     *
     * @return 每项 {docId, 建议中文名}，按 docId 去重
     */
    private List<String[]> knownPages() {
        List<String[]> out = new ArrayList<>();
        try {
            for (com.example.qqbot.kb.KbCorpus.Entry e : corpus.entries()) {
                // **一块一条**：词条的身份就是块 id，中文名就是块标题
                String key = e.id() == null ? "" : e.id().trim();
                if (key.isEmpty()) {
                    continue;
                }
                String zh = termName(e.title());
                out.add(new String[]{key, zh.isEmpty() ? key : zh});
            }
        } catch (Exception e) {
            log.warn("[KB-TERM] 从语料取块失败：{}", e.getMessage());
        }
        return out;
    }

    /**
     * 块标题 → 词条的中文名。
     *
     * <p>实测语料里大量是「深渊之翼斧（Abyssal Wing Axe）」这种写法。把它拆成
     * 「深渊之翼斧、Abyssal Wing Axe」：
     * <b>短的中文名才是关键词路能命中的东西</b> —— 词表判定"命中"靠的是
     * "这个名字出现在问句里"，而群友不会把整条带英文的长标题说出来。
     *
     * <p>括号不在结尾（比如「A（B）C」）就原样保留，不乱动。
     */
    public static String termName(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*?)[（(]([^）)]+)[）)]$").matcher(t);
        if (m.matches()) {
            String zh = m.group(1).trim();
            String en = m.group(2).trim();
            if (!zh.isEmpty() && !en.isEmpty()) {
                return zh + "、" + en;
            }
        }
        return t;
    }

    /** 含中日韩汉字？用来判断"这个名字本身是不是中文" */
    private static boolean hasCjk(String s) {
        return s != null && s.chars().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff);
    }

    // ==================== 读 ====================

    /**
     * 全部词条（含 rejected —— 后台要展示它们）。
     *
     * <p>读也加锁：所有方法共用 {@code db.connection()} 这一条 JDBC 连接，
     * 而批量写会把它切成手动提交。读出到半个事务里是"看起来偶发"的那类 bug，
     * 花一次锁把它按住更省事。
     */
    public synchronized List<Entry> list() {
        if (!available) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT en, zh, status FROM kb_term")) {
            while (rs.next()) {
                out.add(new Entry(rs.getString(1), nz(rs.getString(2)), normalizeStatus(rs.getString(3))));
            }
        } catch (Exception e) {
            log.warn("[KB-TERM] 读取失败：{}", e.getMessage());
        }
        return out;
    }

    /** 按英文名索引 —— 列表组装用，避免每条都查一次库 */
    public synchronized Map<String, Entry> map() {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Entry e : list()) {
            out.put(e.en(), e);
        }
        return out;
    }

    public synchronized Entry get(String en) {
        if (!available || en == null || en.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT en, zh, status FROM kb_term WHERE en = ?")) {
            ps.setString(1, en);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new Entry(rs.getString(1), nz(rs.getString(2)), normalizeStatus(rs.getString(3)))
                        : null;
            }
        } catch (Exception e) {
            log.warn("[KB-TERM] 查询失败：{}", e.getMessage());
            return null;
        }
    }

    public synchronized long count() {
        if (!available) {
            return 0;
        }
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM kb_term")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 写 ====================

    private static final String UPSERT =
            "INSERT INTO kb_term (en, zh, status, updated_at) VALUES (?,?,?,?) "
            + "ON CONFLICT(en) DO UPDATE SET "
            + "zh = excluded.zh, status = excluded.status, updated_at = excluded.updated_at";

    /**
     * 新增或更新一条。写完 {@link #version()} 自增，检索侧下一问就用到新词。
     *
     * <p>用 upsert 而不是 update：语料重建后可能出现表里还没有的页面，
     * 这时直接给它起个中文名也应该成立。
     */
    public boolean upsert(String en, String zh, String status) {
        if (!available || en == null || en.isBlank()) {
            return false;
        }
        String name = en.trim();
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(UPSERT)) {
                ps.setString(1, name);
                ps.setString(2, zh == null ? "" : zh.trim());
                ps.setString(3, normalizeStatus(status));
                ps.setString(4, Instant.now().toString());
                ps.executeUpdate();
            } catch (Exception e) {
                log.warn("[KB-TERM] 保存失败：{}", e.getMessage());
                return false;
            }
        }
        version.incrementAndGet();
        return true;
    }

    /** 只改状态（核对时最常用：draft → verified）。不存在返回 false */
    public boolean setStatus(String en, String status) {
        if (!available || en == null) {
            return false;
        }
        int n;
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE kb_term SET status = ?, updated_at = ? WHERE en = ?")) {
                ps.setString(1, normalizeStatus(status));
                ps.setString(2, Instant.now().toString());
                ps.setString(3, en);
                n = ps.executeUpdate();
            } catch (Exception e) {
                log.warn("[KB-TERM] 改状态失败：{}", e.getMessage());
                return false;
            }
        }
        if (n > 0) {
            version.incrementAndGet();
        }
        return n > 0;
    }

    /**
     * 批量改状态 —— 核对模式的快路径，**一个事务**。
     *
     * <p>旧实现是一条一个 {@code UPDATE} 加一次全表重写，勾 200 条就重写 200 遍文件。
     */
    public BatchResult setStatusBatch(List<String> ens, String status) {
        if (!available || ens == null || ens.isEmpty()) {
            return new BatchResult(0, 0, List.of());
        }
        String wanted = normalizeStatus(status);
        int updated = 0;
        int unchanged = 0;
        List<String> missing = new ArrayList<>();
        synchronized (this) {
            try {
                conn().setAutoCommit(false);
                try (PreparedStatement read = conn().prepareStatement(
                             "SELECT status FROM kb_term WHERE en = ?");
                     PreparedStatement write = conn().prepareStatement(
                             "UPDATE kb_term SET status = ?, updated_at = ? WHERE en = ?")) {
                    String now = Instant.now().toString();
                    for (String en : ens) {
                        if (en == null || en.isBlank()) {
                            continue;
                        }
                        String cur = null;
                        read.setString(1, en);
                        try (ResultSet rs = read.executeQuery()) {
                            if (rs.next()) {
                                cur = normalizeStatus(rs.getString(1));
                            }
                        }
                        if (cur == null) {
                            missing.add(en);
                        } else if (cur.equals(wanted)) {
                            // 已经是目标状态：跳过。写进去也没区别，反而让"改了几条"失真
                            unchanged++;
                        } else {
                            write.setString(1, wanted);
                            write.setString(2, now);
                            write.setString(3, en);
                            write.executeUpdate();
                            updated++;
                        }
                    }
                }
                conn().commit();
            } catch (Exception e) {
                rollbackQuietly();
                log.warn("[KB-TERM] 批量改状态失败：{}", e.getMessage());
                return new BatchResult(0, 0, missing);
            } finally {
                autoCommitOn();
            }
        }
        if (updated > 0) {
            version.incrementAndGet();
        }
        return new BatchResult(updated, unchanged, missing);
    }

    /**
     * 清掉一条的中文名（状态回到 draft）。
     *
     * <p><b>不是删行</b> —— 行代表「这个 Wiki 页面在词条表里有位置」，
     * 删掉它下次启动又会被 {@link #reconcile()} 补回来，徒增困惑。
     */
    public boolean clearName(String en) {
        if (!available || en == null) {
            return false;
        }
        int n;
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE kb_term SET zh = '', status = 'draft', updated_at = ? WHERE en = ?")) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, en);
                n = ps.executeUpdate();
            } catch (Exception e) {
                log.warn("[KB-TERM] 清除失败：{}", e.getMessage());
                return false;
            }
        }
        if (n > 0) {
            version.incrementAndGet();
        }
        return n > 0;
    }

    /**
     * 导入术语表（Excel 旁路）—— CSV / TSV 自动识别，见 {@link #importTable}。
     */
    public ImportResult importTsv(String text) {
        return importTable(text);
    }

    /**
     * 清空**全部**词条（**不可逆**）。
     *
     * <p><b>为什么必须走这里、不能外部直接改库</b>：词表
     * （{@link com.example.qqbot.kb.Glossary}）靠 {@link #version()} 感知变化。
     * 外部进程 DELETE 那些行，版本号不会动 —— 机器人会**继续用旧的 3,000 多条名字**
     * 做关键词检索，而且不报错。走这里版本号 +1，词表下次访问自动重载。
     *
     * @return 删掉了几条
     */
    public int deleteAll() {
        if (!available) {
            return 0;
        }
        synchronized (this) {
            int n = (int) count();
            try (java.sql.Statement st = conn().createStatement()) {
                st.executeUpdate("DELETE FROM kb_term");
            } catch (Exception e) {
                log.warn("[KB-TERM] 清空失败：{}", e.getMessage());
                return 0;
            }
            version.incrementAndGet();
            log.warn("[KB-TERM] 已清空全部词条：{} 条（不可逆）", n);
            return n;
        }
    }

    /**
     * 导入一张术语表：**CSV 或 TSV 自动识别，表头驱动**。
     *
     * <h2>认的表头（中英文都认，忽略大小写与空格）</h2>
     * <ul>
     *   <li>英文名：{@code 英文名 / 英文 / en / id / english / 页面 / wiki页面}（必需）</li>
     *   <li>中文名：{@code 中文名 / 中文 / zh / chinese / 译名}（必需）</li>
     *   <li>别名：{@code 别名 / 别称 / alias / aliases / 其他译名}（可选）</li>
     *   <li>状态：{@code 状态 / status}（可选，默认 draft）</li>
     * </ul>
     *
     * <h2>别名为什么不需要新列</h2>
     * 库里 {@code zh} 本来就是"若干种中文叫法用 {@code 、} 分隔"，
     * 见 {@link com.example.qqbot.kb.Glossary#matchChinese}。所以导入时把
     * 「别名」列并进 {@code zh} 即可 —— 加一列反而要同时改查询扩展与界面，
     * 而语义完全一样。
     *
     * <p>没有表头（旧导出文件）时退回 {@link #importLegacyTsv} 按列位置解析。
     */
    public ImportResult importTable(String text) {
        if (!available || text == null || text.isBlank()) {
            return new ImportResult(0, 0, 0);
        }
        char delim = CsvTable.sniffDelimiter(text);
        List<List<String>> rows = CsvTable.parse(text, delim);

        // 表头可能不在第一行（前面有注释/空行），所以看前几行
        List<String> header = null;
        int headerAt = -1;
        for (int i = 0; i < Math.min(rows.size(), 5); i++) {
            List<String> r = rows.get(i);
            if (col(r, "英文名", "英文", "en", "id", "english", "页面", "wiki页面") >= 0
                    && col(r, "中文名", "中文", "zh", "chinese", "译名") >= 0) {
                header = r;
                headerAt = i;
                break;
            }
        }
        if (header == null) {
            return importLegacyTsv(text);
        }

        int iEn = col(header, "英文名", "英文", "en", "id", "english", "页面", "wiki页面");
        int iZh = col(header, "中文名", "中文", "zh", "chinese", "译名");
        int iAlias = col(header, "别名", "别称", "alias", "aliases", "其他译名");
        int iStatus = col(header, "状态", "status");

        int created = 0;
        int updated = 0;
        int skipped = 0;
        Map<String, Entry> existing = map();
        String now = Instant.now().toString();
        synchronized (this) {
            try {
                conn().setAutoCommit(false);
                try (PreparedStatement ps = conn().prepareStatement(UPSERT)) {
                    for (int i = headerAt + 1; i < rows.size(); i++) {
                        List<String> r = rows.get(i);
                        if (r.isEmpty() || (r.size() == 1 && r.get(0).isBlank())) {
                            continue;
                        }
                        if (cell(r, 0).startsWith("#")) {
                            continue;
                        }
                        String en = cell(r, iEn);
                        if (en.isEmpty()) {
                            skipped++;
                            continue;
                        }
                        ps.setString(1, en);
                        ps.setString(2, mergeAliases(cell(r, iZh), cell(r, iAlias)));
                        String status = cell(r, iStatus);
                        ps.setString(3, normalizeStatus(status.isEmpty() ? "draft" : status));
                        ps.setString(4, now);
                        ps.addBatch();
                        if (existing.containsKey(en)) {
                            updated++;
                        } else {
                            created++;
                        }
                    }
                    ps.executeBatch();
                }
                conn().commit();
            } catch (Exception e) {
                rollbackQuietly();
                log.warn("[KB-TERM] 导入失败：{}", e.getMessage());
                return new ImportResult(0, 0, 0);
            } finally {
                autoCommitOn();
            }
        }
        if (created + updated > 0) {
            version.incrementAndGet();
            log.info("[KB-TERM] 导入完成（{}）：新增 {}，更新 {}，跳过 {}",
                    delim == '\t' ? "TSV" : "CSV", created, updated, skipped);
        }
        return new ImportResult(created, updated, skipped);
    }

    /** 在表头里找第一个匹配的列号（中英文都认，忽略大小写与空格） */
    private static int col(List<String> header, String... names) {
        if (header == null) {
            return -1;
        }
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i) == null ? "" : header.get(i).trim().toLowerCase().replace(" ", "");
            for (String n : names) {
                if (h.equals(n)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) {
            return "";
        }
        String s = row.get(idx);
        return s == null ? "" : s.trim();
    }

    /**
     * 把「别名」列并进中文名。
     *
     * <p>别名在库里就是 {@code zh} 里用 {@code 、} 分隔的若干写法；Excel 里
     * 别名那一格多半用逗号或斜杠分隔，所以先统一成 {@code 、} 再去重。
     */
    private static String mergeAliases(String zh, String alias) {
        String base = zh == null ? "" : zh.trim();
        String extra = alias == null ? "" : alias.trim();
        if (extra.isEmpty()) {
            return base;
        }
        extra = extra.replace(',', '、').replace('，', '、')
                .replace('/', '、').replace('|', '、').replace('｜', '、');
        java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<>();
        for (String p : (base + "、" + extra).split("、")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                parts.add(t);
            }
        }
        return String.join("、", parts);
    }

    /**
     * 按**列位置**解析（没有表头的旧文件）：{@code 英文名 <TAB> 中文名 <TAB> ... <TAB> 状态}。
     *
     * <p>中间两列（wiki页面 / 类别）是导出时给人看的派生信息，**导入时一律忽略** ——
     * 认它们就等于又把派生数据当成了真数据。
     */
    private ImportResult importLegacyTsv(String text) {
        if (!available || text == null || text.isBlank()) {
            return new ImportResult(0, 0, 0);
        }
        int created = 0;
        int updated = 0;
        int skipped = 0;
        Map<String, Entry> existing = map();
        String now = Instant.now().toString();
        synchronized (this) {
            try {
                conn().setAutoCommit(false);
                try (PreparedStatement ps = conn().prepareStatement(UPSERT)) {
                    for (String line : text.split("\r?\n")) {
                        if (line.isBlank() || line.charAt(0) == '#') {
                            continue;
                        }
                        String[] c = line.split("\t", -1);
                        if (c.length < 2) {
                            skipped++;
                            continue;
                        }
                        String en = c[0].trim();
                        String zh = c[1].trim();
                        String status = c.length >= 5 ? c[4].trim() : "draft";
                        if (en.isEmpty()) {
                            skipped++;
                            continue;
                        }
                        ps.setString(1, en);
                        ps.setString(2, zh);
                        ps.setString(3, normalizeStatus(status));
                        ps.setString(4, now);
                        ps.addBatch();
                        if (existing.containsKey(en)) {
                            updated++;
                        } else {
                            created++;
                        }
                    }
                    ps.executeBatch();
                }
                conn().commit();
            } catch (Exception e) {
                rollbackQuietly();
                log.warn("[KB-TERM] 导入失败：{}", e.getMessage());
                return new ImportResult(0, 0, 0);
            } finally {
                autoCommitOn();
            }
        }
        if (created + updated > 0) {
            version.incrementAndGet();
            log.info("[KB-TERM] 导入完成：新增 {}，更新 {}，跳过 {}", created, updated, skipped);
        }
        return new ImportResult(created, updated, skipped);
    }

    // ==================== 工具 ====================

    /** 空值或认不出来的值一律算 draft —— 和旧 GlossaryStore 同一口径 */
    public static String normalizeStatus(String s) {
        if (s == null || s.isBlank()) {
            return "draft";
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        return STATUSES.contains(v) ? v : "draft";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void rollbackQuietly() {
        try {
            conn().rollback();
        } catch (SQLException ignored) {
            // 回滚失败也没什么可做的
        }
    }

    private void autoCommitOn() {
        try {
            conn().setAutoCommit(true);
        } catch (SQLException ignored) {
            // 同上
        }
    }
}
