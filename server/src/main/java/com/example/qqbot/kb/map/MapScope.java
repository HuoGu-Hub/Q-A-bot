package com.example.qqbot.kb.map;

import com.example.qqbot.persistence.KbMapActiveRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "当前用哪几张地图" —— 目录、地点问答、地点语料都从这里取。
 *
 * <h2>为什么不是配置文件里的白名单</h2>
 * 白名单把"哪张地图是当前世界"这个**会变的判断**固化进了一份基本不变的配置。
 * 而游戏一更新，wiki 通常会另起一页、名字也跟着变 —— 那时白名单不会报错，
 * 它只会让**新地图被静默跳过、旧地图继续以"最新"的样子留着**。
 *
 * <p>所以改成两层：
 * <ol>
 *   <li><b>摄入层</b>：命名空间里有什么就同步什么，谁也不丢。数据留着的成本是几百行，
 *       而"删掉了才发现要"是不可逆的；</li>
 *   <li><b>启用层</b>（这里）：一张表记住用哪几张。没启用的数据**完全不可见** ——
 *       不渲染、不参与问答、不进语料、不预热，但随时可以点一下就用。</li>
 * </ol>
 * 于是"wiki 换了新地图"这件事从**静默失效**变成**后台列表里多一行候选**，
 * 而判断材料是客观的：点数、wiki 最后改动时间。系统不替你判断谁最新，只负责摆全。
 *
 * <h2>没人选过的时候怎么办</h2>
 * 兜底选**点数最多**的那张，并在日志里说明"是自动选的、可以改"。
 * 兜底结果**不落库** —— 那样数据一变它会跟着变，也不会假装成一个人类决定。
 *
 * <p>注意兜底的触发条件是"表里一条启用记录都没有"。也就是说**全关不是一个可达状态**：
 * 那会让地图页变成空白，而这不是一个有用的状态。
 */
@Service
public class MapScope {

    private static final Logger log = LoggerFactory.getLogger(MapScope.class);

    /**
     * 后台候选列表的一行。
     *
     * @param lastChanged wiki 最后一次改动这页的时间（空 = 没记录）
     * @param auto        当前启用它是因为"自动兜底"而不是有人选过
     */
    public record Candidate(String map, int markers, String lastChanged, boolean active,
                            boolean auto) {
    }

    private static final long CACHE_TTL_MS = 10_000;

    private final KbMapStore store;
    private final KbMapActiveRepository repo;
    private final AtomicBoolean autoLogged = new AtomicBoolean();

    private volatile Set<String> cached;
    private volatile long cachedAt;

    public MapScope(KbMapStore store, KbMapActiveRepository repo) {
        this.store = store;
        this.repo = repo;
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[MAP-SCOPE] 问答库不可用，启用地图表初始化跳过");
            return;
        }
        try {
            repo.initSchema();
        } catch (Exception e) {
            log.warn("[MAP-SCOPE] 启用地图表初始化失败：{}", e.getMessage());
        }
    }

    /**
     * 当前启用的地图名。空集只可能出现在"库里一条点位都没有"的时候。
     *
     * <p>带 10 秒缓存：这个判断会被**每个瓦片请求**问到（{@code allowedAsset} 要挡非启用地
     * 图的图片），而兜底那段还要按地图做一次点位统计 —— 一张图的 64 个瓦片就是 64 次。
     * 后台切换时显式失效，所以体感上是即时的；同步带来新地图最多晚 10 秒出现。
     */
    public Set<String> activeMaps() {
        Set<String> hit = cached;
        if (hit != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return hit;
        }
        Set<String> fresh = computeActive();
        cached = fresh;
        cachedAt = System.currentTimeMillis();
        return fresh;
    }

    private Set<String> computeActive() {
        Set<String> chosen = new LinkedHashSet<>(safeActive());
        if (!chosen.isEmpty()) {
            return chosen;
        }
        String auto = pickByMarkers(store.markerCounts());
        if (auto == null) {
            return Set.of();
        }
        if (autoLogged.compareAndSet(false, true)) {
            log.info("[MAP-SCOPE] 后台还没选过地图，自动启用「{}」（点数最多）—— 可在后台「地图」里改",
                    auto);
        }
        return Set.of(auto);
    }

    public boolean isActive(String map) {
        return map != null && !map.isBlank() && activeMaps().contains(map.strip());
    }

    /**
     * 后台候选列表：wiki 上现在有的每张地图 + 点数 + 最后改动时间 + 是否启用。
     *
     * <p>这就是"让人能判断"的全部材料。按点数从多到少排 —— 通常当前世界就是第一张。
     */
    public List<Candidate> candidates() {
        Set<String> active = activeMaps();
        Map<String, Boolean> chosen = safeAll();
        Map<String, Integer> counts = store.markerCounts();
        Map<String, String> changed = store.latestPageUpdate();
        List<Candidate> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String map = e.getKey();
            out.add(new Candidate(map, e.getValue(), changed.getOrDefault(map, ""),
                    active.contains(map), active.contains(map) && !chosen.containsKey(map)));
        }
        out.sort(Comparator.comparingInt(Candidate::markers).reversed()
                .thenComparing(Candidate::map));
        return out;
    }

    /** 后台切换某张地图。返回 false 表示存储不可用 */
    public boolean setActive(String map, boolean active) {
        if (map == null || map.isBlank() || !repo.isAvailable()) {
            return false;
        }
        log.info("[MAP-SCOPE] 后台把地图「{}」设为 {}（此前启用：{}）",
                map, active ? "启用" : "停用", activeMaps());
        boolean ok = repo.set(map.strip(), active, java.time.Instant.now().toString());
        cached = null;      // 体感上必须是即时的，不能让那 10 秒缓存挡住
        cachedAt = 0;
        return ok;
    }

    /** 点数最多的一张；并列时按名字取第一个，保证同一份数据每次结果一致 */
    private static String pickByMarkers(Map<String, Integer> counts) {
        String best = null;
        int bestN = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int n = e.getValue() == null ? 0 : e.getValue();
            if (n > bestN || (n == bestN && best != null && e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
                bestN = n;
            }
        }
        return best;
    }

    private List<String> safeActive() {
        if (!repo.isAvailable()) {
            return List.of();
        }
        try {
            return repo.active();
        } catch (Exception e) {
            log.warn("[MAP-SCOPE] 读启用地图失败：{}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Boolean> safeAll() {
        try {
            return new LinkedHashMap<>(repo.all());
        } catch (Exception e) {
            return Map.of();
        }
    }
}
