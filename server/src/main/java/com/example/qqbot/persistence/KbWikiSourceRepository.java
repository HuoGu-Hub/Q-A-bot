package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * wiki 拉取来源的**数据访问** —— SQL 只在这里。
 *
 * <h2>为什么要有这张表（2026-10-13）</h2>
 * 范围原先写在 {@code application.yml} 的 {@code app.kb.wiki-import.categories} 里 ——
 * 那是**启动期配置**，运行时改不了，所以只能改文件 + 跑命令行。
 * 要在管理端页面上勾选范围，范围就必须是可写的数据，而不是配置文件。
 *
 * <p>于是：yml 里的列表退化成**默认值**（表为空时用它），表里的内容才是运维选出来的范围。
 * 这样"勾选 → 保存 → 拉取"是一条闭环，不需要任何人去碰文件。
 */
@Repository
public class KbWikiSourceRepository {

    /** 一行来源。{@code kind} 只有 {@code category} 与 {@code prefix} 两种 */
    public record Source(String name, String kind, boolean enabled, String updatedAt) {
    }

    private final Jdbc jdbc;

    public KbWikiSourceRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_wiki_source ("
                + " name       TEXT PRIMARY KEY,"
                + " kind       TEXT NOT NULL DEFAULT 'category',"
                + " enabled    INTEGER NOT NULL DEFAULT 1,"
                + " updated_at TEXT NOT NULL DEFAULT '')");
    }

    public List<Source> list() {
        return jdbc.query("SELECT name, kind, enabled, updated_at FROM kb_wiki_source ORDER BY kind, name",
                r -> new Source(r.str("name"), r.str("kind"), r.intOf("enabled") != 0, r.str("updated_at")));
    }

    public int count() {
        return jdbc.query("SELECT COUNT(*) AS n FROM kb_wiki_source", r -> r.intOf("n")).stream()
                .findFirst().orElse(0);
    }

    /**
     * 整表替换。
     *
     * <p>为什么是"替换"而不是逐条增删：页面上勾选的结果就是**完整范围**，
     * 逐条同步反而要处理"哪条被删了"的一致性。替换语义最简单，也不会留下幽灵来源。
     */
    public void replace(List<Source> items) {
        jdbc.execute("DELETE FROM kb_wiki_source");
        List<Object[]> batch = new ArrayList<>();
        for (Source s : items) {
            batch.add(new Object[]{s.name(), s.kind(), s.enabled() ? 1 : 0, s.updatedAt()});
        }
        jdbc.batch("INSERT INTO kb_wiki_source (name, kind, enabled, updated_at) VALUES (?,?,?,?)", batch);
    }
}
