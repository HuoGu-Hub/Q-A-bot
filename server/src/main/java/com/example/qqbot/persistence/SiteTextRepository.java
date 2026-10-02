package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 公开站固定文案的**数据访问** —— SQL 只在这里。
 *
 * <p>业务类 {@code site.SiteTextService} 现在只管"哪些块可编辑、默认值是什么、
 * 什么该发给公开站"；**怎么存**归这里。
 *
 * <p>它只返回行，不做业务判断（比如"这个 key 在不在注册表里"是业务规则，
 * 留在 Service 里）。
 */
@Repository
public class SiteTextRepository {

    /** 一行覆盖文案 */
    public record Override(String page, String key, String text) {
    }

    private final Jdbc jdbc;

    public SiteTextRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS site_text ("
                + " page_key   TEXT NOT NULL,"
                + " block_key  TEXT NOT NULL,"
                + " text       TEXT NOT NULL,"
                + " updated_at TEXT NOT NULL,"
                + " PRIMARY KEY (page_key, block_key))");
    }

    public List<Override> all() {
        return jdbc.query("SELECT page_key, block_key, text FROM site_text",
                r -> new Override(r.str("page_key"), r.str("block_key"), r.str("text")));
    }

    public void save(String page, String key, String text, String updatedAt) {
        jdbc.update("INSERT INTO site_text (page_key, block_key, text, updated_at) VALUES (?,?,?,?)"
                + " ON CONFLICT(page_key, block_key) DO UPDATE SET text=excluded.text,"
                + " updated_at=excluded.updated_at", page, key, text, updatedAt);
    }

    public void delete(String page, String key) {
        jdbc.update("DELETE FROM site_text WHERE page_key = ? AND block_key = ?", page, key);
    }
}
