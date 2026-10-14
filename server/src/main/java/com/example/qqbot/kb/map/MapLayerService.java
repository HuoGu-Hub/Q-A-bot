package com.example.qqbot.kb.map;

import com.example.qqbot.persistence.KbMapLayerRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 地图默认图层的读写。
 *
 * <p>这一层只做"可用性判断 + 校验 + 落库"，不碰渲染：
 * 目录接口把结果带出去，前端拿它决定首屏开哪些开关。
 *
 * <p><b>为什么把校验放在这里</b>：白名单里可能残留已经消失的分组键
 * （wiki 改过分组名、某组点位被清空）。落库前按当前目录过滤一遍，
 * 免得配置越攒越脏；读取时不过滤，让配置能活过短暂的"某组暂时没有点位"。
 */
@Service
public class MapLayerService {

    private static final Logger log = LoggerFactory.getLogger(MapLayerService.class);

    private final KbMapLayerRepository repo;

    /**
     * 校验分组键时用**仓储层**而不是 {@code MapViewService}。
     *
     * <p>反过来依赖会成环：{@code MapViewService} 要把默认图层带进目录，就得先拿到本类。
     * 而且这里本来就只关心"这个分组键还存不存在"，不需要目录那层的加工。
     */
    private final KbMapStore mapStore;

    private volatile boolean available;

    public MapLayerService(KbMapLayerRepository repo, KbMapStore mapStore) {
        this.repo = repo;
        this.mapStore = mapStore;
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[MAP-LAYER] 问答库不可用，默认图层配置一并关闭");
            available = false;
            return;
        }
        try {
            repo.initSchema();
            available = true;
            Map<String, Set<String>> all = repo.all();
            if (!all.isEmpty()) {
                log.info("[MAP-LAYER] 默认图层配置就绪：{} 张地图", all.size());
            }
        } catch (Exception e) {
            log.warn("[MAP-LAYER] 初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** 已配置的默认图层；缺键 = 这张地图还没配置过 */
    public Map<String, Set<String>> configured() {
        return available ? repo.all() : Map.of();
    }

    /**
     * 保存一张地图的默认图层。
     *
     * @param keys {@code null} 表示恢复成"未配置"，与"全关"是两件事
     */
    public void save(String map, Collection<String> keys) {
        if (keys == null) {
            repo.clear(map);
            log.info("[MAP-LAYER] {} 恢复为未配置", map);
            return;
        }
        Set<String> known = new java.util.LinkedHashSet<>();
        for (KbMapStore.Group g : mapStore.groups(map)) {
            known.add(g.key());
        }
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String k : keys) {
            // 只留当前目录里确实存在的分组键
            if (known.contains(k)) {
                kept.add(k);
            }
        }
        repo.replace(map, kept);
        log.info("[MAP-LAYER] {} 默认图层保存为 {} 个分组", map, kept.size());
    }
}
