package com.example.qqbot.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 公开站轮播图的**数据访问** —— SQL 只在这里。
 *
 * <p>业务类 {@code site.CarouselStore} 保留"怎么排、删完怎么补号"这类**排序策略**；
 * 这里只管存与取。
 */
@Repository
public class SiteCarouselRepository {

    /** 一行轮播图 */
    public record Row(long id, String file, String mime, int width, int height, String link,
                      String caption, int sort, boolean enabled, String createdAt) {
    }

    private static final String COLS =
            "SELECT id, file, mime, width, height, link, caption, sort, enabled, created_at FROM site_carousel";

    private final Jdbc jdbc;

    public SiteCarouselRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.isAvailable();
    }

    public void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS site_carousel ("
                + " id         INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " file       TEXT NOT NULL,"
                + " mime       TEXT NOT NULL DEFAULT '',"
                + " width      INTEGER NOT NULL DEFAULT 0,"
                + " height     INTEGER NOT NULL DEFAULT 0,"
                + " link       TEXT NOT NULL DEFAULT '',"
                + " caption    TEXT NOT NULL DEFAULT '',"
                + " sort       INTEGER NOT NULL DEFAULT 0,"
                + " enabled    INTEGER NOT NULL DEFAULT 1,"
                + " created_at TEXT NOT NULL DEFAULT '')");
    }

    public List<Row> all() {
        return jdbc.query(COLS + " ORDER BY sort, id", SiteCarouselRepository::read);
    }

    public Row byId(long id) {
        return jdbc.queryOne(COLS + " WHERE id = ?", SiteCarouselRepository::read, id);
    }

    public int count() {
        return (int) jdbc.count("SELECT COUNT(*) FROM site_carousel");
    }

    /** 排在现有最大 sort 之后；空表返回 0 */
    public int nextSort() {
        return (int) jdbc.count("SELECT COALESCE(MAX(sort), -1) + 1 FROM site_carousel");
    }

    public long insert(String file, String mime, int width, int height, String link,
                       String caption, int sort, String createdAt) {
        return jdbc.insert("INSERT INTO site_carousel"
                        + " (file, mime, width, height, link, caption, sort, enabled, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,1,?)",
                file, mime, width, height, link, caption, sort, createdAt);
    }

    public void update(long id, String link, String caption, boolean enabled) {
        jdbc.update("UPDATE site_carousel SET link = ?, caption = ?, enabled = ? WHERE id = ?",
                link, caption, enabled ? 1 : 0, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM site_carousel WHERE id = ?", id);
    }

    /**
     * 批量重写 sort（一个事务）。
     *
     * @param idToSort 每项 {@code [id, sort]}
     */
    public void updateSorts(List<long[]> idToSort) {
        if (idToSort.isEmpty()) {
            return;
        }
        jdbc.batch("UPDATE site_carousel SET sort = ? WHERE id = ?",
                idToSort.stream()
                        .map(p -> new Object[]{(int) p[1], p[0]})
                        .toList());
    }

    private static Row read(Jdbc.Row r) {
        return new Row(r.longOf("id"), r.str("file"), r.str("mime"), r.intOf("width"),
                r.intOf("height"), r.str("link"), r.str("caption"), r.intOf("sort"),
                r.bool("enabled"), r.str("created_at"));
    }
}
