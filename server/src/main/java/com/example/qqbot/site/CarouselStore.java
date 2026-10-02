package com.example.qqbot.site;

import com.example.qqbot.persistence.SqliteConnectionProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页轮播的**清单**存储（图片文件本身在磁盘上，见 {@link CarouselService}）。
 *
 * <p>为什么清单进库、图片进文件系统：清单是要排序、要改说明、要启停的小结构数据，
 * 走 SQLite 一行一条最省事；而图片是二进制，塞进库只会让备份和读取都变难。
 * 两边用 {@code id} 关联，库里那行记着文件名。
 *
 * <p>和 {@link SiteTextService} 一样：库不可用时整体降级成"没有轮播图"，
 * **绝不让公开站因为这块挂掉**。
 */
@Component
public class CarouselStore {

    private static final Logger log = LoggerFactory.getLogger(CarouselStore.class);

    /** 一条轮播图 */
    public record Item(long id, String file, String mime, int width, int height,
                       String link, String caption, int sort, boolean enabled, String createdAt) {
    }

    private final SqliteConnectionProvider db;
    private volatile boolean available;

    public CarouselStore(SqliteConnectionProvider db) {
        this.db = db;
    }

    private Connection conn() {
        return db.connection();
    }

    public boolean isAvailable() {
        return available;
    }

    @PostConstruct
    void init() {
        try {
            if (!db.isAvailable()) {
                log.warn("[SITE] 问答库不可用，首页轮播功能关闭");
                return;
            }
            try (Statement st = conn().createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS site_carousel ("
                        + " id         INTEGER PRIMARY KEY,"
                        + " file       TEXT NOT NULL,"
                        + " mime       TEXT NOT NULL,"
                        + " width      INTEGER NOT NULL DEFAULT 0,"
                        + " height     INTEGER NOT NULL DEFAULT 0,"
                        + " link       TEXT NOT NULL DEFAULT '',"
                        + " caption    TEXT NOT NULL DEFAULT '',"
                        + " sort       INTEGER NOT NULL DEFAULT 0,"
                        + " enabled    INTEGER NOT NULL DEFAULT 1,"
                        + " created_at TEXT NOT NULL)");
            }
            available = true;
            log.info("[SITE] 首页轮播就绪：{} 张（启用 {} 张）", count(), listEnabled().size());
        } catch (Exception e) {
            log.warn("[SITE] 轮播清单初始化失败（不影响公开站）：{}", e.getMessage());
            available = false;
        }
    }

    // ==================== 读 ====================

