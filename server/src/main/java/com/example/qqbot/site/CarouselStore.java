package com.example.qqbot.site;

import com.example.qqbot.persistence.SiteCarouselRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    /** 数据访问全部委托给它 —— SQL 与 java.sql 都在 persistence */
    private final SiteCarouselRepository repo;
    private volatile boolean available;

    public CarouselStore(SiteCarouselRepository repo) {
        this.repo = repo;
    }

    /** 一张轮播图 */
    public record Item(long id, String file, String mime, int width, int height,
                       String link, String caption, int sort, boolean enabled, String createdAt) {
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[SITE] 问答库不可用，轮播图功能关闭");
            return;
        }
        try {
            repo.initSchema();
            available = true;
            log.info("[SITE] 轮播图就绪：{} 张", repo.count());
        } catch (Exception e) {
            log.warn("[SITE] 初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available && repo.isAvailable();
    }

    public List<Item> list() {
        if (!available) {
            return List.of();
        }
        try {
            return repo.all().stream().map(CarouselStore::toItem).toList();
        } catch (Exception e) {
            log.warn("[SITE] 读取轮播清单失败：{}", e.getMessage());
            return List.of();
        }
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
        try {
            SiteCarouselRepository.Row r = repo.byId(id);
            return r == null ? null : toItem(r);
        } catch (Exception e) {
            log.warn("[SITE] 查询轮播图失败：{}", e.getMessage());
            return null;
        }
    }

    public int count() {
        return available ? repo.count() : 0;
    }

    /** 追加一张（排在最后）。返回新 id，失败返回 -1 */
    public long add(String file, String mime, int width, int height, String link, String caption) {
        if (!available) {
            return -1L;
        }
        try {
            return repo.insert(file, mime, width, height, link == null ? "" : link,
                    caption == null ? "" : caption, repo.nextSort(), Instant.now().toString());
        } catch (Exception e) {
            log.warn("[SITE] 新增轮播图失败：{}", e.getMessage());
            return -1L;
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
        try {
            repo.update(id,
                    link == null ? old.link() : link,
                    caption == null ? old.caption() : caption,
                    enabled == null ? old.enabled() : enabled);
        } catch (Exception e) {
            log.warn("[SITE] 更新轮播图失败：{}", e.getMessage());
            return null;
        }
        return get(id);
    }

    /** 删除一行，返回被删的行（调用方据此删磁盘文件）；没有返回 null */
    public Item delete(long id) {
        Item old = get(id);
        if (old == null) {
            return null;
        }
        try {
            repo.delete(id);
        } catch (Exception e) {
            log.warn("[SITE] 删除轮播图失败：{}", e.getMessage());
            return null;
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
        return writeOrder(reordered, "调整轮播顺序失败");
    }

    /** 把 sort 重写成 0..n-1（删掉中间某张之后不留空档） */
    private void resequence() {
        writeOrder(list(), "重排轮播顺序失败");
    }

    private boolean writeOrder(List<Item> ordered, String what) {
        List<long[]> pairs = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            pairs.add(new long[]{ordered.get(i).id(), i});
        }
        try {
            repo.updateSorts(pairs);
            return true;
        } catch (Exception e) {
            log.warn("[SITE] {}：{}", what, e.getMessage());
            return false;
        }
    }

    /** 供排查用：库里的行数分布 */
    public Map<String, Integer> stats() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("total", count());
        out.put("enabled", listEnabled().size());
        return out;
    }

    private static Item toItem(SiteCarouselRepository.Row r) {
        return new Item(r.id(), r.file(), r.mime(), r.width(), r.height(),
                r.link(), r.caption(), r.sort(), r.enabled(), r.createdAt());
    }
}
