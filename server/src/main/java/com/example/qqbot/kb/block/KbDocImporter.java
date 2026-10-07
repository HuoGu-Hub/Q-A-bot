package com.example.qqbot.kb.block;

import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.doc.ChunkImportPlan;
import com.example.qqbot.kb.doc.ChunkMarkup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档导入：把一份**符合格式规范的文档**变成知识库里的块。
 *
 * <p>这是系统内唯一的入口 —— 抓取、清洗、翻译、生成文档都在系统外（用户 2026-09-28 定的边界）。
 *
 * <h2>导入语义（用户明确，无例外）</h2>
 * <ul>
 *   <li>文档里的 id 在库里已存在 → **覆盖**；不存在 → **新增**；</li>
 *   <li>文档里没出现的块 → **一个都不动**；</li>
 *   <li>不区分是否人工改过，一律以最新导入为准；</li>
 *   <li>幂等：同一份文档导两次，第二次什么都不改。</li>
 * </ul>
 *
 * <h2>两步走</h2>
 * <ol>
 *   <li>{@link #preview} —— **只算不改**，回答"这次会改动多少条"（导入前给人看）；</li>
 *   <li>{@link #importDocument} —— 真正写入，并**只为新增/修改的块**算向量。</li>
 * </ol>
 */
@Component
public class KbDocImporter {

    private static final Logger log = LoggerFactory.getLogger(KbDocImporter.class);

    private final KbBlockStore store;
    private final EmbeddingClient embedding;
    /** 导入一份文档 = 在词条表里登记这个页面（词条是"按页定位分块"的入口） */
    private final com.example.qqbot.kb.term.KbTermStore termStore;

    /** 同样不再持有块索引：写完索引自己失效（见 {@code KbBlockStore.version}） */
    public KbDocImporter(KbBlockStore store, EmbeddingClient embedding,
                         com.example.qqbot.kb.term.KbTermStore termStore) {
        this.store = store;
        this.embedding = embedding;
        this.termStore = termStore;
    }

    /** 一次导入的结果 */
    public record Result(int added, int updated, int unchanged, int untouched,
                      int terms, List<String> warnings) {
        public int changed() {
            return added + updated;
        }
    }

    /**
     * ⚠️ 只算不改：解析 + 校验 + 比对，**不写任何数据**。
     *
     * @throws IllegalArgumentException 文档有致命格式问题（消息里带逐条原因）
     */
    public ChunkImportPlan<ChunkMarkup.Block> preview(String documentText) {
        ChunkMarkup.Parsed parsed = parseOrThrow(documentText);
        return planOf(parsed, sourceOf(parsed));
    }

    /**
     * 真正导入。
     *
     * @param documentText 文档全文
     * @return 改动统计
     * @throws IllegalArgumentException 文档有致命格式问题
     */
    public Result importDocument(String documentText) {
        ChunkMarkup.Parsed parsed = parseOrThrow(documentText);
        String docId = docIdOf(parsed);
        String url = parsed.meta("url");
        List<String> tags = ChunkMarkup.splitTags(parsed.meta("tags"));
        String source = sourceOf(parsed);

        ChunkImportPlan plan = planOf(parsed, source);

        // ★ 只为"新增 + 覆盖"的块算向量；无变化的块一次 embedding 都不调
        List<ChunkMarkup.Block> todo = new ArrayList<>();
        todo.addAll(plan.added());
        todo.addAll(plan.updated());

        Map<String, float[]> fresh = new LinkedHashMap<>();
        for (ChunkMarkup.Block b : todo) {
            float[] v = embedding.embedOne(b.body());
            if (v == null || v.length == 0) {
                throw new IllegalStateException("向量模型没有返回结果，导入中止（块 " + b.key() + "）");
            }
            fresh.put(b.key(), v);
        }

        // ★ 标题向量（C 路）：只补本次"新增 + 覆盖"的块，走批量接口（标题很短，
        //   一批 10 条，两百块的文档也就二十次调用）。
        //
        //   和正文向量的**失败策略刻意不同**：正文向量算不出来这份文档就不该进库
        //   （检索会按旧正文匹配），而标题向量缺一条只是少一路召回 —— 大不了
        //   以后用「补标题向量」再跑一遍，绝不该因为它让整份文档导不进去。
        Map<String, float[]> freshTitle = new LinkedHashMap<>();
        if (!todo.isEmpty()) {
            List<String> titles = todo.stream().map(ChunkMarkup.Block::title).toList();
            List<float[]> vecs = safeEmbedTitles(titles);
            if (vecs.size() == titles.size()) {
                for (int i = 0; i < titles.size(); i++) {
                    freshTitle.put(todo.get(i).key(), vecs.get(i));
                }
            } else {
                log.warn("[KB-IMPORT] 标题向量条数不对（要 {} 条，拿到 {} 条），本次不补 —— "
                        + "稍后用「补标题向量」重跑即可", titles.size(), vecs.size());
            }
        }

        int added = 0;
        int updated = 0;
        String now = Instant.now().toString();
        for (ChunkMarkup.Block b : todo) {
            KbBlock block = new KbBlock(
                    b.key(),
                    docId,
                    b.title(),
                    b.body(),
                    url,
                    tags,
                    source,
                    false,
                    now);
            boolean isNew = store.upsert(block, fresh.get(b.key()));
            float[] tv = freshTitle.get(b.key());
            if (tv != null && tv.length > 0) {
                store.upsertTitleVector(block.id(), block.title(), tv);
            }
            if (isNew) {
                added++;
            } else {
                updated++;
            }
        }

        // ★ 登记/补齐词条：**一个块 = 一个词条**（块标题就是它的中文名）。
//   词条表因此就是"按块定位"的目录 —— 一份文档有多少块，就多出多少词条。
        //   词条是"按页定位分块"的入口（词条 → docId → 该文档的全部块），
        //   也是关键词路认识这个页面的唯一途径。
        int terms = registerTerms(parsed);
        log.info("[KB-IMPORT] 文档「{}」：新增 {}、覆盖 {}、无变化 {}、未触及 {}（{} 块）",
                docId, added, updated, plan.unchanged().size(), plan.untouchedExisting(), todo.size());

        return new Result(added, updated, plan.unchanged().size(), plan.untouchedExisting(),
                terms, plan.warnings());
    }

    /** 清空全部块（"首次导入中文语料前清空"用）—— 索引自己会失效，不用手工重载 */
    public void clearAll() {
        store.clearAll();
    }

    /**
     * 给这份文档登记一条词条（页面名）。
     *
     * <p>取名的顺序：文档头的 {@code title} → 文档头的 {@code name} → 第一个块的标题。
     * 他们的文档就是 {@code name} 里写中文标题，所以默认能拿到对的名字；
     * {@code name} 是英文 slug 的写法也支持。
     *
     * <p><b>已存在的词条不覆盖</b>（只在中文名为空时补齐）：名字是可能被人核对/改过的，
     * 重新导一次文档就把它冲掉，等于人工成果白做。要改名字请去「词条」面板。
     *
     * <p>别名（文档头 {@code aliases}，逗号或顿号分隔）会并进中文名 ——
     * 词表按 {@code 、} 拆别名，短别名才是关键词路真正能命中的东西。
     *
     * @return 1 = 新建或补齐了一条；0 = 已经有了，没动
     */
    private int registerTerms(ChunkMarkup.Parsed parsed) {
        if (!termStore.isAvailable()) {
            return 0;
        }
        int n = 0;
        for (ChunkMarkup.Block b : parsed.blocks()) {
            // 词条身份 = **块 id**（有 id 用 id，没有就用标题兜底）
            String key = b.key();
            String name = com.example.qqbot.kb.term.KbTermStore.termName(b.title());
            if (key == null || key.isBlank() || name.isEmpty()) {
                continue;
            }
            com.example.qqbot.kb.term.KbTermStore.Entry existing = termStore.get(key);
            // 已有名字的不覆盖 —— 名字可能被人核对/改过，重导一次就冲掉等于人工白做
            if (existing != null && existing.zh() != null && !existing.zh().isBlank()) {
                continue;
            }
            String status = existing == null || existing.status().isBlank() ? "draft" : existing.status();
            termStore.upsert(key, name, status);
            n++;
        }
        return n;
    }


    // ==================== 内部 ====================

    private ChunkMarkup.Parsed parseOrThrow(String documentText) {
        ChunkMarkup.Parsed parsed = ChunkMarkup.parse(documentText);
        if (!parsed.ok()) {
            throw new IllegalArgumentException("文档格式有问题，不能导入："
                    + String.join("；", parsed.errors()));
        }
        if (parsed.blocks().isEmpty()) {
            throw new IllegalArgumentException("文档里一个块都没有，不能导入");
        }
        return parsed;
    }

    /** 标题向量失败不影响导入 —— 缺了它只是少一路召回，补建入口随时能补 */
    private List<float[]> safeEmbedTitles(List<String> titles) {
        try {
            List<float[]> v = embedding.embedChunked(titles);
            return v == null ? List.of() : v;
        } catch (Exception e) {
            log.warn("[KB-IMPORT] 标题向量计算失败（本次不补，不影响导入）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 文档头里的 {@code source}；没写就用默认的 {@code doc} */
    private static String sourceOf(ChunkMarkup.Parsed parsed) {
        String s = parsed.meta("source");
        return s.isBlank() ? KbBlock.SRC_DOC : s;
    }

    /**
     * 导入计划 + **文档级字段的比对**。
     *
     * <h2>为什么不能直接用 {@code ChunkImportPlan.of(...)}</h2>
     * 它只比对**正文**（块级字段）。而 {@code source} 是**文档级**的，不参与块的身份 ——
     * 于是"只改了文档头的 {@code source}"会被一律判成**无变化**：导入是空转，
     * {@code source} 事实上**永远改不掉**（2026-10-04 实测踩到）。
     *
     * <p>修法：正文相同、但库里那一块的 {@code source} 与本次文档头不一致时，
     * 把它从「无变化」挪进「覆盖」。
     *
     * <p><b>preview 与真导入共用这一份计算</b> —— 所以不会出现
     * "预览说改动 0 条、实际却改了 N 条"这种自相矛盾。
     */
    private ChunkImportPlan<ChunkMarkup.Block> planOf(ChunkMarkup.Parsed parsed, String source) {
        ChunkImportPlan<ChunkMarkup.Block> raw = ChunkImportPlan.of(library(), parsed.blocks());
        if (raw.unchanged().isEmpty()) {
            return raw;
        }
        List<ChunkMarkup.Block> moved = new ArrayList<>();
        List<ChunkMarkup.Block> stay = new ArrayList<>();
        for (ChunkMarkup.Block b : raw.unchanged()) {
            KbBlock cur = store.get(b.key());
            if (cur != null && !source.equals(cur.source())) {
                moved.add(b);
            } else {
                stay.add(b);
            }
        }
        if (moved.isEmpty()) {
            return raw;
        }
        List<ChunkMarkup.Block> updated = new ArrayList<>(raw.updated());
        updated.addAll(moved);
        return new ChunkImportPlan<>(raw.added(), List.copyOf(updated), List.copyOf(stay),
                raw.untouchedExisting(), raw.warnings());
    }

    /** 库里现有的块，key → 块（导入计划的比对基线） */
    private Map<String, KbBlock> library() {
        Map<String, KbBlock> lib = new LinkedHashMap<>();
        for (KbBlock b : store.all()) {
            lib.put(b.id(), b);
        }
        return lib;
    }

    /** 文档身份：优先用文档头的 name，没有就用 id 前缀兜底 */
    private static String docIdOf(ChunkMarkup.Parsed parsed) {
        String name = parsed.meta("name");
        if (!name.isBlank()) {
            return name;
        }
        String first = parsed.blocks().get(0).key();
        int dash = first.lastIndexOf('-');
        return dash > 0 ? first.substring(0, dash) : first;
    }
}
