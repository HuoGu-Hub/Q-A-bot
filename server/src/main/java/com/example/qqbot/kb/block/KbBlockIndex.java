package com.example.qqbot.kb.block;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 块索引的**内存视图** —— 检索时不查数据库。
 *
 * <p>为什么要有它：{@link KbBlockStore} 每次查询都是一次 SQLite 往返。
 * 检索一轮要遍历全部块算余弦，逐块查库显然不行。所以这里做一份常驻内存的快照，
 * 写入之后由调用方显式 {@link #reload()}（和旧的 {@code KbIndex} 一个思路）。
 *
 * <p>与旧设计的根本区别：**索引是"id → 块/向量"，不是"第 k 行 → 块/向量"**。
 * 所以增删改都不会让别的块错位，也不需要墓碑。
 */
@Component
public class KbBlockIndex implements com.example.qqbot.kb.KbCorpus {

    private static final Logger log = LoggerFactory.getLogger(KbBlockIndex.class);

    private final KbBlockStore store;

    private volatile boolean loaded;
    private volatile List<KbBlock> blocks = List.of();
    private volatile Map<String, float[]> vectors = Map.of();
    /** 标题向量 + 它的输入标题（缺这一路的块照常检索，只是不吃闸门） */
    private volatile Map<String, KbBlockStore.TitleVector> titleVectors = Map.of();
    private volatile Map<String, KbBlock> byId = Map.of();
    /** 检索用的条目快照（块 + 向量成对，已排除下架） */
    private volatile List<com.example.qqbot.kb.KbCorpus.Entry> entries = List.of();

    public KbBlockIndex(KbBlockStore store) {
        this.store = store;
    }

    /** 索引是否可用（没有任何块、或一块向量都没有，都算不可用，调用方据此降级） */
    public boolean isReady() {
        ensureLoaded();
        return !blocks.isEmpty() && !vectors.isEmpty();
    }

    public int size() {
        ensureLoaded();
        return blocks.size();
    }

    /** 向量维度；没有向量时返回 0 */
    public int dimensions() {
        ensureLoaded();
        for (float[] v : vectors.values()) {
            return v.length;
        }
        return 0;
    }

    /** 参与检索的块（不含已下架、且**必须有向量**） */
    public List<KbBlock> searchable() {
        ensureLoaded();
        return blocks.stream().filter(b -> vectors.containsKey(b.id())).toList();
    }

    /** {@link com.example.qqbot.kb.KbCorpus} 的实现 —— 检索器只认这个方法 */
    @Override
    public List<com.example.qqbot.kb.KbCorpus.Entry> entries() {
        ensureLoaded();
        return entries;
    }

    public KbBlock block(String id) {
        ensureLoaded();
        return byId.get(id);
    }

    public float[] vector(String id) {
        ensureLoaded();
        return vectors.get(id);
    }

    /** 标题向量；没有（还没补建 / 标题变了未重算）就返回 {@code null} */
    public float[] titleVector(String id) {
        ensureLoaded();
        KbBlockStore.TitleVector tv = titleVectors.get(id);
        return tv == null ? null : tv.vec();
    }

    /** 有标题向量的块数（管理端看"补到几成"） */
    public int titleVectorCount() {
        ensureLoaded();
        return titleVectors.size();
    }

    /** 全部块（含没有向量的），导出 / 管理端用 */
    public List<KbBlock> all() {
        ensureLoaded();
        return blocks;
    }

    /** 写入之后调一次 —— 不重启服务即可生效 */
    public boolean reload() {
        synchronized (this) {
            loaded = false;
            blocks = List.of();
            vectors = Map.of();
            titleVectors = Map.of();
            byId = Map.of();
            entries = List.of();
        }
        ensureLoaded();
        log.info("[KB-BLOCK-IDX] 索引已重载：{} 块，{} 条正文向量，{} 条标题向量",
                blocks.size(), vectors.size(), titleVectors.size());
        return isReady();
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            try {
                List<KbBlock> active = store.allActive();
                Map<String, float[]> vecs = store.allVectors();
                Map<String, KbBlockStore.TitleVector> tvecs = store.allTitleVectors();
                Map<String, KbBlock> map = new LinkedHashMap<>();
                for (KbBlock b : active) {
                    map.put(b.id(), b);
                }
                this.blocks = List.copyOf(active);
                this.vectors = Map.copyOf(vecs);
                this.titleVectors = Map.copyOf(tvecs);
                this.byId = Map.copyOf(map);

                // 检索条目：只保留"未下架 + 有向量"的块（这是"能不能被检索到"的定义）
                List<com.example.qqbot.kb.KbCorpus.Entry> es = new ArrayList<>();
                for (KbBlock b : active) {
                    float[] v = vecs.get(b.id());
                    if (v == null) {
                        continue;
                    }
                    KbBlockStore.TitleVector tv = tvecs.get(b.id());
                    es.add(new com.example.qqbot.kb.KbCorpus.Entry(
                            b.id(), b.docId(), b.title(), b.body(), b.url(), b.tags(),
                            KbBlock.SRC_MANUAL.equalsIgnoreCase(b.source() == null ? "" : b.source()),
                            b.updatedAt(), v,
                            tv == null ? null : tv.vec()));
                }
                this.entries = List.copyOf(es);
            } catch (Exception e) {
                log.warn("[KB-BLOCK-IDX] 加载失败，本次不带知识库回答：{}", e.getMessage());
                this.blocks = List.of();
                this.vectors = Map.of();
                this.titleVectors = Map.of();
                this.byId = Map.of();
                this.entries = List.of();
            } finally {
                loaded = true;
            }
        }
    }
}
