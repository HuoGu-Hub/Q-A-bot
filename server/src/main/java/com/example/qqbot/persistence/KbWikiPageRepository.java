package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * wiki 文章同步状态的**数据访问** —— SQL 只在这里。
 *
 * <p>业务类 {@code kb.wiki.KbWikiPageStore} 现在只管"增量判据"的语义，
 * **怎么存**归这里。
 */
@Repository
public class KbWikiPageRepository {

    /** 一行页面状态 */
    public record Page(String page, String source, long revid, String pageUpdatedAt,
                       String syncedAt, int chars, String blockId, String error) {
    }

    private final Jdbc jdbc;

    public KbWikiPageRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_wiki_page ("
                + " page            TEXT PRIMARY KEY,"
                + " source          TEXT NOT NULL DEFAULT '',"
                + " revid           INTEGER NOT NULL DEFAULT 0,"
                + " page_updated_at TEXT NOT NULL DEFAULT '',"
                + " synced_at       TEXT NOT NULL DEFAULT '',"
                + " chars           INTEGER NOT NULL DEFAULT 0,"
                + " block_id        TEXT NOT NULL DEFAULT '',"
                + " error           TEXT NOT NULL DEFAULT '')");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_wiki_page_source ON kb_wiki_page(source)");
    }

    /** page -> revid */
    public Map<String, Long> knownRevisions() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : jdbc.query("SELECT page, revid FROM kb_wiki_page",
                r -> Map.entry(r.str("page"), r.longOf("revid")))) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    public List<Page> pages() {
        return jdbc.query("SELECT * FROM kb_wiki_page ORDER BY source, page",
                r -> new Page(r.str("page"), r.str("source"), r.longOf("revid"),
                        r.str("page_updated_at"), r.str("synced_at"), r.intOf("chars"),
                        r.str("block_id"), r.str("error")));
    }

    public void upsert(String page, String source, long revid, String pageUpdatedAt,
                       String syncedAt, int chars, String blockId) {
        jdbc.update("INSERT INTO kb_wiki_page (page, source, revid, page_updated_at, synced_at, chars, block_id, error)"
                + " VALUES (?,?,?,?,?,?,?,'')"
                + " ON CONFLICT(page) DO UPDATE SET source=excluded.source, revid=excluded.revid,"
                + " page_updated_at=excluded.page_updated_at, synced_at=excluded.synced_at,"
                + " chars=excluded.chars, block_id=excluded.block_id, error=''",
                page, source, revid, pageUpdatedAt, syncedAt, chars, blockId);
    }

    /**
     * 记一次失败。**不动 revid** —— 动了会让下一次导入误以为"这页已经处理过"而永久跳过它。
     */
    public void markError(String page, String source, long revid, String syncedAt, String message) {
        jdbc.update("INSERT INTO kb_wiki_page (page, source, revid, synced_at, chars, block_id, error)"
                + " VALUES (?,?,?,?,0,'',?)"
                + " ON CONFLICT(page) DO UPDATE SET error=excluded.error, synced_at=excluded.synced_at",
                page, source, revid, syncedAt, message);
    }

    public List<String> pagesOfSource(String source) {
        return jdbc.query("SELECT page FROM kb_wiki_page WHERE source = ?", r -> r.str("page"), source);
    }

    public int count() {
        return (int) jdbc.count("SELECT COUNT(*) FROM kb_wiki_page");
    }
}
