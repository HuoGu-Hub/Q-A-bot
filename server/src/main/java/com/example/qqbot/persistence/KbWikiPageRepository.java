package com.example.qqbot.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(KbWikiPageRepository.class);

    /**
     * 一行页面状态。
     *
     * <p>{@code blockCount} 是 2026-10-02 加的：超长正文改为**切块**之后，一页对应多块
     * （id 约定 {@code <blockId>}、{@code <blockId>-2}、{@code <blockId>-3}…）。
     * 没有它，页面在 wiki 上被**改短**时，上一次多切出来的块会变成**永久孤儿块** ——
     * 库里留着、检索还搜得到、但没有任何东西记得它属于谁。
     */
    public record Page(String page, String source, long revid, String pageUpdatedAt,
                       String syncedAt, int chars, String blockId, int blockCount, String error) {
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
                + " block_count     INTEGER NOT NULL DEFAULT 0,"
                + " error           TEXT NOT NULL DEFAULT '')");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_wiki_page_source ON kb_wiki_page(source)");
        // 已经建过库的机器要补列 —— 少了它，升级后会一直报 no such column: block_count
        addColumnIfMissing("block_count", "INTEGER NOT NULL DEFAULT 0");
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
                        r.str("block_id"), r.intOf("block_count"), r.str("error")));
    }

    public void upsert(String page, String source, long revid, String pageUpdatedAt,
                       String syncedAt, int chars, String blockId, int blockCount) {
        jdbc.update("INSERT INTO kb_wiki_page (page, source, revid, page_updated_at, synced_at, chars, block_id, block_count, error)"
                + " VALUES (?,?,?,?,?,?,?,?,'')"
                + " ON CONFLICT(page) DO UPDATE SET source=excluded.source, revid=excluded.revid,"
                + " page_updated_at=excluded.page_updated_at, synced_at=excluded.synced_at,"
                + " chars=excluded.chars, block_id=excluded.block_id, block_count=excluded.block_count, error=''",
                page, source, revid, pageUpdatedAt, syncedAt, chars, blockId, blockCount);
    }

    /** 这页上一次切了几块（用于清掉本次多出来的）。没记过返回 0 */
    public int blockCountOf(String page) {
        Integer n = jdbc.queryOne("SELECT block_count FROM kb_wiki_page WHERE page = ?",
                r -> r.intOf("block_count"), page);
        return n == null ? 0 : n;
    }

    /** 给已存在的表补列 —— SQLite 没有 ADD COLUMN IF NOT EXISTS，见类注释 */
    private void addColumnIfMissing(String column, String ddl) {
        boolean exists = jdbc.query("PRAGMA table_info(kb_wiki_page)", r -> r.str("name")).stream()
                .anyMatch(n -> column.equalsIgnoreCase(n));
        if (exists) {
            return;
        }
        jdbc.execute("ALTER TABLE kb_wiki_page ADD COLUMN " + column + " " + ddl);
        log.info("[KB-WIKI] kb_wiki_page 补列：{} {}", column, ddl);
    }

    /**
     * 记一次失败。**revid 恒写 0，且不接受调用方传** —— 见 {@link KbWikiPageStore#markError}：
     * 这行的 revid 是增量判据，写了真 revid 会让这页被永久跳过。
     */
    public void markError(String page, String source, String syncedAt, String message) {
        jdbc.update("INSERT INTO kb_wiki_page (page, source, revid, synced_at, chars, block_id, error)"
                + " VALUES (?,?,0,?,0,'',?)"
                + " ON CONFLICT(page) DO UPDATE SET error=excluded.error, synced_at=excluded.synced_at",
                page, source, syncedAt, message);
    }

    /**
     * 上次**没导成功**的页（error 非空）。
     *
     * <p>为什么它必须参与增量判据：这类页的 revid 不可信 —— 老版本曾把真 revid 写进失败行
     * （见 {@link #markError}），于是"revid 相等就跳过"会把这页永久跳过。
     * 判据里带上它，历史坏行也能自愈。
     */
    public List<String> pagesWithError() {
        return jdbc.query("SELECT page FROM kb_wiki_page WHERE COALESCE(error,'') <> ''", r -> r.str("page"));
    }

    public List<String> pagesOfSource(String source) {
        return jdbc.query("SELECT page FROM kb_wiki_page WHERE source = ?", r -> r.str("page"), source);
    }

    public int count() {
        return (int) jdbc.count("SELECT COUNT(*) FROM kb_wiki_page");
    }

    /**
     * 清空全部状态行 —— **回滚时必须连它一起清**。
     *
     * <p>不清的话 {@code revid} 还记着，再跑一次导入会一篇都不处理
     * （判据就是"revid 变过"）—— 于是 purge 变成一扇**单向门**：
     * 撤掉之后再也导不回来。2026-10-02 修。
     */
    public int deleteAll() {
        return jdbc.update("DELETE FROM kb_wiki_page");
    }
}
