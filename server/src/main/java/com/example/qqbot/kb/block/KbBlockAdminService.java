package com.example.qqbot.kb.block;

import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.doc.ChunkImportPlan;
import com.example.qqbot.kb.doc.ChunkMarkup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 管理端的**块操作** —— 一切按 id，不再有"行号"。
 *
 * <h2>和旧路径（{@code KbTermService}）的关系</h2>
 * 旧的块操作是按 {@code chunks.jsonl} 的**行号**写的，而且要在 {@code index.bin} 里
 * 按字节偏移改向量（因为身份是行号）。这里全部换成 **id + 数据库**：
 * 改一块就是改两行，删一块就是删两行，不需要墓碑、不需要原子替换整个文件。
 *
 * <p>两条路并存到切换为止；切过去之后 {@code KbTermService} 里那几段块操作就可以删掉。
 *
 * <h2>一条硬规矩：改正文必须重算向量</h2>
 * 向量是按正文算的。只改正文不重算，检索会**按旧正文匹配** —— 表面上"保存成功"，
 * 实际上改了等于没改。所以本类里所有改正文的入口都强制走 embedding。
 */
@Service
public class KbBlockAdminService {

    private static final Logger log = LoggerFactory.getLogger(KbBlockAdminService.class);

    /** 自动生成 id 时用的前缀白名单：只有它才允许拼进 id */
    private static final Pattern SAFE_SLUG = Pattern.compile("[A-Za-z0-9-]+");

    /**
     * 补标题向量时的进度粒度。
     *
     * <p>不是 API 的批量大小（那个在 {@code app.kb.embedding.batch-size}）——
     * 这里只管"每补多少条打一行进度"。补建要跑几分钟，没进度会让人以为卡死了。
     */
    private static final int BACKFILL_PAGE = 50;

    private final KbBlockStore store;
    private final KbBlockIndex index;
    private final EmbeddingClient embedding;
    private final KbDocImporter importer;

    public KbBlockAdminService(KbBlockStore store, KbBlockIndex index,
                               EmbeddingClient embedding, KbDocImporter importer) {
        this.store = store;
        this.index = index;
        this.embedding = embedding;
        this.importer = importer;
    }

    /** 一份文档的块数概览 */
    public record DocSummary(String docId, int blocks, int retired) {
    }

    /** 给管理端看的块视图 */
    public record BlockView(String id, String docId, String title, String text, String url,
                            List<String> tags, String source, boolean retired, String updatedAt) {

        static BlockView of(KbBlock b) {
            return new BlockView(b.id(), b.docId(), b.title(), b.body(), b.url(),
                    b.tags(), b.source(), b.retired(), b.updatedAt());
        }
    }

    public boolean available() {
        return store.isAvailable();
    }

    // ==================== 读 ====================

    /** 有多少块 / 多少份文档（管理端首页要看） */
    public Map<String, Integer> stats() {
        return Map.of(
                "blocks", store.count(),
                "retired", store.countRetired(),
                "documents", docs().size(),
                "vectors", store.countVectors(),
                // C 路的覆盖度：比 blocks 少就是"还有块没补标题向量"（用下面的补建入口补）
                "titleVectors", store.countTitleVectors());
    }