    /** 全部（后台用），按展示顺序 */
    public List<Item> list() {
        if (!available) {
            return List.of();
        }
        List<Item> out = new ArrayList<>();
        synchronized (this) {
            try (Statement st = conn().createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, file, mime, width, height, link, caption, sort, enabled, created_at"
                                 + " FROM site_carousel ORDER BY sort, id")) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            } catch (Exception e) {
                log.warn("[SITE] 读取轮播清单失败：{}", e.getMessage());
            }
        }
        return out;
    }

    /** 只取启用的（公开站用） */
    public List<Item> listEnabled() {
        List<Item> out = new ArrayList<>();
        for (Item it : list()) {
            if (it.enabled()) {
                out.add(it);
            }
        }
        return out;
    }

    public Item get(long id) {
        if (!available) {
            return null;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT id, file, mime, width, height, link, caption, sort, enabled, created_at"
                            + " FROM site_carousel WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            } catch (Exception e) {
                log.warn("[SITE] 查询轮播图失败：{}", e.getMessage());
                return null;
            }
        }
    }

    public int count() {
        if (!available) {
            return 0;
        }
        synchronized (this) {
            try (Statement st = conn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM site_carousel")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private static Item read(ResultSet rs) throws Exception {
        return new Item(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getString(7),
                rs.getInt(8), rs.getInt(9) != 0, rs.getString(10));
    }

    // ==================== 写 ====================

    /** 追加一张（排在最后）。返回新 id，失败返回 -1 */
    public long add(String file, String mime, int width, int height, String link, String caption) {
        if (!available) {
            return -1L;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "INSERT INTO site_carousel (file, mime, width, height, link, caption, sort, enabled, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,1,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, file);
                ps.setString(2, mime);
                ps.setInt(3, width);
                ps.setInt(4, height);
                ps.setString(5, link == null ? "" : link);
                ps.setString(6, caption == null ? "" : caption);
                // 排在现有最大 sort 之后。没有行时 max 为 null → 0
                ps.setInt(7, nextSort());
                ps.setString(8, Instant.now().toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            } catch (Exception e) {
                log.warn("[SITE] 新增轮播图失败：{}", e.getMessage());
                return -1L;
            }
        }
    }

    private int nextSort() {
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(sort), -1) + 1 FROM site_carousel")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 改说明 / 链接 / 启停。传 {@code null} 表示这一项不动。
     *
     * @return 更新后的行；没这条或失败返回 null
     */
    public Item update(long id, String link, String caption, Boolean enabled) {
        Item old = get(id);
        if (old == null) {
            return null;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE site_carousel SET link = ?, caption = ?, enabled = ? WHERE id = ?")) {
                ps.setString(1, link == null ? old.link() : link);
                ps.setString(2, caption == null ? old.caption() : caption);
                ps.setInt(3, (enabled == null ? old.enabled() : enabled) ? 1 : 0);
                ps.setLong(4, id);
                ps.executeUpdate();
            } catch (Exception e) {
                log.warn("[SITE] 更新轮播图失败：{}", e.getMessage());
                return null;
            }
        }
        return get(id);
    }

    /** 删除一行，返回被删的行（调用方据此删磁盘文件）；没有返回 null */
    public Item delete(long id) {
        Item old = get(id);
        if (old == null) {
            return null;
        }
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "DELETE FROM site_carousel WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (Exception e) {
                log.warn("[SITE] 删除轮播图失败：{}", e.getMessage());
                return null;
            }
        }
        resequence();
        return old;
    }

    /**
     * 上移 / 下移一位。
     *
     * <p>实现是"交换位置后把 sort 重写成 0..n-1" —— 行数最多也就 {@code max-count} 张，
     * 整表重排比小心翼翼地换两个 sort 值简单得多，而且顺手把历史遗留的重复 / 断号 sort 修好。
     *
     * @return 是否真的动了
     */
    public boolean move(long id, int delta) {
        if (delta == 0 || !available) {
            return false;
        }
        List<Item> items = list();
        int from = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id() == id) {
                from = i;
                break;
            }
        }
        int to = from + Integer.signum(delta);
        if (from < 0 || to < 0 || to >= items.size()) {
            return false;
        }
        List<Item> reordered = new ArrayList<>(items);
        reordered.set(from, items.get(to));
        reordered.set(to, items.get(from));
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE site_carousel SET sort = ? WHERE id = ?")) {
                for (int i = 0; i < reordered.size(); i++) {
                    ps.setInt(1, i);
                    ps.setLong(2, reordered.get(i).id());
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (Exception e) {
                log.warn("[SITE] 调整轮播顺序失败：{}", e.getMessage());
                return false;
            }
        }
        return true;
    }

    /** 把 sort 重写成 0..n-1（删掉中间某张之后不留空档） */
    private void resequence() {
        List<Item> items = list();
        synchronized (this) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE site_carousel SET sort = ? WHERE id = ?")) {
                for (int i = 0; i < items.size(); i++) {
                    ps.setInt(1, i);
                    ps.setLong(2, items.get(i).id());
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (Exception e) {
                log.warn("[SITE] 重排轮播顺序失败：{}", e.getMessage());
            }
        }
    }

    /** 供排查用：库里的行数分布 */
    public Map<String, Integer> stats() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("total", count());
        out.put("enabled", listEnabled().size());
        return out;
    }
}
