package com.example.qqbot.kb.map;

import com.example.qqbot.qa.QaStore;
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
 * 地图数据存储 —— wiki 的 marker 落库，供"未来地图同步"与问答共用。
 *
 * <h2>三张表，各管一件事</h2>
 * <ul>
 *   <li>{@code kb_map_page} —— **增量同步的状态**（每页的 revid）。没有它，
 *       每次同步都得把 32 页全拉一遍；有了它只拉变过的。</li>
 *   <li>{@code kb_map_group} —— marker 分组定义（显示名 / 图标 / 是否收集品）。</li>
 *   <li>{@code kb_map_marker} —— marker 本身。{@code article} 是通往知识库的外键。</li>
 * </ul>
 *
 * <h2>为什么主键是 (map, page, marker_id) 而不是 (map, marker_id)</h2>
 * 实测同一张地图的不同页会**重复定义同一批 marker**
 * （{@code Map:Embervale/Collectibles} 与 {@code Collectibles2} 是同一份数据的两个版本）。
 * 用 (map, marker_id) 做主键会让后同步的那页**静默覆盖**前一页。
 * 带上 page 就不会丢数据；问答侧按 article/名字聚合时再自行去重。
 *
 * <h2>为什么复用问答库的连接</h2>
 * 与 {@code KbBlockStore} / {@code KbTermStore} 一致：两个连接开同一个 SQLite 文件
 * 会在 WAL 共享内存上冲突（实测 {@code SQLITE_IOERR_SHMOPEN}）。这是全项目**唯一**
 * 被允许共享连接的地方，见 ArchUnit 的目标规则 R5。
 */
@Component
@DependsOn("qaStore")
public class KbMapStore {

    private static final Logger log = LoggerFactory.getLogger(KbMapStore.class);

    private final QaStore qaStore;
    private volatile boolean available;

    public KbMapStore(QaStore qaStore) {
        this.qaStore = qaStore;
    }

    /** 某一页上一次同步到的版本 */
    public record PageState(String page, String map, long revid, String pageUpdatedAt,
                            String syncedAt, int markerCount, String parseError) {
    }

    /** 一个 marker（落库/读出一致） */
    public record Marker(String map, String page, String group, String markerId, String name,
                         String description, String article, double x, double y, String image) {
    }

