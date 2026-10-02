package com.example.qqbot.kb.map;

import com.example.qqbot.persistence.KbMapRepository;
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
 * 会在 WAL 共享内存上冲突（实测 {@code SQLITE_IOERR_SHMOPEN}）。
 *
 * <h2>本类只剩「语义」</h2>
 * SQL 与 {@code java.sql} 全部搬进了 {@link KbMapRepository}。这里管的是：
 * 解析器的类型怎么摊成行、{@code map} 从哪来、错误信息留多长、以及
 * {@code maps()} 那种"给人看的字符串"的拼法。
 */
@Component
public class KbMapStore {

    private static final Logger log = LoggerFactory.getLogger(KbMapStore.class);

    /** 错误信息入库前截断到多少字 */
    private static final int MAX_ERROR_LEN = 300;

    /** 数据访问全部委托给它 —— SQL 与 java.sql 都在 persistence */
    private final KbMapRepository repo;

    private volatile boolean available;

    public KbMapStore(KbMapRepository repo) {
        this.repo = repo;
    }

    /** 某一页上一次同步到的版本 */
    public record PageState(String page, String map, long revid, String pageUpdatedAt,
                            String syncedAt, int markerCount, String parseError) {
    }

    /**
     * 一个 marker（落库/读出一致）。
     *
     * <p>持久层有一份同形的 {@code KbMapRepository.MarkerRow} —— **两者不能合并**，
     * 让持久层返回本类的嵌套类型会反向建立依赖。
     */
    public record Marker(String map, String page, String group, String markerId, String name,
                         String description, String article, double x, double y, String image) {
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[KB-MAP] 问答库不可用，地图存储一并关闭");
            available = false;
            return;
        }
        try {
            repo.initSchema();
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
        if (!available) {
            return new LinkedHashMap<>();
        }
        try {
            return repo.knownRevisions();
        } catch (Exception e) {
            log.warn("[KB-MAP] 读同步状态失败：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 一页的同步状态（含出错原因），供管理端排查 */
    public List<PageState> pages() {
        if (!available) {
            return List.of();
        }
        try {
            List<PageState> out = new ArrayList<>();
            for (KbMapRepository.PageState p : repo.pages()) {
                out.add(new PageState(p.page(), p.map(), p.revid(), p.pageUpdatedAt(),
                        p.syncedAt(), p.markerCount(), p.parseError()));
            }
            return out;
        } catch (Exception e) {
            log.warn("[KB-MAP] 读页面列表失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 写入一页的解析结果。**先删该页旧 marker 再插新的** —— 这样 wiki 上删掉的 marker
     * 在本地也会消失（幂等 + 能表达删除）。整页在一个事务里做，中途失败不会留半个页面。
     *
     * <p>{@code map} 从哪来：解析器给了就用解析器的，没给就从 page 名推（{@code Map:X/...}）。
     */
    public int replacePage(String page, long revid, String pageUpdatedAt, KbMapParser.Meta meta,
                           List<KbMapParser.Group> groups, List<KbMapParser.Marker> markers,
                           String syncedAt) {
        if (!available) {
            return 0;
        }
        String map = meta == null ? KbMapParser.mapOf(page) : meta.map();
        List<KbMapRepository.GroupRow> groupRows = groups.stream()
                .map(g -> new KbMapRepository.GroupRow(g.key(), g.name(), g.icon(), g.collectible()))
                .toList();
        // ⚠️ page 列用**方法参数**，不是 marker 自己的 —— 一个 marker 可能被多页定义，
        //    这里写的是"这次同步的是哪一页"
        List<KbMapRepository.MarkerRow> markerRows = markers.stream()
                .map(m -> new KbMapRepository.MarkerRow(m.map(), page, m.group(), m.markerId(),
                        m.name(), m.description(), m.article(), m.x(), m.y(), m.image()))
                .toList();
        try {
            return repo.replacePage(page, map, revid, pageUpdatedAt, groupRows, markerRows, syncedAt);
        } catch (Exception e) {
            log.warn("[KB-MAP] 写入页 {} 失败，已回滚：{}", page, e.getMessage());
            return 0;
        }
    }

    /** 记录一页同步失败（不让一页坏掉整次同步，但要在库里能看见） */
    public void markPageError(String page, String map, long revid, String message) {
        if (!available) {
            return;
        }
        String msg = message == null ? "" : message;
        if (msg.length() > MAX_ERROR_LEN) {
            msg = msg.substring(0, MAX_ERROR_LEN);
        }
        // 失败时**不动 revid**：动了会让下一次同步误以为"这页已经同步过"，从而永久跳过它
        try {
            repo.markPageError(page, map, revid, now(), msg);
        } catch (Exception e) {
            log.warn("[KB-MAP] 记录页 {} 的同步错误失败：{}", page, e.getMessage());
        }
    }

    /** 按词条名找 marker —— 问答侧 join 知识库用 */
    public List<Marker> byArticle(String article, int limit) {
        if (!available || article == null || article.isBlank()) {
            return List.of();
        }
        try {
            return markers(repo.byArticle(article, Math.max(1, limit)));
        } catch (Exception e) {
            log.warn("[KB-MAP] 查询失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 全部 marker —— 派生语料时用 */
    public List<Marker> all() {
        if (!available) {
            return List.of();
        }
        try {
            return markers(repo.allMarkers());
        } catch (Exception e) {
            log.warn("[KB-MAP] 读全部 marker 失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 按名字模糊找 marker（玩家常只记得大概的名字） */
    public List<Marker> byNameLike(String keyword, int limit) {
        if (!available || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        try {
            return markers(repo.byNameLike("%" + keyword + "%", Math.max(1, limit)));
        } catch (Exception e) {
            log.warn("[KB-MAP] 查询失败：{}", e.getMessage());
            return List.of();
        }
    }

    private static List<Marker> markers(List<KbMapRepository.MarkerRow> rows) {
        List<Marker> out = new ArrayList<>(rows.size());
        for (KbMapRepository.MarkerRow m : rows) {
            out.add(new Marker(m.map(), m.page(), m.group(), m.markerId(), m.name(),
                    m.description(), m.article(), m.x(), m.y(), m.image()));
        }
        return out;
    }

    /** 每个分组有多少 marker —— 语料扩维时按这个决定先做哪类 */
    public Map<String, Integer> countsByGroup(String map) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        try {
            for (KbMapRepository.GroupCount g : repo.countsByGroup(map)) {
                out.put(g.group(), (int) g.count());
            }
        } catch (Exception e) {
            log.warn("[KB-MAP] 分组统计失败：{}", e.getMessage());
        }
        return out;
    }

    /** 有哪些地图 —— 形如 {@code Embervale(1234)}，给人看的一行 */
    public List<String> maps() {
        if (!available) {
            return List.of();
        }
        try {
            List<String> out = new ArrayList<>();
            for (KbMapRepository.MapCount m : repo.mapCounts()) {
                out.add(m.map() + "(" + m.count() + ")");
            }
            return out;
        } catch (Exception e) {
            log.warn("[KB-MAP] 读地图列表失败：{}", e.getMessage());
            return List.of();
        }
    }

    public int markerCount() {
        return count(repo::markerCount);
    }

    public int pageCount() {
        return count(repo::pageCount);
    }

    public int groupCount() {
        return count(repo::groupCount);
    }

    private int count(java.util.function.LongSupplier source) {
        if (!available) {
            return 0;
        }
        try {
            return (int) source.getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 同步时刻的 ISO 时间戳 */
    public static String now() {
        return Instant.now().toString();
    }
}
