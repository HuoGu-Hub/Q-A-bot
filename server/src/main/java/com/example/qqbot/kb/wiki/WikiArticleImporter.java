package com.example.qqbot.kb.wiki;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.WikiImport;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.persistence.KbWikiPageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * wiki 文章导入 —— 把**任务**与**机制**补进语料。
 *
 * <h2>为什么这两类只能从文章来</h2>
 * 实测：地图的 `Quests` 分组**只有 1 个 marker**，而 `Category:Quests` 有 **149 篇**文章。
 * 任务的前置、目标、奖励、做法全在正文里；地图只有一个坐标点。
 * 机制（`Combat Mechanics` / `Crafting` / `Survival`…）同理，地图里根本没有。
 *
 * <h2>三个必须做的事</h2>
 * <ol>
 *   <li><b>清洗</b>：正文过 {@link WikitextCleaner}。不清洗等于把 `{{` / `[[` 当内容喂给向量模型。</li>
 *   <li><b>增量</b>：按 `revid` 比对（{@link KbWikiPageStore}），没变的页不重新清洗、**不重新向量化**
 *       —— embedding 是按量计费的。</li>
 *   <li><b>可回滚</b>：块全落在 `docId = <前缀>·<来源>` 下，{@link #purge()} 一条命令清干净。</li>
 * </ol>
 *
 * <h2>超长正文是**切块**，不是截断（2026-10-02 改）</h2>
 * 原先超长直接 {@code substring(0, maxBodyChars)} —— 而机制页实测约 11KB、
 * 上限 1.5KB，<b>86% 的正文被丢掉</b>，关键信息很可能正好在被截掉的部分
 * （这与实测缺口「任务/机制的中文检索只有 2/4」吻合）。
 *
 * <p>现在交给 {@link KbTextChunker} 按段落/句子边界切：一页对应多块，
 * 块 id 是 {@code <基础id>}、{@code <基础id>-2}、{@code <基础id>-3}…
 * `kb_wiki_page.block_count` 记住切了几块，页面**改短**时据此清掉多出来的
 * —— 不清就是永久孤儿块。
 *
 * <p>失败**按页隔离**：一篇坏了只记 error，其余照常导入；且**不推进它的 revid**，
 * 下次会重试（和地图同步同一条教训）。
 */
@Service
public class WikiArticleImporter {

    private static final Logger log = LoggerFactory.getLogger(WikiArticleImporter.class);

    /** 块来源标记：来自 wiki 文章（与 doc / manual / map 并列） */
    public static final String SRC_WIKI = "wiki";

    private final KbPolicy props;
    private final WikiApiClient client;
    private final KbWikiPageStore store;
    private final KbBlockStore blockStore;
    private final EmbeddingClient embedding;

    /* 既不持有块索引、也不持有词条表：写完索引自己失效、词条表自己补齐 */
    public WikiArticleImporter(KbPolicy props, WikiApiClient client, KbWikiPageStore store,
                               KbBlockStore blockStore, EmbeddingClient embedding) {
        this.props = props;
        this.client = client;
        this.store = store;
        this.blockStore = blockStore;
        this.embedding = embedding;
    }

    /** 一个导入来源：前缀（如 `Quests/`）或分类（如 `Gameplay`） */
    public record Source(String kind, String name) {
        public String label() {
            return kind.equals("prefix") ? name : name;
        }
    }

    public record ImportReport(int sources, int pages, int changed, int imported, int failed, int chars,
                               List<String> errors, boolean dryRun, Map<String, Integer> bySource) {
    }

    /** 配置里声明的来源列表 */
    public List<Source> sources() {
        List<Source> out = new ArrayList<>();
        for (String p : props.getWikiImport().getPrefixes()) {
            if (p != null && !p.isBlank()) {
                out.add(new Source("prefix", p.trim()));
            }
        }
        for (String c : props.getWikiImport().getCategories()) {
            if (c != null && !c.isBlank()) {
                out.add(new Source("category", c.trim()));
            }
        }
        return out;
    }

    public ImportReport importAll(boolean dryRun) {
        WikiImport cfg = props.getWikiImport();
        if (!cfg.isEnabled()) {
            return new ImportReport(0, 0, 0, 0, 0, 0, List.of("wiki 文章导入未启用"), dryRun, Map.of());
        }
        if (!store.isAvailable()) {
            return new ImportReport(0, 0, 0, 0, 0, 0, List.of("文章状态存储不可用"), dryRun, Map.of());
        }
        List<Source> sources = sources();
        if (sources.isEmpty()) {
            return new ImportReport(0, 0, 0, 0, 0, 0, List.of("没有配置任何来源（categories / prefixes）"), dryRun, Map.of());
        }

        // 建中英对照索引：让英文文章块能被中文提问检索到（见 NameZhIndex 类注释里的实测缺口）
        NameZhIndex nameIndex = NameZhIndex.fromBlocks(blockStore.allActive());
        log.info("[KB-WIKI] 中英对照索引：{} 条", nameIndex.size());

        int totalPages = 0;
        int totalChanged = 0;
        int imported = 0;
        int failed = 0;
        int chars = 0;
        List<String> errors = new ArrayList<>();
        Map<String, Integer> bySource = new LinkedHashMap<>();
        // 块 id 占用登记：撞车时必须吵（否则一页的内容会静默覆盖另一页）
        java.util.Map<String, String> blockIdOwner = new java.util.HashMap<>();

        for (Source src : sources) {
            WikiApiClient.Collected listed;
            List<String> titles;
            try {
                listed = list(src, cfg.getMaxPagesPerSource());
                titles = listed.titles();
            } catch (Exception e) {
                errors.add(src.label() + "：列页失败 " + e.getMessage());
                failed++;
                continue;
            }
            // ⚠️ 顶到上限时**必须吵**：多出来的页既不在本次导入里、也不会进状态表 ——
            //    静默漏页是最难查的一类问题（见 WikiApiClient.collect）。
            if (listed.truncated()) {
                String warn = src.label() + "：列到 " + titles.size() + " 页就顶到上限"
                        + "（wiki-import.max-pages-per-source=" + cfg.getMaxPagesPerSource()
                        + "），**后面还有页没列** —— 调大上限，或把它拆成多个来源";
                log.warn("[KB-WIKI] {}", warn);
                errors.add(warn);
            }
            totalPages += titles.size();
            bySource.put(src.label(), titles.size());
            if (titles.isEmpty()) {
                continue;
            }

            Map<String, WikiApiClient.Meta> meta;
            Map<String, WikiApiClient.Revision> content = Map.of();
            try {
                meta = client.fetchMeta(titles);
            } catch (Exception e) {
                errors.add(src.label() + "：取版本号失败 " + e.getMessage());
                failed++;
                continue;
            }
            Map<String, Long> known = store.knownRevisions();
            // ⚠️ 上次**失败**的页必须重试 —— 别看 revid。失败行里的 revid 不可信
            //    （见 KbWikiPageStore.markError 的注释），只信"这页有没有 error"。
            java.util.Set<String> pending = store.pendingRetries();
            if (!pending.isEmpty()) {
                log.info("[KB-WIKI] 上次失败待重试：{} 页", pending.size());
            }
            // ⚠️ 块 id 撞车检查放在**列页之后、增量判断之前**：
            //    这样不管这次有没有页要导（正常增量跑就是 0 页），它都会出现在报告里。
            //    已知并接受（方案 C，2026-10-08 决定）：见缺陷清单 §10.2 ——
            //    撞车的两组都是近似重复页，暂不改 id 约定，但**不许再静默**。
            for (String title : titles) {
                String collideWith = registerBlockId(blockIdOwner, blockIdOf(title), title);
                if (collideWith != null) {
                    errors.add(title + "：块 id 与「" + collideWith + "」撞车（" + blockIdOf(title)
                            + "）—— 这一页的正文进不了语料（已知问题，见缺陷清单 §10.2）");
                }
            }

            List<String> changed = new ArrayList<>();
            for (String title : titles) {
                WikiApiClient.Meta m = meta.get(title);
                if (m == null) {
                    continue;
                }
                Long local = known.get(title);
                if (local == null || local != m.revid() || pending.contains(title)) {
                    changed.add(title);
                }
            }
            totalChanged += changed.size();
            if (dryRun || changed.isEmpty()) {
                continue;
            }

            try {
                content = client.fetchContent(changed);
            } catch (Exception e) {
                errors.add(src.label() + "：取正文失败 " + e.getMessage());
                failed += changed.size();
                continue;
            }

            for (String title : changed) {
                WikiApiClient.Revision rev = content.get(title);
                if (rev == null) {
                    failed++;
                    errors.add(title + "：API 没返回内容");
                    store.markError(title, src.label(), "API 没返回内容");
                    continue;
                }
                try {
                    String body = WikitextCleaner.clean(rev.content());
                    if (body.isBlank()) {
                        // 清洗后为空说明这页没有可读正文（纯模板页）——记状态但不建块
                        store.upsert(title, src.label(), rev.revid(), rev.timestamp(), 0, "", 0);
                        continue;
                    }
                    String name = titleOf(title);
                    // ★ 有核对来的中文名就写成「中文（English）」——中英都能命中。
                    //   查不到就保持英文，**不编**（见 NameZhIndex 类注释）。
                    String zh = nameIndex.resolve(name);
                    String display = zh != null ? zh + "（" + name + "）" : name;
                    // ★ 切块（不是截断）——见类注释与 KbTextChunker
                    List<String> chunks = KbTextChunker.split(body, cfg.getMaxBodyChars());
                    String baseId = blockIdOf(title);
                    String docId = cfg.getDocIdPrefix() + "·" + src.label();
                    String url = "https://enshrouded.wiki.gg/wiki/" + title.replace(' ', '_');
                    String now = Instant.now().toString();
                    // 先清掉上一次多切出来的块：页面在 wiki 上被**改短**时不清就是永久孤儿块
                    for (int i = store.blockCount(title); i > chunks.size(); i--) {
                        String stale = chunkId(baseId, i);
                        blockStore.deleteTitleVector(stale);
                        blockStore.delete(stale);
                    }
                    for (int i = 0; i < chunks.size(); i++) {
                        String id = chunkId(baseId, i + 1);
                        // 第 1 块用干净标题；后续块标「（续 N）」——模型据此知道这是同一页的节选，
                        // 也免得 prompt 里出现几个看起来重复的标题
                        String chunkTitle = i == 0 ? display : display + "（续 " + (i + 1) + "）";
                        // 中文名**每块都带**：块是独立检索单位，缺了它中文提问够不到后面的块
                        String text = zh != null ? zh + "。" + chunks.get(i) : chunks.get(i);
                        KbBlock block = new KbBlock(id, docId, chunkTitle, text, url,
                                List.of("wiki", src.kind(), src.label()), SRC_WIKI, false, now);
                        if (blockStore.upsert(block, embedding.embedOne(name + "\n" + text))) {
                            // 标题向量一律用**干净的页面名**：C 路闸门问的是"这是哪一页"，
                            // 同一页的每一块都该同样命中；并列时按 id 顺序取到第 1 块（导语段）
                            blockStore.upsertTitleVector(id, chunkTitle, embedding.embedOne(name));
                            imported++;
                        }
                    }
                    chars += body.length();
                    store.upsert(title, src.label(), rev.revid(), rev.timestamp(), body.length(),
                            baseId, chunks.size());
                } catch (Exception e) {
                    failed++;
                    errors.add(title + "：" + e.getMessage());
                    // ⚠️ 记失败**绝不能带上新的 revid** —— 那等于宣称"这页已经导完"，
                    //    而增量判据是 revid 相等就跳过 → 这页会被永久跳过、静默留白。
                    //    签名已经从参数上堵死了（见 KbWikiPageStore.markError）。
                    store.markError(title, src.label(), e.getMessage());
                }
            }
        }

        // 词条表**不用管**：下次有人读词条（B 路检索 / 管理端）时，KbTermStore 会自己发现
        // "语料版本变了"并补齐 —— 见 KbTermStore.ensureReconciled()。这里以前显式调 reconcile()。
        log.info("[KB-WIKI] 导入完成：来源 {} 个 / 页面 {} 篇 / 变过 {} 篇 / 写入 {} 块 / 失败 {} / {} 字",
                sources.size(), totalPages, totalChanged, imported, failed, chars);
        return new ImportReport(sources.size(), totalPages, totalChanged, imported, failed, chars,
                errors, dryRun, bySource);
    }

    /**
     * 清掉全部 wiki 文章块 —— 回滚用。
     *
     * <h2>为什么必须连 {@code kb_wiki_page} 的状态行一起清</h2>
     * 增量判据是"这页的 {@code revid} 变过吗"。状态行留着的话，purge 之后再跑导入
     * <b>会一篇都不处理</b> —— 于是 purge 变成一扇**单向门**：撤掉之后再也导不回来。
     * 2026-10-02 修：purge = 完全回到"从没导入过"的状态。
     */
    public int purge() {
        WikiImport cfg = props.getWikiImport();
        List<KbWikiPageRepository.Page> pages = store.pages();
        int n = 0;
        for (KbWikiPageRepository.Page p : pages) {
            String id = p.blockId();
            if (id == null || id.isBlank()) {
                continue;
            }
            // 一页可能多块（切块）——blockCount 为 0 的老行只删基础 id
            for (int i = Math.max(1, p.blockCount()); i >= 1; i--) {
                String bid = chunkId(id, i);
                blockStore.deleteTitleVector(bid);
                if (blockStore.delete(bid)) {
                    n++;
                }
            }
        }
        // ★ 连状态一起清 —— 否则下一次导入会空转（见方法注释）
        int cleared = store.clear();
        // 词条表不用管：删块同样让语料版本前进，下次读取时自动补齐。
        // ⚠️ 但**孤儿词条不会自动清** —— 那是管理端的手动动作（deleteOrphans）：
        //    "先起中文名、正文以后再导"的人工行也长得像孤儿，自动清会误伤。
        log.info("[KB-WIKI] 已清除 wiki 文章块 {} 个、状态行 {} 条（前缀 {}）", n, cleared, cfg.getDocIdPrefix());
        return n;
    }

    /**
     * 登记块 id 的占用，返回**之前占用它的那个页面**（null = 第一次）。
     *
     * <p>为什么需要：{@link #blockIdOf} 是有损的（标点→短横、大小写归一），
     * 于是**两个不同的上游页可能映射到同一个块 id**。实测 2026-10-07：
     * {@code Quests/Farmer's Advice} 与 {@code Quests/Farmer’s Advice}（直引号 vs 弯引号）、
     * {@code Crash Down Force} 与 {@code Crash Down: Force} —— 两组各抢一个 id，
     * 后写的把先写的**整块内容覆盖掉**，而两页的状态行都报成功（账上 792 块 / 实际 790 块）。
     *
     * <p>这里只负责**认出来**：真正的修法要动 id 约定。**2026-10-08 决定按方案 C**：
     * 不改 id 约定（撞车的两组都是近似重复页），但每次导入都必须把撞车报出来。
     * 所以调用点在"列完页之后"（`importAll` 里），而不是"真要写块"的时候 ——
     * 后者在正常增量跑（0 页要导）里根本不会执行，等于没有告警。
     */
    static String registerBlockId(java.util.Map<String, String> seen, String id, String title) {
        String prev = seen.putIfAbsent(id, title);
        return prev == null || prev.equals(title) ? null : prev;
    }

    /** 列来源下的页名 —— 返回的是带"是否被上限截断"的结果，调用方必须把截断报出来 */
    private WikiApiClient.Collected list(Source src, int limit) {
        return src.kind().equals("prefix")
                ? client.listByPrefix(src.name(), limit)
                : client.listCategoryMembers(src.name(), limit);
    }

    /**
     * 块 id：第 1 块用基础 id，之后是 {@code <基础id>-2}、{@code <基础id>-3}…
     *
     * <p>确定性 + 可推导 —— 所以 {@code purge()} 与"页面改短时清孤儿块"都不需要额外记账，
     * 只靠 {@code block_count} 就能推出来。
     */
    public static String chunkId(String baseId, int part) {
        return part <= 1 ? baseId : baseId + "-" + part;
    }

    /** 页面名 → 块 id（确定性，重跑幂等） */
    public static String blockIdOf(String page) {
        return "wiki-" + page.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    /** 页面名 → 可读标题（去掉 `Quests/` 这类命名空间前缀） */
    public static String titleOf(String page) {
        String p = page == null ? "" : page.trim();
        int slash = p.indexOf('/');
        return slash > 0 && slash < p.length() - 1 ? p.substring(slash + 1) : p;
    }
}
