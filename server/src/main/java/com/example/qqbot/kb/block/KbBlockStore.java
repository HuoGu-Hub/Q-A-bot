package com.example.qqbot.kb.block;

import com.example.qqbot.kb.EntryId;
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
public class KbBlockStore implements com.example.qqbot.kb.TermSource {

    private static final Logger log = LoggerFactory.getLogger(KbBlockStore.class);

    /** 数据访问全部委托给它 —— SQL 与 java.sql 都在 persistence */
    private final KbBlockRepository repo;

    private volatile boolean available;

    /**
     * 语料版本：**每次写成功 +1**。
     *
     * <p>存在的理由：让内存快照
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

    /** 外部写入检测的间隔（毫秒）。测试里设成 0 就能立刻发现 */
    private static final long EXTERNAL_CHECK_MILLIS =
            Long.getLong("qqbot.block.external-check-millis", 30000L);

    /** 上一次查库里指纹的时间与结果 —— 见 {@link #version()} */
    private volatile long lastExternalCheck;
    private volatile String lastExternalStamp = "";

    /**
     * 当前语料版本 —— 内存快照用它对账（见上面字段说明）。
     *
     * <p>除了本进程的写入，还会低频比对库里的指纹，用来发现**别的进程**改了库
     * （离线工具、直接 SQL）。内存计数器对它们无感，而症状是"检索一直用旧语料、
     * 且不报错" —— 最难查的一类问题。
     */
    public long version() {
        long now = System.currentTimeMillis();
        if (now - lastExternalCheck >= EXTERNAL_CHECK_MILLIS) {
            lastExternalCheck = now;
            String stamp;
            try {
                stamp = repo.stamp();
            } catch (Exception e) {
                stamp = lastExternalStamp;   // 查不动就当没变，别把检索拖崩
            }
            if (!stamp.equals(lastExternalStamp)) {
                lastExternalStamp = stamp;
                version.incrementAndGet();
                log.info("[KB-BLOCK] 检测到外部写入（{}），语料版本 +1", stamp);
            }
        }
        return version.get();
    }

