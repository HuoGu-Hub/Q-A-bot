package com.example.qqbot.kb.term;

import com.example.qqbot.persistence.KbTermRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 表里的行按语料的 {@code docId} 补齐（{@link #reconcile}），
 * 于是 {@code Equipment}、{@code Sets/*} 这类**没有正文但需要翻译**的伞形词也在表里。
 * 「有没有正文」由语料现算，不落库；孤儿词条只由 {@link #deleteOrphans} 显式清理。
 *
 * <h2>本类只剩「语义」</h2>
 * SQL 与 {@code java.sql} 全部搬进了 {@link KbTermRepository}。这里管的是：
 * 状态口径、版本号（{@link #version()}）、降级策略、以及导入格式的解析
 * （CSV/TSV 的表头识别与别名合并是**给人看的格式**，不是数据访问）。
 *
 * <h2>和 {@link com.example.qqbot.kb.category.CategoryStore} 一样</h2>
 * 共用问答库那条 SQLite 连接，库不可用时整体降级为「没有术语表」
 * （B 路退化成只认英文词），**绝不让机器人挂掉**。
 */
@Component
public class KbTermStore {

    private static final Logger log = LoggerFactory.getLogger(KbTermStore.class);

    /**
     * 合法状态。认不出来的一律归 draft —— 和旧 GlossaryStore 同一口径。
     *
     * <p>取值域定义在 {@link KbTermRepository}：那是 {@code status} 这一列的事，
     * 而且批量改状态要在事务里比较归一化后的值。这里转发一次，免得两处各写一份、
     * 改一处忘一处。
     */
    public static final List<String> STATUSES = KbTermRepository.STATUSES;

    /** 数据访问全部委托给它 —— SQL 与 java.sql 都在 persistence */
    private final KbTermRepository repo;

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

    /** 上一次按语料补齐时，语料是哪个版本（{@code -1} = 还没补过）—— 见 {@link #ensureReconciled()} */
    private volatile long reconciledVersion = -1L;

    public KbTermStore(KbTermRepository repo, com.example.qqbot.kb.KbCorpus corpus) {
        this.repo = repo;
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

    // ==================== 建表 + 补齐 ====================

    /** 初始化（public 以便测试显式调用，和 QaStore 一致（它也是连接持有者）） */
    @PostConstruct
    public void init() {
        try {
            if (!repo.isAvailable()) {
                log.warn("[KB-TERM] 问答库不可用，词条功能关闭（B 路退化为只认英文词）");
                return;
            }
            repo.initSchema();
            available = true;
            log.info("[KB-TERM] 存储就绪：{} 条词条", repo.count());
            // 顺序很重要：先建表，再按语料补齐页面
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
     * 按**当前语料**把缺的词条补进表里（缺的才插入）。
     *
     * <p>语料从哪来不归它管：它只认 {@link com.example.qqbot.kb.KbCorpus}
     * （旧实现读的是已归档的 {@code pages.jsonl}，随迁移一起删掉了 —— 这里曾留下过时描述）。
     *
     * <p>幂等，重复执行无副作用。放在启动时跑一次的意义：重新爬过语料之后，
     * 新页面自动获得"可以给它起中文名"的位置，不需要任何额外操作。
     *
     * <p><b>运行期不用调用方操心</b>（2026-10-08 改）：{@link #list()} 会先比
     * {@link com.example.qqbot.kb.KbCorpus#version()}，发现语料比自己上次补齐时新就顺手补一次 ——
     * 所以导入器/派生器**不再需要**在写完之后显式调它。
     *（旧做法就是让它们显式调：迁移前共 4 处，新增一个写入口就漏一个，
     *而漏了的症状是「新页面没有中文名的位置」——B 路搜不到，也不报错。）
     *
     * <p>「新增了几条」靠比较补齐前后的行数得出：{@code INSERT OR IGNORE} 只报提交行数，
     * 分不出哪些被忽略了 —— 而"不忽略"正是这里必须保住的东西（人工成果优先）。
     *
     * @return 本次新插入的行数
     */
    public int reconcile() {
        if (!available) {
            return 0;
        }
        // ⚠️ 先取版本、再读语料（和 KbBlockIndex.ensureLoaded 同一条规矩）
        long corpusVersion = corpus.version();
        List<KbTermRepository.Row> rows = knownPages();
        if (rows.isEmpty()) {
            reconciledVersion = corpusVersion;   // 这一版语料确实没东西可补
            return 0;
        }
        long before = repo.count();
        try {
            repo.insertMissing(rows, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[KB-TERM] 补齐页面失败：{}", e.getMessage());
            return 0;   // 失败**不记版本** → 下次访问还会再试
        }
        int added = (int) (repo.count() - before);
        reconciledVersion = corpusVersion;
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
        try {
            repo.deleteByEn(orphans);
        } catch (Exception e) {
            log.warn("[KB-TERM] 清理孤儿词条失败：{}", e.getMessage());
            return 0;
        }
        version.incrementAndGet();
        log.warn("[KB-TERM] 已清掉 {} 条没有对应块的词条", orphans.size());
        return orphans.size();
    }

    /**
     * 语料里有哪些页面，以及**给每页建议的中文名**。
     *
     * <p>为什么建议名要在这里算：词条表里一行如果中文名留空，它就只是"待起名的空行" ——
     * 词条列表看着像没名字，关键词路（{@link com.example.qqbot.kb.Glossary} 只读词条表）
     * 也永远认不出这一页。所以补齐时顺手给个能用的名字。
     *
     * <p>取名规则：**块标题里带括号的中文名就拆出来**（很多文档的标题是
     * 「深渊之翼斧（Abyssal Wing Axe）」这种写法）；拆不出来就原样用标题。
     * 都不合适也没关系 —— 人可以在「词条」面板里改，改过之后 INSERT OR IGNORE 不会再动它。
     *
     * @return 每项 {块 id, 建议中文名}，状态一律 draft
     */
    private List<KbTermRepository.Row> knownPages() {
        List<KbTermRepository.Row> out = new ArrayList<>();
        try {
            for (com.example.qqbot.kb.KbCorpus.Entry e : corpus.entries()) {
                // **一块一条**：词条的身份就是块 id，中文名就是块标题
                String key = e.id() == null ? "" : e.id().trim();
                if (key.isEmpty()) {
                    continue;
                }
                String zh = termName(e.title());
                out.add(new KbTermRepository.Row(key, zh.isEmpty() ? key : zh, "draft"));
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
     * <p>⚠️ 切块标记要先去掉：超长页面会被切成多块，后续块标题是
     * 「水（Water）（续 2）」。不去掉的话上面那个"末尾括号"正则会把
     * <b>「续 2」当成英文名</b>，长出一批名为「水（Water）、续 2」的垃圾词条。
     */
    /** 分块标记：{@code （续 2）} —— 它不属于名字，见 {@code KbTextChunker} */
    private static final java.util.regex.Pattern CHUNK_MARK =
            java.util.regex.Pattern.compile("\\s*[（(]续\\s*\\d+[）)]$");

    public static String termName(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            return "";
        }
        t = CHUNK_MARK.matcher(t).replaceFirst("").trim();
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

    // ==================== 读 ====================

    /**
     * 全部词条（含 rejected —— 后台要展示它们）。
     *
     * <p>读也加锁这件事已经下沉到 {@link KbTermRepository}（共用连接是全局串行的），
     * 这里不再自己 synchronized。
     */
    /**
     * 语料变了就自己补一次（幂等；快路径只比一个 long）。
     *
     * <h2>为什么挂在读路径上（懒）</h2>
     * 写路径可能是 CLI 进程里**一次写上千块**：在那里补齐既慢又没人看；
     * 而「忘了补」的代价是**新页面没有中文名的位置** —— B 路关键词搜不到它，还不报错。
     * 让真正要读的人（B 路检索 / 管理端）顺手补上，两个问题一起没了：
     * 第一次读发现版本对不上 → 补一次；之后每次读只是比一个 long。
     *
     * <p>旧做法：让每个导入器写完之后显式调 {@link #reconcile()}（迁移前共 4 处）——
     * 和块索引的 {@code reload()} 是同一条教训：**新增一个写入口就漏一个**，
     * 漏了的症状是静默的。
     */
    private void ensureReconciled() {
        if (!available || corpus.version() == reconciledVersion) {
            return;
        }
        synchronized (this) {
            if (!available || corpus.version() == reconciledVersion) {
                return;
            }
            int added = reconcile();
            if (added > 0) {
                log.info("[KB-TERM] 语料变了，自动补齐 {} 条词条", added);
            }
        }
    }

    public List<Entry> list() {
        if (!available) {
            return List.of();
        }
        ensureReconciled();   // 语料变了就顺手补齐 —— 调用方不需要记得调 reconcile()
        try {
            List<Entry> out = new ArrayList<>();
            for (KbTermRepository.Row r : repo.all()) {
                out.add(new Entry(r.en(), r.zh(), normalizeStatus(r.status())));
            }
            return out;
        } catch (Exception e) {
            log.warn("[KB-TERM] 读取失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 按英文名索引 —— 列表组装用，避免每条都查一次库 */
    public Map<String, Entry> map() {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Entry e : list()) {
            out.put(e.en(), e);
        }
        return out;
    }

    public Entry get(String en) {
        if (!available || en == null || en.isBlank()) {
            return null;
        }
        try {
            KbTermRepository.Row r = repo.find(en);
            return r == null ? null : new Entry(r.en(), r.zh(), normalizeStatus(r.status()));
        } catch (Exception e) {
            log.warn("[KB-TERM] 查询失败：{}", e.getMessage());
            return null;
        }
    }

    public long count() {
        if (!available) {
            return 0;
        }
        try {
            return repo.count();
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 写 ====================

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
        try {
            repo.upsert(en.trim(), zh == null ? "" : zh.trim(), status, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[KB-TERM] 保存失败：{}", e.getMessage());
            return false;
        }
        version.incrementAndGet();
        return true;
    }

    /** 只改状态（核对时最常用：draft → verified）。不存在返回 false */
    public boolean setStatus(String en, String status) {
        if (!available || en == null) {
            return false;
        }
        boolean ok;
        try {
            ok = repo.updateStatus(en, status, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[KB-TERM] 改状态失败：{}", e.getMessage());
            return false;
        }
        if (ok) {
            version.incrementAndGet();
        }
        return ok;
    }

    /**
     * 批量改状态 —— 核对模式的快路径，**一个事务**。
     *
     * <p>旧实现是一条一个 {@code UPDATE} 加一次全表重写，勾 200 条就重写 200 遍文件。
     * 事务边界与「本来就对的不写」这两件事都在 {@link KbTermRepository#setStatusBatch}。
     *
     * <p>失败时返回全 0（并记 warn）：事务已整体回滚，报告部分统计只会误导 ——
     * 那正是旧实现干的事（它把回滚前攒到的 {@code missing} 报了出去）。
     */
    public BatchResult setStatusBatch(List<String> ens, String status) {
        if (!available || ens == null || ens.isEmpty()) {
            return new BatchResult(0, 0, List.of());
        }
        KbTermRepository.StatusOutcome out;
        try {
            out = repo.setStatusBatch(ens, status, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[KB-TERM] 批量改状态失败：{}", e.getMessage());
            return new BatchResult(0, 0, List.of());
        }
        if (out.updated() > 0) {
            version.incrementAndGet();
        }
        return new BatchResult(out.updated(), out.unchanged(), out.missing());
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
        boolean ok;
        try {
            ok = repo.clearName(en, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[KB-TERM] 清除失败：{}", e.getMessage());
            return false;
        }
        if (ok) {
            version.incrementAndGet();
        }
        return ok;
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
        long before = count();
        try {
            repo.deleteAll();
        } catch (Exception e) {
            log.warn("[KB-TERM] 清空失败：{}", e.getMessage());
            return 0;
        }
        version.incrementAndGet();
        log.warn("[KB-TERM] 已清空全部词条：{} 条（不可逆）", before);
        return (int) before;
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
     *
     * <p>「新增 / 更新」是按导入前的快照算的：同一份文件里同一个英文名出现两次会各记一次，
     * 因为最终只有一行 —— 这与旧实现一致，也是"这次导入动了多少"最有用的口径。
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
        List<KbTermRepository.Row> batch = new ArrayList<>();
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
            String status = cell(r, iStatus);
            batch.add(new KbTermRepository.Row(en,
                    mergeAliases(cell(r, iZh), cell(r, iAlias)),
                    status.isEmpty() ? "draft" : status));
            if (existing.containsKey(en)) {
                updated++;
            } else {
                created++;
            }
        }
        try {
            repo.upsertBatch(batch, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[KB-TERM] 导入失败：{}", e.getMessage());
            return new ImportResult(0, 0, 0);
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
        List<KbTermRepository.Row> batch = new ArrayList<>();
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
            batch.add(new KbTermRepository.Row(en, zh, status));
            if (existing.containsKey(en)) {
                updated++;
            } else {
                created++;
            }
        }
        try {
            repo.upsertBatch(batch, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[KB-TERM] 导入失败：{}", e.getMessage());
            return new ImportResult(0, 0, 0);
        }
        if (created + updated > 0) {
            version.incrementAndGet();
            log.info("[KB-TERM] 导入完成：新增 {}，更新 {}，跳过 {}", created, updated, skipped);
        }
        return new ImportResult(created, updated, skipped);
    }

    // ==================== 工具 ====================

    /** 空值或认不出来的值一律算 draft —— 定义在 {@link KbTermRepository}，见 {@link #STATUSES} */
    public static String normalizeStatus(String s) {
        return KbTermRepository.normalizeStatus(s);
    }
}
