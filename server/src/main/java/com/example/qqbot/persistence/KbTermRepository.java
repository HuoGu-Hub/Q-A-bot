package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 词条表（{@code kb_term}）的**数据访问** —— 知识库「名字层」的唯一真源。
 *
 * <h2>为什么 status 的取值域定义在这里</h2>
 * {@code draft / verified / rejected} 是 {@code status} 这一列的取值域，
 * 而「认不出来的值一律归 draft」是这一列的**读时约束**。
 * {@link #setStatusBatch} 要在**同一个事务里**拿旧值跟目标值比较，
 * 比较用的必须是归一化后的值 —— 所以归一化不能留在业务侧（那样 Repository 就得反向依赖它）。
 * 把这份词表放在列旁边、由 {@code KbTermStore} 转发一次，就只有一份定义。
 *
 * <h2>三处「不能顺手改掉」的地方</h2>
 * <ul>
 *   <li>{@link #insertMissing} 用 {@code INSERT OR IGNORE}：已存在的行**一个字都不动**。
 *       中文名和核对状态是人工成果，被语料重建冲掉就是真丢数据。</li>
 *   <li>{@link #insertMissing} 与 {@link #upsertBatch} 是**两条** SQL，不能合并成一条 ——
 *       前者要保留旧值，后者要覆盖。</li>
 *   <li>{@link #setStatusBatch} 是读-改-写交替（先看旧状态再决定要不要写），
 *       必须整体原子：一半生效的核对结果谁也说不清哪些算数。</li>
 * </ul>
 */
@Repository
public class KbTermRepository {

    /** 合法状态 —— {@code status} 列的取值域 */
    public static final List<String> STATUSES = List.of("draft", "verified", "rejected");

    /** 空值或认不出来的值一律算 draft —— 和旧 GlossaryStore 同一口径 */
    public static String normalizeStatus(String s) {
        if (s == null || s.isBlank()) {
            return "draft";
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        return STATUSES.contains(v) ? v : "draft";
    }

    /** 一行词条。{@code zh} 空串 = 还没起中文名 */
    public record Row(String en, String zh, String status) {
    }

    /** 批量改状态的结果：真改了几条 / 本来就是目标状态几条 / 没对上的英文名 */
    public record StatusOutcome(int updated, int unchanged, List<String> missing) {
    }

    private static final String UPSERT =
            "INSERT INTO kb_term (en, zh, status, updated_at) VALUES (?,?,?,?) "
                    + "ON CONFLICT(en) DO UPDATE SET "
                    + "zh = excluded.zh, status = excluded.status, updated_at = excluded.updated_at";

    /** ⚠️ 冲突时**什么都不改** —— 见类注释 */
    private static final String INSERT_OR_IGNORE =
            "INSERT OR IGNORE INTO kb_term (en, zh, status, updated_at) VALUES (?,?,?,?)";

    private final Jdbc jdbc;

    public KbTermRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        // 用普通字符串而不是文本块 —— 与 KbCategoryRepository 保持一致，文本块的结束符
        // 必须独占一行，生成代码时极易写错
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_term ("
                + " en         TEXT PRIMARY KEY,"
                + " zh         TEXT NOT NULL DEFAULT '',"
                + " status     TEXT NOT NULL DEFAULT 'draft',"
                + " updated_at TEXT NOT NULL DEFAULT '')");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_term_status ON kb_term(status)");
    }

    public List<Row> all() {
        return jdbc.query("SELECT en, zh, status FROM kb_term",
                r -> new Row(r.str("en"), nz(r.str("zh")), r.str("status")));
    }

    public Row find(String en) {
        return jdbc.queryOne("SELECT en, zh, status FROM kb_term WHERE en = ?",
                r -> new Row(r.str("en"), nz(r.str("zh")), r.str("status")), en);
    }

    public long count() {
        return jdbc.count("SELECT COUNT(*) FROM kb_term");
    }

    /**
     * 缺的才插入（一个事务）。
     *
     * @return **提交的行数**（含被 {@code OR IGNORE} 跳过的那些）；
     *         真正新增了几条要由调用方比较前后的 {@link #count()} —— 这正是
     *         {@code INSERT OR IGNORE} 的代价，换来的是「人工成果不被冲掉」。
     */
    public int insertMissing(List<Row> rows, String updatedAt) {
        return jdbc.batch(INSERT_OR_IGNORE, args(rows, updatedAt));
    }

    /** 新增或覆盖（一个事务） */
    public int upsertBatch(List<Row> rows, String updatedAt) {
        return jdbc.batch(UPSERT, args(rows, updatedAt));
    }

    /** 新增或覆盖一条 */
    public void upsert(String en, String zh, String status, String updatedAt) {
        jdbc.update(UPSERT, en, nz(zh), normalizeStatus(status), updatedAt);
    }

    /** 只改状态；不存在返回 false */
    public boolean updateStatus(String en, String status, String updatedAt) {
        return jdbc.update("UPDATE kb_term SET status = ?, updated_at = ? WHERE en = ?",
                normalizeStatus(status), updatedAt, en) > 0;
    }

    /**
     * 批量改状态 —— 读-改-写交替，**一个事务**（见 {@link Jdbc#transaction}）。
     *
     * <p>为什么不能拆成「先查一批、再写一批」两步：两步之间另一个管理员可以插进来改同一条，
     * 于是「本来就对 / 真改了」的统计与实际写入对不上，而且没人在提交前能发现。
     *
     * <p>已经是目标状态的行**跳过不写**：写进去结果一样，却会让「改了几条」失真。
     *
     * <p>任何一步失败 → 整个事务回滚 → 由调用方降级成「什么都没改」。
     */
    public StatusOutcome setStatusBatch(List<String> ens, String wanted, String updatedAt) {
        String target = normalizeStatus(wanted);
        return jdbc.transaction(() -> {
            int updated = 0;
            int unchanged = 0;
            List<String> missing = new ArrayList<>();
            for (String en : ens) {
                if (en == null || en.isBlank()) {
                    continue;
                }
                Row cur = find(en);
                if (cur == null) {
                    missing.add(en);
                } else if (normalizeStatus(cur.status()).equals(target)) {
                    unchanged++;
                } else {
                    updateStatus(en, target, updatedAt);
                    updated++;
                }
            }
            return new StatusOutcome(updated, unchanged, missing);
        });
    }

    /** 清掉中文名并把状态打回 draft（**不是删行**） */
    public boolean clearName(String en, String updatedAt) {
        return jdbc.update("UPDATE kb_term SET zh = '', status = 'draft', updated_at = ? WHERE en = ?",
                updatedAt, en) > 0;
    }

    /** 按英文名批量删（一个事务） */
    public int deleteByEn(List<String> ens) {
        List<Object[]> rows = ens.stream().map(en -> new Object[]{en}).toList();
        return jdbc.batch("DELETE FROM kb_term WHERE en = ?", rows);
    }

    /** 清空整表（**不可逆**） */
    public int deleteAll() {
        return jdbc.update("DELETE FROM kb_term");
    }

    private static List<Object[]> args(List<Row> rows, String updatedAt) {
        List<Object[]> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            out.add(new Object[]{r.en(), nz(r.zh()), normalizeStatus(r.status()), updatedAt});
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
