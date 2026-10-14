package com.example.qqbot.kb.map;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.MapSync;
import com.example.qqbot.persistence.KbMapRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 清掉**不在白名单里的地图**留下的一切：库里的行、磁盘上的瓦片缓存。
 *
 * <h2>为什么必须有它</h2>
 * {@link MapSync#getMaps() 白名单}只管住了"以后不再写进来"。已经躺在库里的不会自己走 ——
 * 而它们照样会出现在四个地方，每一个都是真问题：
 * <ol>
 *   <li><b>目录接口</b>：「默认显示」页会列出 5 张地图的切换 chip，其中四张是旧版本或区域小图；</li>
 *   <li><b>地点问答</b>：旧世界的 marker 会进 {@code PlaceDirectory}，群友问出来的坐标可能来自抢先体验版；</li>
 *   <li><b>地点语料</b>：语料按 article 聚合，于是"远古尖塔"一条里混了三套互不相干的坐标系；</li>
 *   <li><b>磁盘</b>：旧地图的瓦片缓存白占几十 MB，而且永远不会再被请求到。</li>
 * </ol>
 *
 * <h2>什么时候跑</h2>
 * <ul>
 *   <li><b>启动一次</b> —— 让"改了配置但还没点同步"也能立刻见效；</li>
 *   <li><b>每次同步之后</b> —— wiki 哪天把某张旧图加回来，下一次同步就把它挡在门外。</li>
 * </ul>
 * 两处都是幂等的：没东西可清时几乎零开销，也不打日志。
 *
 * <h2>它不负责重新派生地点语料</h2>
 * 语料要调向量接口、要花钱，不能在一个后台清理里顺手做。
 * 所以这里只**明确喊一声**：语料是用旧地图数据派的，请到后台点一次
 * 「清除地点语料」再「派生地点语料」。见 {@link LocationCorpusBuilder#derivedCount()}。
 */
@Component
public class MapResidueCleaner {

    private static final Logger log = LoggerFactory.getLogger(MapResidueCleaner.class);

    /**
     * 一次清理的结果。
     *
     * @param rows        库里删掉的行数
     * @param cacheFiles  磁盘上删掉的瓦片文件数
     * @param corpusStale 库里还留着旧地图派生出来的地点语料（需要重新派生）
     */
    public record Report(List<String> maps, int rows, long cacheFiles, long cacheBytes,
                         int corpusStale) {

        public boolean empty() {
            return rows == 0 && cacheFiles == 0;
        }
    }

    private final MapSync cfg;
    private final KbMapStore store;
    private final MapImageCache images;
    private final LocationCorpusBuilder corpus;

    public MapResidueCleaner(KbPolicy policy, KbMapStore store, MapImageCache images,
                             LocationCorpusBuilder corpus) {
        // 只读接口而不是 KbProperties：kb 包不认识 config 包（ArchitectureTest 有护栏）
        this.cfg = policy.getMapSync();
        this.store = store;
        this.images = images;
        this.corpus = corpus;
    }

    /** 启动即清一次 —— 库里已有的残留不会自己走 */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanAtStartup() {
        clean();
    }

    /** 清理一次。白名单为空时**什么都不做**（保持"命名空间里有什么就有什么"的旧行为） */
    public Report clean() {
        List<String> keep = cfg.getMaps();
        if (keep == null || keep.isEmpty()) {
            return new Report(List.of(), 0, 0, 0, 0);
        }
        KbMapRepository.PurgeCounts rows = store.purgeMapsNotIn(keep);
        MapImageCache.CachePurge cache = images.purgeMapsNotIn(keep);
        if (rows.total() == 0 && cache.files() == 0) {
            return new Report(List.of(), 0, 0, 0, 0);
        }
        log.warn("[MAP-CLEAN] 清掉不在白名单（{}）里的地图：{}；"
                        + "明细 页面 {} / 画布 {} / 分组 {} / marker {} / 备注 {} / 自定义点位 {}，"
                        + "瓦片缓存 {} 个文件 / {}",
                String.join(",", keep), String.join("、", rows.maps()),
                rows.pages(), rows.canvases(), rows.groups(), rows.markers(), rows.notes(),
                rows.places(), cache.files(), cacheBytesText(cache.bytes()));

        // 语料没法在这里自动重建（要调向量接口、要花钱），但绝不能默不作声：
        // 不重新派生的话，机器人会继续拿旧世界的坐标回答地点问题
        int stale = corpus.derivedCount();
        if (stale > 0) {
            log.warn("[MAP-CLEAN] ⚠️ 地点语料（{} 条）是用被清掉的地图数据派生的，仍带着旧世界的坐标。"
                    + "请到后台「地图标注 → 同步与状态」点一次「清除地点语料」再「派生地点语料」。", stale);
        }
        return new Report(rows.maps(), rows.total(), cache.files(), cache.bytes(), stale);
    }

    private static String cacheBytesText(long bytes) {
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