    @PostConstruct
    public void init() {
        if (!qaStore.isAvailable()) {
            log.warn("[KB-MAP] 问答库不可用，地图存储一并关闭");
            available = false;
            return;
        }
        try (Statement st = conn().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS kb_map_page ("
                    + " page            TEXT PRIMARY KEY,"
                    + " map             TEXT NOT NULL DEFAULT '',"
                    + " revid           INTEGER NOT NULL DEFAULT 0,"
                    + " page_updated_at TEXT NOT NULL DEFAULT '',"
                    + " synced_at       TEXT NOT NULL DEFAULT '',"
                    + " marker_count    INTEGER NOT NULL DEFAULT 0,"
                    + " parse_error     TEXT NOT NULL DEFAULT '')");

            st.execute("CREATE TABLE IF NOT EXISTS kb_map_group ("
                    + " map         TEXT NOT NULL,"
                    + " key         TEXT NOT NULL,"
                    + " name        TEXT NOT NULL DEFAULT '',"
                    + " icon        TEXT NOT NULL DEFAULT '',"
                    + " collectible INTEGER NOT NULL DEFAULT 0,"
                    + " PRIMARY KEY (map, key))");

            st.execute("CREATE TABLE IF NOT EXISTS kb_map_marker ("
                    + " map         TEXT NOT NULL,"
                    + " page        TEXT NOT NULL,"
                    + " marker_id   TEXT NOT NULL,"
                    + " grp         TEXT NOT NULL DEFAULT '',"
                    + " name        TEXT NOT NULL DEFAULT '',"
                    + " description TEXT NOT NULL DEFAULT '',"
                    + " article     TEXT NOT NULL DEFAULT '',"
                    + " x           REAL NOT NULL DEFAULT 0,"
                    + " y           REAL NOT NULL DEFAULT 0,"
                    + " image       TEXT NOT NULL DEFAULT '',"
                    + " PRIMARY KEY (map, page, marker_id))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_map_marker_article ON kb_map_marker(article)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_map_marker_grp ON kb_map_marker(map, grp)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_map_marker_name ON kb_map_marker(name)");
            available = true;
            log.info("[KB-MAP] 地图存储就绪：{} 页 / {} 个 marker / {} 个分组",
                    pageCount(), markerCount(), groupCount());
        } catch (Exception e) {
            log.warn("[KB-MAP] 初始化失败，地图数据不可用：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** 每页上次同步的 revid —— 增量同步的判据 */
    public Map<String, Long> knownRevisions() {
        Map<String, Long> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT page, revid FROM kb_map_page")) {
            while (rs.next()) {
                out.put(rs.getString("page"), rs.getLong("revid"));
            }
        } catch (SQLException e) {
            log.warn("[KB-MAP] 读同步状态失败：{}", e.getMessage());
        }
        return out;
    }

    /** 一页的同步状态（含出错原因），供管理端排查 */
    public List<PageState> pages() {
        List<PageState> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM kb_map_page ORDER BY map, page")) {
            while (rs.next()) {
                out.add(new PageState(rs.getString("page"), rs.getString("map"), rs.getLong("revid"),
                        rs.getString("page_updated_at"), rs.getString("synced_at"),
                        rs.getInt("marker_count"), rs.getString("parse_error")));
            }
        } catch (SQLException e) {
            log.warn("[KB-MAP] 读页面列表失败：{}", e.getMessage());
        }
        return out;
    }

    /**
     * 写入一页的解析结果。**先删该页旧 marker 再插新的** —— 这样 wiki 上删掉的 marker
     * 在本地也会消失（幂等 + 能表达删除）。整页在一个事务里做，中途失败不会留半个页面。
     */
    public int replacePage(String page, long revid, String pageUpdatedAt, KbMapParser.Meta meta,
                           List<KbMapParser.Group> groups, List<KbMapParser.Marker> markers, String syncedAt) {
        if (!available) {
            return 0;
        }
        Connection c = conn();
        synchronized (c) {
            boolean auto;
            try {
                auto = c.getAutoCommit();
                c.setAutoCommit(false);
            } catch (SQLException e) {
                log.warn("[KB-MAP] 开启事务失败：{}", e.getMessage());
                return 0;
            }
            try {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM kb_map_marker WHERE page = ?")) {
                    ps.setString(1, page);
                    ps.executeUpdate();
                }
                String map = meta == null ? KbMapParser.mapOf(page) : meta.map();

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO kb_map_group (map, key, name, icon, collectible) VALUES (?,?,?,?,?) "
                                + "ON CONFLICT(map, key) DO UPDATE SET name=excluded.name,"
                                + " icon=excluded.icon, collectible=excluded.collectible")) {
                    for (KbMapParser.Group g : groups) {
                        ps.setString(1, map);
                        ps.setString(2, g.key());
                        ps.setString(3, g.name() == null ? "" : g.name());
                        ps.setString(4, g.icon() == null ? "" : g.icon());
                        ps.setInt(5, g.collectible() ? 1 : 0);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO kb_map_marker"
                                + " (map, page, marker_id, grp, name, description, article, x, y, image)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    for (KbMapParser.Marker mk : markers) {
                        ps.setString(1, mk.map());
                        ps.setString(2, page);
                        ps.setString(3, mk.markerId());
                        ps.setString(4, mk.group() == null ? "" : mk.group());
                        ps.setString(5, mk.name() == null ? "" : mk.name());
                        ps.setString(6, mk.description() == null ? "" : mk.description());
                        ps.setString(7, mk.article() == null ? "" : mk.article());
                        ps.setDouble(8, mk.x());
                        ps.setDouble(9, mk.y());
                        ps.setString(10, mk.image() == null ? "" : mk.image());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO kb_map_page (page, map, revid, page_updated_at, synced_at, marker_count, parse_error)"
                                + " VALUES (?,?,?,?,?,?,'')"
                                + " ON CONFLICT(page) DO UPDATE SET map=excluded.map, revid=excluded.revid,"
                                + " page_updated_at=excluded.page_updated_at, synced_at=excluded.synced_at,"
                                + " marker_count=excluded.marker_count, parse_error=''")) {
                    ps.setString(1, page);
                    ps.setString(2, map);
                    ps.setLong(3, revid);
                    ps.setString(4, pageUpdatedAt == null ? "" : pageUpdatedAt);
                    ps.setString(5, syncedAt);
                    ps.setInt(6, markers.size());
                    ps.executeUpdate();
                }
                c.commit();
                return markers.size();
            } catch (SQLException e) {
                log.warn("[KB-MAP] 写入页 {} 失败，已回滚：{}", page, e.getMessage());
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败就算了，下一次同步会覆盖
                }
                return 0;
            } finally {
                try {
                    c.setAutoCommit(auto);
                } catch (SQLException ignored) {
                    // 恢复不了就保持现状
                }
            }
        }
    }

    /** 记录一页同步失败（不让一页坏掉整次同步，但要在库里能看见） */
    public void markPageError(String page, String map, long revid, String message) {
        if (!available) {
            return;
        }
        // 失败时**不动 revid**：动了会让下一次同步误以为"这页已经同步过"，从而永久跳过它
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO kb_map_page (page, map, revid, synced_at, marker_count, parse_error)"
                        + " VALUES (?,?,?,?,0,?)"
                        + " ON CONFLICT(page) DO UPDATE SET parse_error=excluded.parse_error, synced_at=excluded.synced_at")) {
            ps.setString(1, page);
            ps.setString(2, map);
            ps.setLong(3, revid);
            ps.setString(4, now());
            ps.setString(5, message == null ? "" : message.substring(0, Math.min(300, message.length())));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[KB-MAP] 记录页 {} 的同步错误失败：{}", page, e.getMessage());
        }
    }

    /** 按词条名找 marker —— 问答侧 join 知识库用 */
    public List<Marker> byArticle(String article, int limit) {
        if (!available || article == null || article.isBlank()) {
            return List.of();
        }
        // ORDER BY 不是装饰：同一个 article 可能出现在多张地图里（实测 "Ancient Spire"
        // 在 Embervale 和 Blackmire 都有），没有它返回顺序就是不确定的，调用方和测试都会飘。
        return query("SELECT * FROM kb_map_marker WHERE article = ? ORDER BY map, page, marker_id LIMIT ?",
                article, Math.max(1, limit));
    }

    /** 全部 marker —— 派生语料时用 */
    public List<Marker> all() {
        List<Marker> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM kb_map_marker ORDER BY map, page, marker_id")) {
            while (rs.next()) {
                out.add(new Marker(rs.getString("map"), rs.getString("page"), rs.getString("grp"),
                        rs.getString("marker_id"), rs.getString("name"), rs.getString("description"),
                        rs.getString("article"), rs.getDouble("x"), rs.getDouble("y"), rs.getString("image")));
            }
        } catch (SQLException e) {
            log.warn("[KB-MAP] 读全部 marker 失败：{}", e.getMessage());
        }
        return out;
    }

    /** 按名字模糊找 marker（玩家常只记得大概的名字） */
    public List<Marker> byNameLike(String keyword, int limit) {
        if (!available || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return query("SELECT * FROM kb_map_marker WHERE name LIKE ? ORDER BY map, page, marker_id LIMIT ?",
                "%" + keyword + "%", Math.max(1, limit));
    }

    private List<Marker> query(String sql, String a, int limit) {
        List<Marker> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Marker(rs.getString("map"), rs.getString("page"), rs.getString("grp"),
                            rs.getString("marker_id"), rs.getString("name"), rs.getString("description"),
                            rs.getString("article"), rs.getDouble("x"), rs.getDouble("y"), rs.getString("image")));
                }
            }
        } catch (SQLException e) {
            log.warn("[KB-MAP] 查询失败：{}", e.getMessage());
        }
        return out;
    }

    /** 每个分组有多少 marker —— 语料扩维时按这个决定先做哪类 */
    public Map<String, Integer> countsByGroup(String map) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        String sql = map == null || map.isBlank()
                ? "SELECT grp, COUNT(*) n FROM kb_map_marker GROUP BY grp ORDER BY n DESC"
                : "SELECT grp, COUNT(*) n FROM kb_map_marker WHERE map = ? GROUP BY grp ORDER BY n DESC";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            if (map != null && !map.isBlank()) {
                ps.setString(1, map);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("grp"), rs.getInt("n"));
                }
            }
        } catch (SQLException e) {
            log.warn("[KB-MAP] 分组统计失败：{}", e.getMessage());
        }
        return out;
    }

    /** 有哪些地图 */
    public List<String> maps() {
        List<String> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT map, COUNT(*) n FROM kb_map_marker GROUP BY map ORDER BY n DESC")) {
            while (rs.next()) {
                out.add(rs.getString("map") + "(" + rs.getInt("n") + ")");
            }
        } catch (SQLException e) {
            log.warn("[KB-MAP] 读地图列表失败：{}", e.getMessage());
        }
        return out;
    }

    public int markerCount() {
        return scalar("SELECT COUNT(*) FROM kb_map_marker");
    }

    public int pageCount() {
        return scalar("SELECT COUNT(*) FROM kb_map_page");
    }

    public int groupCount() {
        return scalar("SELECT COUNT(*) FROM kb_map_group");
    }

    private int scalar(String sql) {
        if (!available) {
            return 0;
        }
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private Connection conn() {
        return qaStore.connection();
    }

    /** 同步时刻的 ISO 时间戳 */
    public static String now() {
        return Instant.now().toString();
    }
}
