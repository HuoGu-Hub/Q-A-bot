package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分类目录（{@code kb_category}）的**数据访问**。
 *
 * <h2>改造后：分类不再是"原始标签 → 9 大类"的映射</h2>
 * 原来这张表存的是 {@code raw -> group_key + label_zh}（智能归类的**手动覆盖**）。
 * 现在分类就是一份**人工维护的清单**：分块符号里带什么标签，词条就归到什么分类，
 * 标签不在清单里就归「未分类」。所以只有 {@code name / label / sort} 三列。
 *
 * <p>旧结构的表在 {@link #initSchema()} 里被识别并丢弃 —— 那些映射正是
 * "智能分类"的产物，重构后不再需要；留着只会让人以为它还在起作用。
 */
@Repository
public class KbCategoryRepository {

    /** 一个分类：名字（= 分块符号里的标签值）与显示名 */
    public record Row(String name, String label, int sort) {
    }

    private final Jdbc jdbc;

    public KbCategoryRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        // 旧表结构与新表不同名同义：检测到旧列就整体丢弃重建。
        // 这是**有意的数据删除** —— 旧数据是智能分类的手动覆盖，重构后无意义。
        if (hasColumn("kb_category", "raw") && !hasColumn("kb_category", "name")) {
            jdbc.execute("DROP TABLE kb_category");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_category ("
                + " name       TEXT PRIMARY KEY,"
                + " label      TEXT NOT NULL DEFAULT '',"
                + " sort       INTEGER NOT NULL DEFAULT 0,"
                + " created_at TEXT NOT NULL DEFAULT '')");
    }

    /** 表里有没有这一列 —— 迁移用（SQLite 没有 IF NOT EXISTS 的 ADD COLUMN） */
    private boolean hasColumn(String table, String column) {
        try {
            return jdbc.query("PRAGMA table_info(" + table + ")", r -> r.str("name"))
                    .stream().anyMatch(column::equalsIgnoreCase);
        } catch (Exception e) {
            return false;
        }
    }

    public List<Row> all() {
        return jdbc.query("SELECT name, label, sort FROM kb_category ORDER BY sort, name",
                r -> new Row(r.str("name"), r.str("label"), r.intOf("sort")));
    }

    public Row find(String name) {
        return jdbc.queryOne("SELECT name, label, sort FROM kb_category WHERE name = ?",
                r -> new Row(r.str("name"), r.str("label"), r.intOf("sort")), name);
    }

    /** 新增或更新一个分类（label 覆盖） */
    public void upsert(String name, String label, int sort, String now) {
        jdbc.update("INSERT INTO kb_category (name, label, sort, created_at) VALUES (?,?,?,?) "
                        + "ON CONFLICT(name) DO UPDATE SET label = excluded.label, sort = excluded.sort",
                name, label == null ? "" : label, sort, now);
    }

    public boolean delete(String name) {
        return jdbc.update("DELETE FROM kb_category WHERE name = ?", name) > 0;
    }

    public long count() {
        return jdbc.count("SELECT COUNT(*) FROM kb_category");
    }
}
