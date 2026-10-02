package com.example.qqbot.kb.block;

import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.doc.ChunkMarkup;
import com.example.qqbot.persistence.SqliteConnectionProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * <h2>连接</h2>
 * ⚠️ 复用 {@link com.example.qqbot.persistence.SqliteConnectionProvider#connection()}，不自己开连接 —— 两个连接操作同一个
 * SQLite 文件会让 WAL 的共享内存段冲突（实测报 {@code SQLITE_IOERR_SHMOPEN}）。
 */
@Component
@DependsOn("qaStore")
public class KbBlockStore {

    private static final Logger log = LoggerFactory.getLogger(KbBlockStore.class);

    private final SqliteConnectionProvider db;

    private volatile boolean available;

    public KbBlockStore(SqliteConnectionProvider db) {
        this.db = db;
    }

    private Connection conn() {
        return db.connection();
    }

    @PostConstruct
    public void init() {
        try {
            if (!db.isAvailable()) {
                log.warn("[KB-BLOCK] 问答库不可用，块存储一并关闭（知识库检索不可用）");
                available = false;
                return;
            }
            try (Statement st = conn().createStatement()) {
                // 用普通字符串而不是文本块 —— 与 KbTermStore / CategoryStore 保持一致
                st.execute("CREATE TABLE IF NOT EXISTS kb_block ("
                        + " id         TEXT PRIMARY KEY,"
                        + " doc_id     TEXT NOT NULL DEFAULT '',"
                        + " title      TEXT NOT NULL DEFAULT '',"
                        + " body       TEXT NOT NULL DEFAULT '',"
                        + " url        TEXT NOT NULL DEFAULT '',"
                        + " tags       TEXT NOT NULL DEFAULT '',"
                        + " source     TEXT NOT NULL DEFAULT '" + KbBlock.SRC_DOC + "',"
                        + " retired    INTEGER NOT NULL DEFAULT 0,"
                        + " updated_at TEXT NOT NULL DEFAULT '')");
                st.execute("CREATE INDEX IF NOT EXISTS idx_block_doc ON kb_block(doc_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_block_retired ON kb_block(retired)");

                st.execute("CREATE TABLE IF NOT EXISTS kb_block_vec ("
                        + " id   TEXT PRIMARY KEY,"
                        + " dim  INTEGER NOT NULL,"
                        + " vec  BLOB NOT NULL)");

                // C 路（标题向量）。单开一张表而不是给 kb_block_vec 加一列：
                // 那张表的主键是 id，一列存不下"一个块两条向量"；重做主键要删表重建，
                // 而新建一张表的迁移风险是零（老表一个字节都不用动）。
                //
                // ★ 为什么要存 title：这份向量是"对哪个标题算的"。标题改了而对不上，
                //   就说明它过期了 —— 这是补建入口判断"要不要重算"的唯一依据。
                st.execute("CREATE TABLE IF NOT EXISTS kb_block_title_vec ("
                        + " id    TEXT PRIMARY KEY,"
                        + " title TEXT NOT NULL DEFAULT '',"
                        + " dim   INTEGER NOT NULL,"
                        + " vec   BLOB NOT NULL)");
            }
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
        return scalar("SELECT COUNT(*) FROM kb_block");
    }

    public int countRetired() {
        return scalar("SELECT COUNT(*) FROM kb_block WHERE retired = 1");
    }

    /** 有向量的块数（用来发现"块进了库但还没算向量"的半成品状态） */
    public int countVectors() {
        return scalar("SELECT COUNT(*) FROM kb_block_vec");
    }

    public KbBlock get(String id) {
        if (!available || id == null || id.isBlank()) {
            return null;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT * FROM kb_block WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            } catch (Exception e) {
                log.warn("[KB-BLOCK] 读取块 {} 失败：{}", id, e.getMessage());
                return null;
            }
        }
    }

    /** 全部块（含已下架），按 id 排序 —— 顺序稳定，便于测试与导出 */
    public List<KbBlock> all() {
        return query("SELECT * FROM kb_block ORDER BY id", null);
    }

    /** 参与检索的块（不含已下架） */
    public List<KbBlock> allActive() {
        return query("SELECT * FROM kb_block WHERE retired = 0 ORDER BY id", null);
    }

    /** 某份文档下的所有块 */
    public List<KbBlock> byDoc(String docId) {
        return query("SELECT * FROM kb_block WHERE doc_id = ? ORDER BY id", docId);
    }

    private List<KbBlock> query(String sql, String param) {
        List<KbBlock> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                if (param != null) {
                    ps.setString(1, param);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            } catch (Exception e) {
                log.warn("[KB-BLOCK] 查询失败：{}", e.getMessage());
            }
        }
        return out;
    }

    private int scalar(String sql) {
        if (!available) {
            return 0;
        }
        synchronized (this) {
            try (Statement st = conn().createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getInt(1);
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private KbBlock map(ResultSet rs) throws SQLException {
        return new KbBlock(
                rs.getString("id"),
                rs.getString("doc_id"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("url"),
                ChunkMarkup.splitTags(rs.getString("tags")),
                rs.getString("source"),
                rs.getInt("retired") == 1,
                rs.getString("updated_at"));
    }

    // ==================== 向量 ====================

    /** 读一个块的向量；没有就返回 {@code null} */
    public float[] vector(String id) {
        if (!available || id == null || id.isBlank()) {
            return null;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT dim, vec FROM kb_block_vec WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return toFloats(rs.getInt("dim"), rs.getBytes("vec"));
                }
            } catch (Exception e) {
                log.warn("[KB-BLOCK] 读取向量 {} 失败：{}", id, e.getMessage());
                return null;
            }
        }
    }

    /** 一次读出全部向量（检索要整批做暴力余弦，逐条查会慢） */
    public Map<String, float[]> allVectors() {
        Map<String, float[]> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        synchronized (this) {
            try (Statement st = conn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, dim, vec FROM kb_block_vec")) {
                while (rs.next()) {
                    out.put(rs.getString("id"), toFloats(rs.getInt("dim"), rs.getBytes("vec")));
                }
            } catch (Exception e) {
                log.warn("[KB-BLOCK] 批量读向量失败：{}", e.getMessage());
            }
        }
        return out;
    }

    /** 向量维度；一个都没有时返回 0 */
    public int dimensions() {
        if (!available) {
            return 0;
        }
        synchronized (this) {
            try (Statement st = conn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT dim FROM kb_block_vec LIMIT 1")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (Exception e) {
                return 0;
            }
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
        return scalar("SELECT COUNT(*) FROM kb_block_title_vec");
    }

    /** 读一个块的标题向量；没有就返回 {@code null} */
    public TitleVector titleVector(String id) {
        if (!available || id == null || id.isBlank()) {
            return null;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT title, dim, vec FROM kb_block_title_vec WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new TitleVector(rs.getString("title"),
                            toFloats(rs.getInt("dim"), rs.getBytes("vec")));
                }
            } catch (Exception e) {
                log.warn("[KB-BLOCK] 读取标题向量 {} 失败：{}", id, e.getMessage());
                return null;
            }
        }
    }

    /** 一次读出全部标题向量（检索时和正文向量一样整批进内存） */
    public Map<String, TitleVector> allTitleVectors() {
        Map<String, TitleVector> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        synchronized (this) {
            try (Statement st = conn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, title, dim, vec FROM kb_block_title_vec")) {
                while (rs.next()) {
                    out.put(rs.getString("id"), new TitleVector(rs.getString("title"),
                            toFloats(rs.getInt("dim"), rs.getBytes("vec"))));
                }
            } catch (Exception e) {
                log.warn("[KB-BLOCK] 批量读标题向量失败：{}", e.getMessage());
            }
        }
        return out;
    }

    /** 写入 / 覆盖一个块的标题向量。{@code title} 必须一起给（见 {@link TitleVector}） */
    public void upsertTitleVector(String id, String title, float[] vec) {
        if (!available || id == null || id.isBlank() || vec == null || vec.length == 0) {
            return;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "INSERT INTO kb_block_title_vec (id, title, dim, vec) VALUES (?,?,?,?)"
                            + " ON CONFLICT(id) DO UPDATE SET"
                            + " title=excluded.title, dim=excluded.dim, vec=excluded.vec")) {
                ps.setString(1, id);
                ps.setString(2, title == null ? "" : title);
                ps.setInt(3, vec.length);
                ps.setBytes(4, toBytes(vec));
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warn("[KB-BLOCK] 写入标题向量 {} 失败：{}", id, e.getMessage());
            }
        }
    }

    /** 删掉一个块的标题向量（块本身还在时用，比如模型换了要整批重算） */
    public void deleteTitleVector(String id) {
        if (!available || id == null || id.isBlank()) {
            return;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "DELETE FROM kb_block_title_vec WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warn("[KB-BLOCK] 删除标题向量 {} 失败：{}", id, e.getMessage());
            }
        }
    }

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

    // ==================== 写 ====================

    /**
     * 写入或覆盖一个块（按 id）。
     *
     * <p>{@code vec} 为 {@code null} 时**不动已有向量** —— 这样"只改标题/标签"
     * 这种不需要重算向量的操作，不用白白花一次 embedding 调用。
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
        boolean isNew = get(b.id()) == null;
        String now = b.updatedAt() == null || b.updatedAt().isBlank()
                ? Instant.now().toString() : b.updatedAt();

        synchronized (this) {
            try {
                try (PreparedStatement ps = conn().prepareStatement(
                        "INSERT INTO kb_block (id, doc_id, title, body, url, tags, source, retired, updated_at)"
                                + " VALUES (?,?,?,?,?,?,?,?,?)"
                                + " ON CONFLICT(id) DO UPDATE SET"
                                + " doc_id=excluded.doc_id, title=excluded.title, body=excluded.body,"
                                + " url=excluded.url, tags=excluded.tags, source=excluded.source,"
                                + " retired=excluded.retired, updated_at=excluded.updated_at")) {
                    ps.setString(1, b.id());
                    ps.setString(2, b.docId());
                    ps.setString(3, b.title());
                    ps.setString(4, b.body());
                    ps.setString(5, b.url());
                    ps.setString(6, b.tagsLine());
                    ps.setString(7, b.source());
                    ps.setInt(8, b.retired() ? 1 : 0);
                    ps.setString(9, now);
                    ps.executeUpdate();
                }
                if (vec != null) {
                    try (PreparedStatement ps = conn().prepareStatement(
                            "INSERT INTO kb_block_vec (id, dim, vec) VALUES (?,?,?)"
                                    + " ON CONFLICT(id) DO UPDATE SET dim=excluded.dim, vec=excluded.vec")) {
                        ps.setString(1, b.id());
                        ps.setInt(2, vec.length);
                        ps.setBytes(3, toBytes(vec));
                        ps.executeUpdate();
                    }
                }
                return isNew;
            } catch (SQLException e) {
                throw new IllegalStateException("写入块失败：" + e.getMessage(), e);
            }
        }
    }

    /** 下架 / 恢复。**保留数据**，只改标记 —— 与旧的墓碑机制不同，这里可以真删也可以留着 */
    public boolean setRetired(String id, boolean retired) {
        if (!available) {
            return false;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE kb_block SET retired=?, updated_at=? WHERE id=?")) {
                ps.setInt(1, retired ? 1 : 0);
                ps.setString(2, Instant.now().toString());
                ps.setString(3, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                log.warn("[KB-BLOCK] 下架/恢复失败：{}", e.getMessage());
                return false;
            }
        }
    }

    /** 真删（块 + 向量）。旧设计做不到这件事，只能打墓碑 —— 这是 id 身份换来的自由 */
    public boolean delete(String id) {
        if (!available) {
            return false;
        }
        synchronized (this) {
            try {
                int n;
                try (PreparedStatement ps = conn().prepareStatement("DELETE FROM kb_block WHERE id=?")) {
                    ps.setString(1, id);
                    n = ps.executeUpdate();
                }
                try (PreparedStatement ps = conn().prepareStatement("DELETE FROM kb_block_vec WHERE id=?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                // 标题向量跟着块走 —— 漏了它，删掉再新建一个同 id 的块会捡到旧标题的向量
                deleteTitleVector(id);
                return n > 0;
            } catch (SQLException e) {
                log.warn("[KB-BLOCK] 删除失败：{}", e.getMessage());
                return false;
            }
        }
    }

    /** 清空全部块与向量（"首次导入前清空语料"用） */
    public void clearAll() {
        if (!available) {
            return;
        }
        synchronized (this) {
            try (Statement st = conn().createStatement()) {
                st.executeUpdate("DELETE FROM kb_block");
                st.executeUpdate("DELETE FROM kb_block_vec");
                st.executeUpdate("DELETE FROM kb_block_title_vec");
                log.warn("[KB-BLOCK] 已清空全部块、正文向量与标题向量");
            } catch (SQLException e) {
                log.warn("[KB-BLOCK] 清空失败：{}", e.getMessage());
            }
        }
    }
}
