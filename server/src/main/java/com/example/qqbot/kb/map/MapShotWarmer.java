package com.example.qqbot.kb.map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 按需预热 —— 出图缺哪几张瓦片，就只补那几张。
 *
 * <h2>为什么不能只靠全量预热</h2>
 * 全量预热一次要拉几百张、按 wiki 的限流退避要跑一两个小时；而群里问一个点位
 * 只缺 <b>4~9 张</b>。等全量跑完，那一个小时里所有地点问题都出不了图。
 *
 * <p>反过来，全量预热是"为了以后"，按需预热是"为了刚才这一问" —— 两者不冲突：
 * 出图发现缺口 → 把缺的几张丢给后台 → 群里这次只回文字 → 下次问同一个点位就有图。
 *
 * <h2>为什么可以另起一个线程池</h2>
 * 出站限流是 {@link WikiMapImageSource} 里的**全局闸门**（所有请求串行通过），
 * 所以多一个线程不会变成并发轰炸，只是让这几张排在队列前面。
 * 用单线程池还保证了同一批内部是顺序取的。
 *
 * <h2>去重</h2>
 * 同一个点位连着被问几次，缺口是同一批；{@link #pending} 保证它们只排一次队。
 */
@Component
public class MapShotWarmer {

    private static final Logger log = LoggerFactory.getLogger(MapShotWarmer.class);

    /** 一次按需预热最多补几张。出图最多用到十来张，超出这个数说明 key 或视口算错了 */
    private static final int MAX_TILES = 24;

    private final MapImageCache images;

    /** 已排队或正在补的瓦片键 —— 重复问同一个点位不会重复排队 */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "map-shot-warm");
        t.setDaemon(true);
        return t;
    });

    public MapShotWarmer(MapImageCache images) {
        this.images = images;
    }

    /**
     * 把缺的瓦片交给后台补。
     *
     * @param width 出图这次用的档位。下次问同一个点位算出来的档位是一样的，所以只补这一档
     */
    public void warmAsync(String map, String page, List<String> tiles, int width) {
        if (map == null || page == null || tiles == null || tiles.isEmpty()) {
            return;
        }
        List<String> fresh = new ArrayList<>();
        for (String tile : tiles.stream().distinct().limit(MAX_TILES).toList()) {
            if (pending.add(key(map, page, width, tile))) {
                fresh.add(tile);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        log.info("[MAP-SHOT] 按需预热：缺 {} 张瓦片（{} 档，{}），已交给后台补 —— 补完再问就有图",
                fresh.size(), width, page);
        pool.submit(() -> {
            try {
                for (String tile : fresh) {
                    images.get(map, page, tile, width);
                }
            } finally {
                fresh.forEach(tile -> pending.remove(key(map, page, width, tile)));
            }
        });
    }

    private static String key(String map, String page, int width, String tile) {
        return map + "|" + page + "|" + width + "|" + tile;
    }
}
