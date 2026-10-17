package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库块与向量（{@code kb_block} / {@code kb_block_vec} / {@code kb_block_title_vec}）的**数据访问**。
 *
 * <h2>为什么分两张表</h2>
 * 块表有正文（小、要常列），向量表是 17 MB 的 BLOB（大、只在检索时整批读）。
 * 合成一张的话，管理端"列出所有块"会顺手把 17 MB 也拖出来。
 *
 * <h2>为什么标题向量单开一张表</h2>
 * {@code kb_block_vec} 的主键是 id，一列存不下"一个块两条向量"；重做主键要删表重建，
 * 而新建一张表的迁移风险是零（老表一个字节都不用动）。
 *
 * <h2>三处不能顺手改掉的东西</h2>
 * <ul>
 *   <li>{@link #upsert} 里 {@code vec == null} 时**不动已有向量** —— 这样"只改标题/标签"
 *       这种不需要重算向量的操作，不用白白花一次 embedding 调用。</li>
 *   <li>{@link #delete} 必须**连标题向量一起删** —— 漏了它，删掉再新建一个同 id 的块
 *       会捡到旧标题的向量（{@code TitleVector.title} 是它过期与否的唯一判据）。</li>
 *   <li>{@link #upsert} 的 {@code isNew} 要在**事务内**算。算在外面的话，
 *       两个并发的导入可能都判成"新增"，于是统计与实际相反。</li>
 * </ul>
 *
 * <h2>为什么向量的字节编解码在这里，而 {@code group_ids} 的 JSON 在业务侧</h2>
 * 两边都是"列的编码方式"，但依赖成本不同：向量的编解码是**纯 JDK**
 * （{@code ByteBuffer}），放在这里不引入任何依赖；JSON 那套需要 Jackson，
 * 而业务类本来就持有 {@code ObjectMapper} —— 为了不把 Jackson 拖进持久层，留在那边。
 */
@Repository
public class KbBlockRepository {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(KbBlockRepository.class);

    /**
     * 一行块。
     *
     * <p>{@code tagsLine} 是列里的原文（{@code ChunkMarkup} 的分隔格式），
     * 拆成 {@code List<String>} 是业务侧的事 —— 那需要 {@code kb.doc} 的类型。
     */
    public record Row(String id, String docId, String title, String body, String url,
                      String tagsLine, String source, boolean retired, String updatedAt,
                      String alias) {
    }

    /** 一个标题向量行。{@code title} 是这份向量的**输入** —— 输入变了它就是过期的 */
    public record TitleVec(String title, float[] vec) {
    }

    private static final String BLOCK_COLUMNS =
            "id, doc_id, title, body, url, tags, source, retired, updated_at, alias";

    private static final Jdbc.RowMapper<Row> BLOCK = r -> new Row(
            r.str("id"), r.str("doc_id"), r.str("title"), r.str("body"), r.str("url"),
            r.str("tags"), r.str("source"), r.intOf("retired") == 1, r.str("updated_at"),
            r.str("alias") == null ? "" : r.str("alias"));

    private final Jdbc jdbc;

    /**
     * 块表的**指纹**：条数 + 最大更新时间。
     *
     * <p>用途与词条表那边一样：发现"别的进程改了库"。{@link com.example.qqbot.kb.block.KbBlockStore}
     * 的版本号是**内存计数器**，离线工具直接改库时它一动不动 —— 索引就一直用旧语料、
     * 而且不报错。这个查询只有一行，够便宜，可以低频轮询。
     */
    public String stamp() {
        return jdbc.queryOne("SELECT COUNT(*) AS n, COALESCE(MAX(updated_at), '') AS t FROM kb_block",
                r -> r.intOf("n") + "@" + r.str("t"));
    }

    public KbBlockRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    /**
     * @param defaultSource {@code source} 列的默认值。**由业务侧传进来** ——
     *                      那是 {@code KbBlock.SRC_DOC} 这个业务常量的值，
     *                      持久层不该反向依赖 {@code kb.block}。
     */
    public void initSchema(String defaultSource) {
        // 用普通字符串而不是文本块 —— 与 KbCategoryRepository 保持一致
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_block ("
                + " id         TEXT PRIMARY KEY,"
                + " doc_id     TEXT NOT NULL DEFAULT '',"
                + " title      TEXT NOT NULL DEFAULT '',"
                + " body       TEXT NOT NULL DEFAULT '',"
                + " url        TEXT NOT NULL DEFAULT '',"
                + " tags       TEXT NOT NULL DEFAULT '',"
                + " source     TEXT NOT NULL DEFAULT '" + defaultSource + "',"
                + " retired    INTEGER NOT NULL DEFAULT 0,"
                + " updated_at TEXT NOT NULL DEFAULT '',"
                + " alias      TEXT NOT NULL DEFAULT '')");
        // 老库没有 alias 列 —— 补一次（SQLite 的 ADD COLUMN 重复执行会报错，所以先查再补）
        if (!hasColumn("kb_block", "alias")) {
            jdbc.execute("ALTER TABLE kb_block ADD COLUMN alias TEXT NOT NULL DEFAULT ''");
            // 只在"刚刚加上这一列"的那一次做旧数据搬迁 —— 见下
            migrateLegacyTermAliases();
        }
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_block_doc ON kb_block(doc_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_block_retired ON kb_block(retired)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_block_vec ("
                + " id   TEXT PRIMARY KEY,"
                + " dim  INTEGER NOT NULL,"
                + " vec  BLOB NOT NULL)");

        // C 路（标题向量）—— 见类注释
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_block_title_vec ("
                + " id    TEXT PRIMARY KEY,"
                + " title TEXT NOT NULL DEFAULT '',"
                + " dim   INTEGER NOT NULL,"
                + " vec   BLOB NOT NULL)");
    }

    /** 表里有没有这一列 —— 迁移用（SQLite 没有 IF NOT EXISTS 的 ADD COLUMN） */
    private boolean hasColumn(String table, String column) {
        return jdbc.query("PRAGMA table_info(" + table + ")",
                        r -> r.str("name"), new Object[0])
                .stream().anyMatch(column::equalsIgnoreCase);
    }

    private boolean hasTable(String table) {
        return jdbc.queryOne("SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                r -> r.str("name"), table) != null;
    }

    /**
     * 旧库的**一次性**搬迁：把老名字表（{@code kb_term}）里人工填的中文名搬进 {@code alias}。
     *
     * <p>只在 {@code alias} 列刚加上那一次运行 —— 旧库有几千条人工译名，直接丢掉
     * 会让中文提问重新够不到那些英文页（B 路关键词检索的燃料就是这些名字）。
     *
     * <p>只搬「英文标题 + 旧中文名里含非 ASCII」那批：中文标题的块本来就自带中文，
     * 旧 {@code zh} 只是标题的副本，搬过来只会制造重复别名。
     *
     * <p>搬完不清 {@code kb_term} —— 留着它是一份可回溯的备份，也不会再被读取。
     */
    private void migrateLegacyTermAliases() {
        if (!hasTable("kb_term")) {
            return;
        }
        try {
            int n = jdbc.update(
                    "UPDATE kb_block SET alias = (SELECT t.zh FROM kb_term t WHERE t.en = kb_block.id)"
                            + " WHERE alias = ''"
                            + "   AND kb_block.title NOT GLOB '*[^ -~]*'"
                            + "   AND EXISTS (SELECT 1 FROM kb_term t WHERE t.en = kb_block.id"
                            + "               AND t.zh <> '' AND t.zh GLOB '*[^ -~]*')");
            if (n > 0) {
                log.info("[KB-BLOCK] 已把 {} 条旧词条中文名搬迁为别名", n);
            }
        } catch (Exception e) {
            log.warn("[KB-BLOCK] 旧词条中文名搬迁失败（不影响启动）：{}", e.getMessage());
        }
    }

    // ==================== 读 ====================

    public long count() {
        return jdbc.count("SELECT COUNT(*) FROM kb_block");
    }

    public long countRetired() {
        return jdbc.count("SELECT COUNT(*) FROM kb_block WHERE retired = 1");
    }

    /** 有向量的块数（用来发现"块进了库但还没算向量"的半成品状态） */
    public long countVectors() {
        return jdbc.count("SELECT COUNT(*) FROM kb_block_vec");
    }

    /** 有多少块已经有标题向量（管理端看"补到几成"） */
    public long countTitleVectors() {
        return jdbc.count("SELECT COUNT(*) FROM kb_block_title_vec");
    }

    public Row find(String id) {
        return jdbc.queryOne("SELECT " + BLOCK_COLUMNS + " FROM kb_block WHERE id = ?", BLOCK, id);
    }

    /** 全部块（含已下架），按 id 排序 —— 顺序稳定，便于测试与导出 */
    public List<Row> all() {
        return jdbc.query("SELECT " + BLOCK_COLUMNS + " FROM kb_block ORDER BY id", BLOCK);
    }

    /** 参与检索的块（不含已下架） */
    public List<Row> allActive() {
        return jdbc.query("SELECT " + BLOCK_COLUMNS
                + " FROM kb_block WHERE retired = 0 ORDER BY id", BLOCK);
    }

    /**
     * 某个来源下、每个 docId **生效**的块数。
     *
     * <p>管理端「抓取」页用它回答"这个来源的知识入库了没有" —— 块是按
     * {@code wiki·<来源名>} 分组的，所以数一遍就能看出漏了哪个来源。
     * 只数 {@code retired = 0}：下架的块不算生效的知识。
     */
    public Map<String, Integer> countActiveByDoc(String source) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : jdbc.query(
                "SELECT doc_id AS d, COUNT(*) AS n FROM kb_block"
                        + " WHERE retired = 0 AND source = ? GROUP BY doc_id",
                r -> Map.entry(r.str("d"), r.intOf("n")), source)) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 某份文档下的所有块 */
    public List<Row> byDoc(String docId) {
        return jdbc.query("SELECT " + BLOCK_COLUMNS
                + " FROM kb_block WHERE doc_id = ? ORDER BY id", BLOCK, docId);
    }

    // ==================== 向量 ====================

    /** 读一个块的向量；没有就返回 {@code null} */
    public float[] vector(String id) {
        return jdbc.queryOne("SELECT dim, vec FROM kb_block_vec WHERE id = ?",
                r -> toFloats(r.intOf("dim"), r.bytes("vec")), id);
    }

    /** 一次读出全部向量（检索要整批做暴力余弦，逐条查会慢） */
    public Map<String, float[]> allVectors() {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, float[]> e : jdbc.query("SELECT id, dim, vec FROM kb_block_vec",
                r -> Map.entry(r.str("id"), toFloats(r.intOf("dim"), r.bytes("vec"))))) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 向量维度；一个都没有时返回 0 */
    public int dimensions() {
        Integer d = jdbc.queryOne("SELECT dim FROM kb_block_vec LIMIT 1", r -> r.intOf("dim"));
        return d == null ? 0 : d;
    }

    // ==================== 标题向量（C 路）====================

    /** 读一个块的标题向量；没有就返回 {@code null} */
    public TitleVec titleVector(String id) {
        return jdbc.queryOne("SELECT title, dim, vec FROM kb_block_title_vec WHERE id = ?",
                r -> new TitleVec(r.str("title"), toFloats(r.intOf("dim"), r.bytes("vec"))), id);
    }

    /** 一次读出全部标题向量（检索时和正文向量一样整批进内存） */
    public Map<String, TitleVec> allTitleVectors() {
        Map<String, TitleVec> out = new LinkedHashMap<>();
        for (Map.Entry<String, TitleVec> e : jdbc.query("SELECT id, title, dim, vec FROM kb_block_title_vec",
                r -> Map.entry(r.str("id"),
                        new TitleVec(r.str("title"), toFloats(r.intOf("dim"), r.bytes("vec")))))) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 写入 / 覆盖一个块的标题向量。{@code title} 必须一起给（见 {@link TitleVec}） */
    public void upsertTitleVector(String id, String title, float[] vec) {
        jdbc.update("INSERT INTO kb_block_title_vec (id, title, dim, vec) VALUES (?,?,?,?)"
                        + " ON CONFLICT(id) DO UPDATE SET"
                        + " title=excluded.title, dim=excluded.dim, vec=excluded.vec",
                id, title == null ? "" : title, vec.length, toBytes(vec));
    }

    /** 删掉一个块的标题向量（块本身还在时用，比如模型换了要整批重算） */
    public void deleteTitleVector(String id) {
        jdbc.update("DELETE FROM kb_block_title_vec WHERE id = ?", id);
    }

    // ==================== 写 ====================

    /**
     * 写入或覆盖一个块（按 id），返回 {@code true} 表示**是新增**。
     *
     * <p>{@code vec} 为 {@code null} 时不动已有向量（见类注释）。
     */
    public boolean upsert(Row row, float[] vec) {
        return jdbc.transaction(() -> {
            boolean isNew = jdbc.count("SELECT COUNT(*) FROM kb_block WHERE id = ?", row.id()) == 0;
            jdbc.update("INSERT INTO kb_block (id, doc_id, title, body, url, tags, source, retired, updated_at, alias)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)"
                            + " ON CONFLICT(id) DO UPDATE SET"
                            + " doc_id=excluded.doc_id, title=excluded.title, body=excluded.body,"
                            + " url=excluded.url, tags=excluded.tags, source=excluded.source,"
                            + " retired=excluded.retired, updated_at=excluded.updated_at, alias=excluded.alias",
                    row.id(), row.docId(), row.title(), row.body(), row.url(), row.tagsLine(),
                    row.source(), row.retired(), row.updatedAt(), row.alias() == null ? "" : row.alias());
            if (vec != null) {
                jdbc.update("INSERT INTO kb_block_vec (id, dim, vec) VALUES (?,?,?)"
                                + " ON CONFLICT(id) DO UPDATE SET dim=excluded.dim, vec=excluded.vec",
                        row.id(), vec.length, toBytes(vec));
            }
            return isNew;
        });
    }

    /**
     * 只改别名 —— 不碰正文，所以不需要重算向量。
     *
     * <p>别名是人工成果，导入不覆盖它；改它也走这一条独立的窄 UPDATE。
     */
    public boolean updateAlias(String id, String alias, String now) {
        return jdbc.update("UPDATE kb_block SET alias = ?, updated_at = ? WHERE id = ?",
                alias == null ? "" : alias, now, id) > 0;
    }

    /** 下架 / 恢复。**保留数据**，只改标记 */
    public boolean setRetired(String id, boolean retired, String now) {
        return jdbc.update("UPDATE kb_block SET retired=?, updated_at=? WHERE id=?",
                retired, now, id) > 0;
    }

    /** 真删（块 + 正文向量 + 标题向量，一个事务）。旧设计做不到这件事，只能打墓碑 */
    public boolean delete(String id) {
        return jdbc.transaction(() -> {
            int n = jdbc.update("DELETE FROM kb_block WHERE id=?", id);
            jdbc.update("DELETE FROM kb_block_vec WHERE id=?", id);
            // 标题向量跟着块走 —— 见类注释
            jdbc.update("DELETE FROM kb_block_title_vec WHERE id=?", id);
            return n > 0;
        });
    }

    /** 清空全部块与向量（"首次导入前清空语料"用），一个事务 */
    public void clearAll() {
        jdbc.transaction(() -> {
            jdbc.update("DELETE FROM kb_block");
            jdbc.update("DELETE FROM kb_block_vec");
            jdbc.update("DELETE FROM kb_block_title_vec");
            return null;
        });
    }

    // ==================== 字节编解码 ====================

    private static byte[] toBytes(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.BIG_ENDIAN);
        for (float f : v) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    private static float[] toFloats(int dim, byte[] raw) {
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = buf.getFloat();
        }
        return v;
    }
}