    /** 让下一次 {@link #version()} 立刻比对库里的指纹，不等轮询间隔。包内可见，给测试与排查用 */
    void forceExternalCheck() {
        lastExternalCheck = 0L;
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

    /**
     * 某个来源下、每个 docId 生效的块数。
     *
     * <p>管理端「抓取」页拿它显示"这个来源入库了多少块" —— 见
     * {@link com.example.qqbot.persistence.KbBlockRepository#countActiveByDoc}。
     */
    public Map<String, Integer> activeCountByDoc(String source) {
        return available ? repo.countActiveByDoc(source) : Map.of();
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

    public KbBlock get(EntryId id) {
        if (!available || id == null || id.isBlank()) {
            return null;
        }
        try {
            KbBlockRepository.Row r = repo.find(id.value());
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
        return new KbBlock(EntryId.of(r.id()), r.docId(), r.title(), r.body(), r.url(),
                ChunkMarkup.splitTags(r.tagsLine()), r.source(), r.retired(), r.updatedAt(),
                r.alias() == null ? "" : r.alias());
    }

    // ==================== 名字视图（TermSource）====================

    /**
     * 检索侧要的名字行 —— **词条就是分块**，所以直接用块表当名字源。
     *
     * <p>只给 {@code id / 标题 / 别名}：标题里「（English）」那截由
     * {@code TermNames} 在读时派生，存库那份别名只装人工写的东西。
     */
    @Override
    public List<com.example.qqbot.kb.TermSource.Row> rows() {
        List<com.example.qqbot.kb.TermSource.Row> out = new ArrayList<>();
        for (KbBlock b : allActive()) {
            out.add(new com.example.qqbot.kb.TermSource.Row(b.id(), b.title(), b.aliasOrEmpty()));
        }
        return out;
    }

    // ==================== 向量 ====================

    /** 读一个块的向量；没有就返回 {@code null} */
    public float[] vector(EntryId id) {
        if (!available || id == null || id.isBlank()) {
            return null;
        }
        try {
            return repo.vector(id.value());
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 读取向量 {} 失败：{}", id, e.getMessage());
            return null;
        }
    }

    /** 一次读出全部向量（检索要整批做暴力余弦，逐条查会慢） */
    public Map<EntryId, float[]> allVectors() {
        Map<EntryId, float[]> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        try {
            for (Map.Entry<String, float[]> e : repo.allVectors().entrySet()) {
                out.put(EntryId.of(e.getKey()), e.getValue());
            }
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 批量读向量失败：{}", e.getMessage());
        }
        return out;
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
    public TitleVector titleVector(EntryId id) {
        if (!available || id == null || id.isBlank()) {
            return null;
        }
        try {
            KbBlockRepository.TitleVec t = repo.titleVector(id.value());
            return t == null ? null : new TitleVector(t.title(), t.vec());
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 读取标题向量 {} 失败：{}", id, e.getMessage());
            return null;
        }
    }

    /** 一次读出全部标题向量（检索时和正文向量一样整批进内存） */
    public Map<EntryId, TitleVector> allTitleVectors() {
        Map<EntryId, TitleVector> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        try {
            for (Map.Entry<String, KbBlockRepository.TitleVec> e : repo.allTitleVectors().entrySet()) {
                out.put(EntryId.of(e.getKey()), new TitleVector(e.getValue().title(), e.getValue().vec()));
            }
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 批量读标题向量失败：{}", e.getMessage());
        }
        return out;
    }

    /** 写入 / 覆盖一个块的标题向量。{@code title} 必须一起给（见 {@link TitleVector}） */
    public void upsertTitleVector(EntryId id, String title, float[] vec) {
        if (!available || id == null || id.isBlank() || vec == null || vec.length == 0) {
            return;
        }
        try {
            repo.upsertTitleVector(id.value(), title, vec);
            touch();
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 写入标题向量 {} 失败：{}", id, e.getMessage());
        }
    }

    /** 删掉一个块的标题向量（块本身还在时用，比如模型换了要整批重算） */
    public void deleteTitleVector(EntryId id) {
        if (!available || id == null || id.isBlank()) {
            return;
        }
        try {
            repo.deleteTitleVector(id.value());
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
        KbBlockRepository.Row row = new KbBlockRepository.Row(b.id().value(), b.docId(), b.title(), b.body(),
                b.url(), b.tagsLine(), b.source(), b.retired(), now, b.aliasOrEmpty());
        try {
            boolean added = repo.upsert(row, vec);
            touch();
            return added;
        } catch (Exception e) {
            throw new IllegalStateException("写入块失败：" + e.getMessage(), e);
        }
    }

    /**
     * 只改一条词条的别名 —— **不重算向量**（别名不是正文）。
     *
     * <p>导入器不覆盖别名，所以这是别名唯一的写入路径。
     */
    public boolean updateAlias(EntryId id, String alias) {
        if (!available || id == null || id.isBlank()) {
            return false;
        }
        try {
            boolean changed = repo.updateAlias(id.value(), alias == null ? "" : alias.trim(),
                    Instant.now().toString());
            if (changed) {
                touch();
            }
            return changed;
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 改别名 {} 失败：{}", id, e.getMessage());
            return false;
        }
    }

    /** 下架 / 恢复。**保留数据**，只改标记 —— 与旧的墓碑机制不同，这里可以真删也可以留着 */
    public boolean setRetired(EntryId id, boolean retired) {
        if (!available) {
            return false;
        }
        try {
            boolean changed = repo.setRetired(id.value(), retired, Instant.now().toString());
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
    public boolean delete(EntryId id) {
        if (!available) {
            return false;
        }
        try {
            boolean deleted = repo.delete(id.value());
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
