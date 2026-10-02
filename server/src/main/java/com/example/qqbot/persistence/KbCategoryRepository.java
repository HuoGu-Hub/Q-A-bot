package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分类映射（{@code raw -> 大类 + 中文标签}）的**数据访问**。
 *
 * <h2>两条 upsert 不能合并 —— 这是一个静默数据丢失的坑</h2>
 * <ul>
 *   <li>{@link #upsert}：会把 {@code label_zh} 一起覆盖成传进来的值。</li>
 *   <li>{@link #upsertGroupOnly}：**冲突时不写 label_zh**，已有中文标签原样留着。</li>
 * </ul>
 * 批量改归属时前端只知道大类（中文标签是逐条人工填的），
 * 若复用第一条 SQL，就等于把选中的几十条中文标签**一次性清空** ——
 * 而且看不出来：列表上没标签的行本来也长这样。
 */
@Repository
public class KbCategoryRepository {

    /** 一行映射 */
    public record Row(String raw, String groupKey, String labelZh) {
    }

    private static final String UPSERT =
            "INSERT INTO kb_category (raw, group_key, label_zh, updated_at) VALUES (?,?,?,?) "
                    + "ON CONFLICT(raw) DO UPDATE SET "
                    + "group_key = excluded.group_key, "
                    + "label_zh = excluded.label_zh, "
                    + "updated_at = excluded.updated_at";

    /** ⚠️ 冲突时**不动 label_zh** —— 见类注释 */
    private static final String UPSERT_GROUP_ONLY =
            "INSERT INTO kb_category (raw, group_key, label_zh, updated_at) VALUES (?,?,?,?) "
                    + "ON CONFLICT(raw) DO UPDATE SET "
                    + "group_key = excluded.group_key, "
                    + "updated_at = excluded.updated_at";

    private final Jdbc jdbc;

    public KbCategoryRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_category ("
                + " raw        TEXT PRIMARY KEY,"
                + " group_key  TEXT NOT NULL DEFAULT '',"
                + " label_zh   TEXT NOT NULL DEFAULT '',"
                + " updated_at TEXT NOT NULL DEFAULT '')");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_cat_group ON kb_category(group_key)");
    }

    public List<Row> all() {
        return jdbc.query("SELECT raw, group_key, label_zh FROM kb_category",
                r -> new Row(r.str("raw"), r.str("group_key"), r.str("label_zh")));
    }

    public void upsert(String raw, String groupKey, String labelZh, String updatedAt) {
        jdbc.update(UPSERT, raw, groupKey, labelZh == null ? "" : labelZh, updatedAt);
    }

    /** 批量只改大类、**保留已有中文标签**（一个事务） */
    public int upsertGroupOnly(List<String> raws, String groupKey, String updatedAt) {
        List<Object[]> rows = raws.stream()
                .map(raw -> new Object[]{raw, groupKey, "", updatedAt})
                .toList();
        return jdbc.batch(UPSERT_GROUP_ONLY, rows);
    }

    /** 批量 upsert（一个事务） */
    public int upsertBatch(List<Row> items, String updatedAt) {
        List<Object[]> rows = items.stream()
                .map(m -> new Object[]{m.raw(), m.groupKey(), m.labelZh() == null ? "" : m.labelZh(), updatedAt})
                .toList();
        return jdbc.batch(UPSERT, rows);
    }

    public boolean delete(String raw) {
        return jdbc.update("DELETE FROM kb_category WHERE raw = ?", raw) > 0;
    }

    public int clearAll() {
        return jdbc.update("DELETE FROM kb_category");
    }

    public long count() {
        return jdbc.count("SELECT COUNT(*) FROM kb_category");
    }
}
