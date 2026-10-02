package com.example.qqbot.kb.wiki;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.term.KbTermStore;
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
 * <p>失败**按页隔离**：一篇坏了只记 error，其余照常导入；且**不推进它的 revid**，
 * 下次会重试（和地图同步同一条教训）。
 */
@Service
public class WikiArticleImporter {

    private static final Logger log = LoggerFactory.getLogger(WikiArticleImporter.class);

    /** 块来源标记：来自 wiki 文章（与 doc / manual / map 并列） */
    public static final String SRC_WIKI = "wiki";

    private final KbProperties props;
    private final WikiApiClient client;
    private final KbWikiPageStore store;
    private final KbBlockStore blockStore;
    private final KbBlockIndex index;
    private final EmbeddingClient embedding;
    private final KbTermStore termStore;

    public WikiArticleImporter(KbProperties props, WikiApiClient client, KbWikiPageStore store,
                               KbBlockStore blockStore, KbBlockIndex index, EmbeddingClient embedding,
                               KbTermStore termStore) {
        this.props = props;
        this.client = client;
        this.store = store;
        this.blockStore = blockStore;
        this.index = index;
        this.embedding = embedding;
        this.termStore = termStore;
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
        KbProperties.WikiImport cfg = props.getWikiImport();
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

        for (Source src : sources) {
            List<String> titles;
            try {
                titles = list(src, cfg.getMaxPagesPerSource());
            } catch (Exception e) {
                errors.add(src.label() + "：列页失败 " + e.getMessage());
                failed++;
                continue;
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
            List<String> changed = new ArrayList<>();
            for (String title : titles) {
                WikiApiClient.Meta m = meta.get(title);
                if (m == null) {
                    continue;
                }
                Long local = known.get(title);
                if (local == null || local != m.revid()) {
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
                    store.markError(title, src.label(), 0, "API 没返回内容");
                    continue;
                }
                try {
                    String body = WikitextCleaner.clean(rev.content());
                    if (body.isBlank()) {
                        // 清洗后为空说明这页没有可读正文（纯模板页）——记状态但不建块
                        store.upsert(title, src.label(), rev.revid(), rev.timestamp(), 0, "");
                        continue;
                    }
                    if (body.length() > cfg.getMaxBodyChars()) {
                        body = body.substring(0, cfg.getMaxBodyChars());
                    }
                    String id = blockIdOf(title);
                    String name = titleOf(title);
                    // ★ 有核对来的中文名就写成「中文（English）」——中英都能命中。
                    //   查不到就保持英文，**不编**（见 NameZhIndex 类注释）。
                    String zh = nameIndex.resolve(name);
                    String display = zh != null ? zh + "（" + name + "）" : name;
                    // 中文名也塞进正文开头：多一处中文 token，向量与字面两条路都受益
                    if (zh != null) {
                        body = zh + "。" + body;
                    }
                    String docId = cfg.getDocIdPrefix() + "·" + src.label();
                    String url = "https://enshrouded.wiki.gg/wiki/" + title.replace(' ', '_');
                    KbBlock block = new KbBlock(id, docId, display, body, url,
                            List.of("wiki", src.kind(), src.label()), SRC_WIKI, false, Instant.now().toString());
                    if (blockStore.upsert(block, embedding.embedOne(name + "\n" + body))) {
                        blockStore.upsertTitleVector(id, name, embedding.embedOne(name));
                        imported++;
                        chars += body.length();
                    }
                    store.upsert(title, src.label(), rev.revid(), rev.timestamp(), body.length(), id);
                } catch (Exception e) {
                    failed++;
                    errors.add(title + "：" + e.getMessage());
                    store.markError(title, src.label(), rev.revid(), e.getMessage());
                }
            }
        }

        if (!dryRun && imported > 0) {
            index.reload();
        // 新块要**立刻**能被 B 路（关键词）看到：词条表只在启动时 reconcile 一次，
        // 不补这一下，运行期导入的块要等下次重启才进词表。
        termStore.reconcile();
        }
        log.info("[KB-WIKI] 导入完成：来源 {} 个 / 页面 {} 篇 / 变过 {} 篇 / 写入 {} 块 / 失败 {} / {} 字",
                sources.size(), totalPages, totalChanged, imported, failed, chars);
        return new ImportReport(sources.size(), totalPages, totalChanged, imported, failed, chars,
                errors, dryRun, bySource);
    }

    /** 清掉全部 wiki 文章块 —— 回滚用 */
    public int purge() {
        KbProperties.WikiImport cfg = props.getWikiImport();
        int n = 0;
        for (KbWikiPageStore.PageState p : store.pages()) {
            String id = p.blockId();
            if (id == null || id.isBlank()) {
                continue;
            }
            blockStore.deleteTitleVector(id);
            if (blockStore.delete(id)) {
                n++;
            }
        }
        if (n > 0) {
            index.reload();
        // 新块要**立刻**能被 B 路（关键词）看到：词条表只在启动时 reconcile 一次，
        // 不补这一下，运行期导入的块要等下次重启才进词表。
        termStore.reconcile();
        }
        log.info("[KB-WIKI] 已清除 wiki 文章块 {} 个（前缀 {}）", n, cfg.getDocIdPrefix());
        return n;
    }

    private List<String> list(Source src, int limit) {
        return src.kind().equals("prefix")
                ? client.listByPrefix(src.name(), limit)
                : client.listCategoryMembers(src.name(), limit);
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