    /** 全部文档及其块数，按文档名排序 */
    public List<DocSummary> docs() {
        Map<String, int[]> agg = new LinkedHashMap<>();
        for (KbBlock b : store.all()) {
            String doc = b.docId() == null || b.docId().isBlank() ? "（无文档）" : b.docId();
            int[] a = agg.computeIfAbsent(doc, k -> new int[2]);
            a[0]++;
            if (b.retired()) {
                a[1]++;
            }
        }
        List<DocSummary> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : agg.entrySet()) {
            out.add(new DocSummary(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        out.sort((x, y) -> x.docId().compareTo(y.docId()));
        return out;
    }

    /** 某份文档下的全部块（含已下架，管理端要看得到） */
    public List<BlockView> blocksOfDoc(String docId) {
        return store.byDoc(docId).stream().map(BlockView::of).toList();
    }

    public BlockView get(String id) {
        KbBlock b = store.get(id);
        return b == null ? null : BlockView.of(b);
    }

    // ==================== 写 ====================

    /**
     * 改一块的正文 —— **会重算向量**。
     *
     * @return 改完的块；id 不存在返回 {@code null}
     * @throws IllegalArgumentException 正文为空（要下架请用 {@link #setRetired}）
     */
    public BlockView updateText(String id, String newText) {
        KbBlock old = store.get(id);
        if (old == null) {
            return null;
        }
        String text = newText == null ? "" : newText.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("正文不能为空；要下架这一块请用「下架」");
        }
        KbBlock updated = new KbBlock(old.id(), old.docId(), old.title(), text, old.url(),
                old.tags(), old.source(), old.retired(), Instant.now().toString());
        store.upsert(updated, embed(text, id));
        index.reload();
        log.info("[KB-BLOCK] 块 {} 正文已更新（{} 字，向量已重算）", id, text.length());
        return BlockView.of(updated);
    }

    /**
     * 新增一块（管理端手写）。
     *
     * <p><b>为什么 id 建议显式给</b>：id 是块的身份，而中文标题没法可靠地转成英文 id。
     * 所以只有在 {@code docId} 本身是英文/数字/短横线时才会自动补 {@code docId-N}；
     * 否则要求显式提供 —— 与其生成一个中文 id（非法）或一个无意义的 {@code block-0}，
     * 不如让人明确写一个。
     */
    public BlockView add(String docId, String id, String title, String text,
                         String url, List<String> tags) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("正文不能为空");
        }
        String doc = docId == null ? "" : docId.trim();
        String bid = id == null || id.isBlank() ? autoId(doc) : id.trim();
        if (!bid.matches("[" + "A-Za-z0-9-" + "]+")) {
            throw new IllegalArgumentException("id 只能由英文字母、数字和 - 组成：" + bid);
        }
        if (store.get(bid) != null) {
            throw new IllegalArgumentException("id 已存在：" + bid + "（想改内容请用「保存这一块」）");
        }
        KbBlock b = new KbBlock(bid, doc, title == null ? "" : title.trim(), body,
                url == null ? "" : url, tags == null ? List.of() : List.copyOf(tags),
                KbBlock.SRC_MANUAL, false, Instant.now().toString());
        store.upsert(b, embed(body, bid));
        // 标题向量一起补上（C 路要它才能"名字直击"）；没标题的块就没有这一路
        if (!b.title().isBlank()) {
            store.upsertTitleVector(bid, b.title(), embed(b.title(), bid));
        }
        index.reload();
        log.info("[KB-BLOCK] 新增块 {}（文档 {}，{} 字）", bid, doc, body.length());
        return BlockView.of(b);
    }

    /** 下架 / 恢复一块（保留数据，随时能回来） */
    public boolean setRetired(String id, boolean retired) {
        boolean ok = store.setRetired(id, retired);
        if (ok) {
            index.reload();
        }
        return ok;
    }

    /** **真删除**一块 —— 旧设计做不到（只能打墓碑），id 即身份之后才敢这么干 */
    public boolean delete(String id) {
        boolean ok = store.delete(id);
        if (ok) {
            index.reload();
            log.warn("[KB-BLOCK] 块 {} 已真删除", id);
        }
        return ok;
    }

    /** 整份文档下架 / 恢复 */
    public int setDocRetired(String docId, boolean retired) {
        int n = 0;
        for (KbBlock b : store.byDoc(docId)) {
            if (b.retired() != retired && store.setRetired(b.id(), retired)) {
                n++;
            }
        }
        if (n > 0) {
            index.reload();
            log.info("[KB-BLOCK] 文档「{}」{}了 {} 块", docId, retired ? "下架" : "恢复", n);
        }
        return n;
    }

    // ==================== 导入 ====================

    /** 只算不改：告诉管理员"这次会改动多少条" */
    public ChunkImportPlan<ChunkMarkup.Block> preview(String documentText) {
        return importer.preview(documentText);
    }

    /** 真正导入（新增 / 覆盖，文件里没出现的块一个都不动） */
    public KbDocImporter.Result importDoc(String documentText) {
        return importer.importDocument(documentText);
    }

    // ==================== 标题向量补建（C 路） ====================

    /**
     * 补建结果。
     *
     * @param blocks   扫了多少块
     * @param missing  其中"从来没有标题向量"的块数
     * @param stale    其中"有，但标题已经变了"的块数（过期的）
     * @param embedded 这次真正算了几条（dryRun 时为 0）
     * @param dryRun   只统计不写库
     */
    public record TitleBackfill(int blocks, int missing, int stale, int embedded, boolean dryRun) {
        public int pending() {
            return missing + stale;
        }
    }

    /**
     * 补建 / 刷新标题向量（C 路）—— **幂等**，只处理"缺失"和"过期"的块。
     *
     * <p>过期判定就是比对 {@code kb_block.title} 和标题向量里存的那一份
     * （见 {@link KbBlockStore.TitleVector}）—— 标题改了没重算，会被这里发现。
     *
     * <p>跑完会 reload 索引，所以**不用重启服务**就生效。
     *
     * @param dryRun true = 只统计要补多少条，一条都不调模型（先在界面上问一句"要不要花这笔钱"）
     */
    public TitleBackfill backfillTitleVectors(boolean dryRun) {
        if (!store.isAvailable()) {
            throw new IllegalStateException("块存储不可用");
        }
        if (!dryRun && !embedding.isAvailable()) {
            throw new IllegalStateException("向量模型未配置，无法补标题向量");
        }
        List<KbBlock> todo = new ArrayList<>();
        int missing = 0;
        int stale = 0;
        Map<String, KbBlockStore.TitleVector> have = store.allTitleVectors();
        for (KbBlock b : store.all()) {
            if (b.title() == null || b.title().isBlank()) {
                continue;   // 没标题的块没有"标题向量"可言
            }
            KbBlockStore.TitleVector tv = have.get(b.id());
            if (tv == null) {
                missing++;
                todo.add(b);
            } else if (!b.title().equals(tv.title())) {
                stale++;
                todo.add(b);
            }
        }
        int blocks = store.count();
        if (dryRun || todo.isEmpty()) {
            log.info("[KB-TITLE] 补建预览：{} 块里 {} 块要补（缺 {}、过期 {}）{}",
                    blocks, todo.size(), missing, stale, dryRun ? "（dryRun，未写库）" : "");
            return new TitleBackfill(blocks, missing, stale, 0, dryRun);
        }
        log.info("[KB-TITLE] 开始补建标题向量：{} 块要补（缺 {}、过期 {}）", todo.size(), missing, stale);
        int done = 0;
        for (int i = 0; i < todo.size(); i += BACKFILL_PAGE) {
            List<KbBlock> slice = todo.subList(i, Math.min(i + BACKFILL_PAGE, todo.size()));
            List<String> titles = slice.stream().map(KbBlock::title).toList();
            List<float[]> vecs = embedding.embedChunked(titles);
            if (vecs == null || vecs.size() != slice.size()) {
                // 条数对不上就宁可整批不写：写错位置的向量比没有向量更糟（会把别的块顶上来）
                throw new IllegalStateException("标题向量返回条数不对（要 " + slice.size()
                        + " 条，拿到 " + (vecs == null ? "null" : vecs.size()) + " 条），已补 "
                        + done + " 条；再跑一次会从没补上的继续");
            }
            for (int j = 0; j < slice.size(); j++) {
                KbBlock b = slice.get(j);
                store.upsertTitleVector(b.id(), b.title(), vecs.get(j));
            }
            done += slice.size();
            log.info("[KB-TITLE] 补建进度 {}/{}", done, todo.size());
        }
        index.reload();
        log.info("[KB-TITLE] 标题向量补建完成：{} 条（索引已重载，无需重启）", done);
        return new TitleBackfill(blocks, missing, stale, done, false);
    }

    /** 清空全部块（**首次导入前**用；不可逆，界面上要二次确认） */
    public void clearAll() {
        importer.clearAll();
        log.warn("[KB-BLOCK] 管理端清空了全部块与向量");
    }

    // ==================== 内部 ====================

    private float[] embed(String text, String id) {
        if (!embedding.isAvailable()) {
            throw new IllegalStateException("向量模型未配置，无法写入（块 " + id + "）");
        }
        float[] v = embedding.embedOne(text);
        if (v == null || v.length == 0) {
            throw new IllegalStateException("向量模型没有返回结果（块 " + id + "）");
        }
        return v;
    }

    /** 只有 docId 是英文/数字/短横线时才敢自动补 id —— 见 {@link #add} 的说明 */
    private String autoId(String docId) {
        String base = docId == null ? "" : docId.trim();
        if (base.isEmpty() || !SAFE_SLUG.matcher(base).matches()) {
            throw new IllegalArgumentException(
                    "文档名「" + base + "」不是英文，无法自动生成 id —— 请显式指定 id（只能用英文字母、数字和 -）");
        }
        String prefix = base + "-";
        int max = -1;
        for (KbBlock b : store.all()) {
            if (b.id().startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(b.id().substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 不是 "前缀+数字" 的块不参与编号
                }
            }
        }
        return prefix + (max + 1);
    }
}
