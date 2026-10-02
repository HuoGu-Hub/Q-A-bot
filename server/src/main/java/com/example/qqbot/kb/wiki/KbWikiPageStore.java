package com.example.qqbot.kb.wiki;

import com.example.qqbot.persistence.SqliteConnectionProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

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
 * wiki 文章的同步状态 —— **增量导入**的判据。
 *
 * <h2>为什么必须有这张表</h2>
 * 任务 + 机制合计约 170 篇。每次都全量重新清洗、重新向量化，
 * 既慢又白花钱（embedding 是按量计费的）。存下每篇的 `revid`，
 * 版本没变就一个字节都不动 —— 和地图同步是同一个思路。
 *
 * <p>同时记 `block_id`：这样"这篇文章对应哪个块"是可查的，
 * 页面在 wiki 上被删掉时也能把对应块一起清掉。
 */
@Component
@DependsOn("qaStore")
public class KbWikiPageStore {

    private static final Logger log = LoggerFactory.getLogger(KbWikiPageStore.class);

    private final SqliteConnectionProvider db;
    private volatile boolean available;

    public KbWikiPageStore(SqliteConnectionProvider db) {
        this.db = db;
    }

    /** 一篇页面的同步状态 */
    public record PageState(String page, String source, long revid, String pageUpdatedAt,
                            String syncedAt, int chars, String blockId, String error) {
    }

    @PostConstruct
    public void init() {
        if (!db.isAvailable()) {
            log.warn("[KB-WIKI] 问答库不可用，wiki 文章状态存储一并关闭");
            available = false;
            return;
        }
        try (Statement st = conn().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS kb_wiki_page ("
                    + " page            TEXT PRIMARY KEY,"
                    + " source          TEXT NOT NULL DEFAULT '',"
                    + " revid           INTEGER NOT NULL DEFAULT 0,"
                    + " page_updated_at TEXT NOT NULL DEFAULT '',"
                    + " synced_at       TEXT NOT NULL DEFAULT '',"
                    + " chars           INTEGER NOT NULL DEFAULT 0,"
                    + " block_id        TEXT NOT NULL DEFAULT '',"
                    + " error           TEXT NOT NULL DEFAULT '')");
            st.execute("CREATE INDEX IF NOT EXISTS idx_wiki_page_source ON kb_wiki_page(source)");
            available = true;
            log.info("[KB-WIKI] 文章状态就绪：{} 篇", count());
        } catch (Exception e) {
            log.warn("[KB-WIKI] 初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** 已知的 page -> revid */
    public Map<String, Long> knownRevisions() {
        Map<String, Long> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT page, revid FROM kb_wiki_page")) {
            while (rs.next()) {
                out.put(rs.getString("page"), rs.getLong("revid"));
            }
        } catch (SQLException e) {
            log.warn("[KB-WIKI] 读同步状态失败：{}", e.getMessage());
        }
        return out;
    }

    public List<PageState> pages() {
        List<PageState> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM kb_wiki_page ORDER BY source, page")) {
            while (rs.next()) {
                out.add(new PageState(rs.getString("page"), rs.getString("source"), rs.getLong("revid"),
                        rs.getString("page_updated_at"), rs.getString("synced_at"), rs.getInt("chars"),
                        rs.getString("block_id"), rs.getString("error")));
            }
        } catch (SQLException e) {
            log.warn("[KB-WIKI] 读页面列表失败：{}", e.getMessage());
        }
        return out;
    }

    public void upsert(String page, String source, long revid, String pageUpdatedAt, int chars, String blockId) {
        if (!available) {
            return;
        }
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO kb_wiki_page (page, source, revid, page_updated_at, synced_at, chars, block_id, error)"
                        + " VALUES (?,?,?,?,?,?,?,'')"
                        + " ON CONFLICT(page) DO UPDATE SET source=excluded.source, revid=excluded.revid,"
                        + " page_updated_at=excluded.page_updated_at, synced_at=excluded.synced_at,"
                        + " chars=excluded.chars, block_id=excluded.block_id, error=''")) {
            ps.setString(1, page);
            ps.setString(2, source);
            ps.setLong(3, revid);
            ps.setString(4, pageUpdatedAt == null ? "" : pageUpdatedAt);
            ps.setString(5, Instant.now().toString());
            ps.setInt(6, chars);
            ps.setString(7, blockId == null ? "" : blockId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[KB-WIKI] 写入页面状态失败 {}：{}", page, e.getMessage());
        }
    }

    /**
     * 记一次失败。
     *
     * <p><b>不动 revid</b> —— 动了会让下一次导入误以为"这页已经处理过"，把它永久跳过。
     * 这与地图同步是同一条教训。
     */
    public void markError(String page, String source, long revid, String message) {
        if (!available) {
            return;
        }
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO kb_wiki_page (page, source, revid, synced_at, chars, block_id, error)"
                        + " VALUES (?,?,?,?,0,'',?)"
                        + " ON CONFLICT(page) DO UPDATE SET error=excluded.error, synced_at=excluded.synced_at")) {
            ps.setString(1, page);
            ps.setString(2, source);
            ps.setLong(3, revid);
            ps.setString(4, Instant.now().toString());
            ps.setString(5, message == null ? "" : message.substring(0, Math.min(300, message.length())));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[KB-WIKI] 记录错误失败 {}：{}", page, e.getMessage());
        }
    }

    /** 某个来源下的全部页面（用于按来源回滚） */
    public List<String> pagesOfSource(String source) {
        List<String> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        try (PreparedStatement ps = conn().prepareStatement("SELECT page FROM kb_wiki_page WHERE source = ?")) {
            ps.setString(1, source);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString("page"));
                }
            }
        } catch (SQLException e) {
            log.warn("[KB-WIKI] 读来源页面失败：{}", e.getMessage());
        }
        return out;
    }

    public int count() {
        if (!available) {
            return 0;
        }
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM kb_wiki_page")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private Connection conn() {
        return db.connection();
    }
}
