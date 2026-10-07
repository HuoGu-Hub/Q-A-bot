package com.example.qqbot.kb.block;

import com.example.qqbot.kb.doc.ChunkMarkup;
import com.example.qqbot.persistence.KbBlockRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库块的存储 —— **块和它的向量都在 SQLite 里，没有"行号"**。
 *
 * <h2>为什么合到数据库里（而不是继续 chunks.jsonl + index.bin）</h2>
 * 旧设计用"文件第 k 行"当块身份，好处是读得快、坏处是**不能删不能重排**：
 * 删一行会让后面全部错位，所以只能用墓碑顶着，文件只增不减。
 * 现在文档会**持续增删改**（外部 AI 一次给几个块），那套必然崩。
 *
 * <p>所以：身份 = {@code kb_block.id}，向量按 id 存 {@code kb_block_vec}。
 * 删块就是删两行；重排、压缩、重建都不用再顾虑"历史引用会指错"。
 *
 * <h2>为什么分两张表</h2>
 * 块表有正文（小、要常列），向量表是 17 MB 的 BLOB（大、只在检索时整批读）。
 * 合成一张的话，管理端"列出所有块"会顺手把 17 MB 也拖出来。
 *
 * <h2>本类只剩「语义」</h2>
 * SQL 与 {@code java.sql} 全部搬进了 {@link KbBlockRepository}。这里管的是：
 * 标签行的拆装（{@link ChunkMarkup} 的格式）、对外的异常类型、
 * 以及 {@code vec == null} 时"不动已有向量"这条调用约定。
 */
@Component
public class KbBlockStore {

    private static final Logger log = LoggerFactory.getLogger(KbBlockStore.class);

    /** 数据访问全部委托给它 —— SQL 与 java.sql 都在 persistence */
    private final KbBlockRepository repo;

    private volatile boolean available;

    /**
     * 语料版本：**每次写成功 +1**。
     *
     * <p>存在的理由和 {@code KbTermStore.version} 一样 —— 让内存快照
     * （{@link KbBlockIndex}）自己判断"我缓存的那一份是不是旧的"。旧契约是
     * "写完请调用方记得调 {@link KbBlockIndex#reload()}"：全项目 12 处手工调用，
     * 新增一个写入口就漏一个，而漏了的症状是**检索一直用旧语料、且不报错**。
     *
     * <p>初值取 1 而不是 0：{@code 0} 要留给"索引从未加载过"的哨兵（见 KbBlockIndex）。
     */
    private final java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong(1);

    public KbBlockStore(KbBlockRepository repo) {
        this.repo = repo;
    }

    /** 当前语料版本 —— 内存快照用它对账（见上面字段说明） */
    public long version() {
        return version.get();
    }

    /** 写成功之后 +1。只在**确实可能改变语料**的地方调 */
    private void touch() {
        version.incrementAndGet();
    }

    @PostConstruct
    public void init() {
        try {
            if (!repo.isAvailable()) {
                log.warn("[KB-BLOCK] 问答库不可用，块存储一并关闭（知识库检索不可用）");
                available = false;
                return;
            }
            // source 列的默认值是个业务常量，由这边传进去 —— 持久层不该依赖 kb.block
            repo.initSchema(KbBlock.SRC_DOC);
            available = true;
            log.info("[KB-BLOCK] 块存储就绪：{} 块（其中已下架 {}）", count(), countRetired());
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 初始化失败，知识库块功能不可用：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    // ==================== 读 ====================

    public int count() {
        return countOf(repo::count);
    }

    public int countRetired() {
        return countOf(repo::countRetired);
    }

    /** 有向量的块数（用来发现"块进了库但还没算向量"的半成品状态） */
    public int countVectors() {
        return countOf(repo::countVectors);
    }

    private int countOf(java.util.function.LongSupplier source) {
        if (!available) {
            return 0;
        }
        try {
            return (int) source.getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }

    public KbBlock get(String id) {
        if (!available || id == null || id.isBlank()) {
            return null;
        }
        try {
            KbBlockRepository.Row r = repo.find(id);
            return r == null ? null : toBlock(r);
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 读取块 {} 失败：{}", id, e.getMessage());
            return null;
        }
    }

    /** 全部块（含已下架），按 id 排序 —— 顺序稳定，便于测试与导出 */
    public List<KbBlock> all() {
        return blocks(repo::all, "查询");
    }

    /** 参与检索的块（不含已下架） */
    public List<KbBlock> allActive() {
        return blocks(repo::allActive, "查询");
    }

    /** 某份文档下的所有块 */
    public List<KbBlock> byDoc(String docId) {
        return blocks(() -> repo.byDoc(docId), "查询");
    }

    private List<KbBlock> blocks(java.util.function.Supplier<List<KbBlockRepository.Row>> source,
                                 String what) {
        if (!available) {
            return List.of();
        }
        try {
            List<KbBlock> out = new ArrayList<>();
            for (KbBlockRepository.Row r : source.get()) {
                out.add(toBlock(r));
            }
            return out;
        } catch (Exception e) {
            log.warn("[KB-BLOCK] {}失败：{}", what, e.getMessage());
            return List.of();
        }
    }

    /** 列里的 tags 是 {@link ChunkMarkup} 的分隔格式，拆开是这边的事 */
    private static KbBlock toBlock(KbBlockRepository.Row r) {
        return new KbBlock(r.id(), r.docId(), r.title(), r.body(), r.url(),
                ChunkMarkup.splitTags(r.tagsLine()), r.source(), r.retired(), r.updatedAt());
    }

    // ==================== 向量 ====================

    /** 读一个块的向量；没有就返回 {@code null} */
    public float[] vector(String id) {
        if (!available || id == null || id.isBlank()) {
            return null;
        }
        try {
            return repo.vector(id);
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 读取向量 {} 失败：{}", id, e.getMessage());
            return null;
        }
    }

    /** 一次读出全部向量（检索要整批做暴力余弦，逐条查会慢） */
    public Map<String, float[]> allVectors() {
        if (!available) {
            return new LinkedHashMap<>();
        }
        try {
            return repo.allVectors();
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 批量读向量失败：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 向量维度；一个都没有时返回 0 */
    public int dimensions() {
        if (!available) {
            return 0;
        }
        try {
            return repo.dimensions();
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 标题向量（C 路）====================

    /**
     * 一个块的标题向量 + **它是拿哪个标题算出来的**。
     *
     * <p>{@code title} 不是冗余字段：标题是这份向量的输入，输入变了它就是过期的。
     * 补建入口靠比对它来决定要不要重算，所以它必须一起存。
     */
    public record TitleVector(String title, float[] vec) {
    }

    /** 有多少块已经有标题向量（管理端看"补到几成"） */
    public int countTitleVectors() {
        return countOf(repo::countTitleVectors);
    }

    /** 读一个块的标题向量；没有就返回 {@code null} */
    public TitleVector titleVector(String id) {
        if (!available || id == null || id.isBlank()) {
            return null;
        }
        try {
            KbBlockRepository.TitleVec t = repo.titleVector(id);
            return t == null ? null : new TitleVector(t.title(), t.vec());
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 读取标题向量 {} 失败：{}", id, e.getMessage());
            return null;
        }
    }

    /** 一次读出全部标题向量（检索时和正文向量一样整批进内存） */
    public Map<String, TitleVector> allTitleVectors() {
        Map<String, TitleVector> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        try {
            for (Map.Entry<String, KbBlockRepository.TitleVec> e : repo.allTitleVectors().entrySet()) {
                out.put(e.getKey(), new TitleVector(e.getValue().title(), e.getValue().vec()));
            }
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 批量读标题向量失败：{}", e.getMessage());
        }
        return out;
    }

    /** 写入 / 覆盖一个块的标题向量。{@code title} 必须一起给（见 {@link TitleVector}） */
    public void upsertTitleVector(String id, String title, float[] vec) {
        if (!available || id == null || id.isBlank() || vec == null || vec.length == 0) {
            return;
        }
        try {
            repo.upsertTitleVector(id, title, vec);
            touch();
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 写入标题向量 {} 失败：{}", id, e.getMessage());
        }
    }

    /** 删掉一个块的标题向量（块本身还在时用，比如模型换了要整批重算） */
    public void deleteTitleVector(String id) {
        if (!available || id == null || id.isBlank()) {
            return;
        }
        try {
            repo.deleteTitleVector(id);
            touch();
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 删除标题向量 {} 失败：{}", id, e.getMessage());
        }
    }

    // ==================== 写 ====================

    /**
     * 写入或覆盖一个块（按 id）。
     *
     * <p>{@code vec} 为 {@code null} 时**不动已有向量** —— 这样"只改标题/标签"
     * 这种不需要重算向量的操作，不用白白花一次 embedding 调用。
     *
     * <p>「是新增还是覆盖」由 {@link KbBlockRepository#upsert} 在**事务内**判定：
     * 判定放在外面的话，两个并发的导入可能都判成"新增"，统计就与实际相反了。
     *
     * @return true = 是新增；false = 覆盖了已有的
     */
    public boolean upsert(KbBlock b, float[] vec) {
        if (!available) {
            throw new IllegalStateException("块存储不可用");
        }
        if (b == null || b.id() == null || b.id().isBlank()) {
            throw new IllegalArgumentException("块 id 不能为空");
        }
        String now = b.updatedAt() == null || b.updatedAt().isBlank()
                ? Instant.now().toString() : b.updatedAt();
        KbBlockRepository.Row row = new KbBlockRepository.Row(b.id(), b.docId(), b.title(), b.body(),
                b.url(), b.tagsLine(), b.source(), b.retired(), now);
        try {
            boolean added = repo.upsert(row, vec);
            touch();
            return added;
        } catch (Exception e) {
            throw new IllegalStateException("写入块失败：" + e.getMessage(), e);
        }
    }

    /** 下架 / 恢复。**保留数据**，只改标记 —— 与旧的墓碑机制不同，这里可以真删也可以留着 */
    public boolean setRetired(String id, boolean retired) {
        if (!available) {
            return false;
        }
        try {
            boolean changed = repo.setRetired(id, retired, Instant.now().toString());
            if (changed) {
                touch();
            }
            return changed;
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 下架/恢复失败：{}", e.getMessage());
            return false;
        }
    }

    /** 真删（块 + 向量）。旧设计做不到这件事，只能打墓碑 —— 这是 id 身份换来的自由 */
    public boolean delete(String id) {
        if (!available) {
            return false;
        }
        try {
            boolean deleted = repo.delete(id);
            if (deleted) {
                touch();
            }
            return deleted;
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 删除失败：{}", e.getMessage());
            return false;
        }
    }

    /** 清空全部块与向量（"首次导入前清空语料"用） */
    public void clearAll() {
        if (!available) {
            return;
        }
        try {
            repo.clearAll();
            touch();
            log.warn("[KB-BLOCK] 已清空全部块、正文向量与标题向量");
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 清空失败：{}", e.getMessage());
        }
    }
}
