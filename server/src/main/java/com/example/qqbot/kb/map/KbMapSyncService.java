package com.example.qqbot.kb.map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图数据同步 —— 把 wiki 的 marker 增量同步进本地库。
 *
 * <h2>为什么这是"未来地图功能"的地基，而不只是一个抓取脚本</h2>
 * 地图 marker 是**唯一**同时具备三件事的数据源：
 * <ol>
 *   <li><b>坐标</b>（x/y）→ 未来地图查看器可以直接打点；</li>
 *   <li><b>对应词条</b>（{@code article}，实测 100% 覆盖）→ 与知识库 join；</li>
 *   <li><b>结构化分类</b>（46 个分组，含 13 个按游戏区域划分的 Lore 组）→ 免费的"地点/区域"分类法。</li>
 * </ol>
 * 也就是说：**同步一次，同时解决"语料扩维"和"地图打点"两件事**。
 *
 * <h2>增量语义</h2>
 * 每页都有 {@code revid}。只有版本变过的页才会被重新取正文与解析；
 * 没变的页一个字节都不动。首次同步会拉全部 32 页（约 548 KB）。
 *
 * <h2>失败语义</h2>
 * **按页隔离**：一页 JSON 坏了只记 {@code parse_error}，其余页照常同步。
 * 单页失败**不推进它的 revid**，所以下次同步会重试它，不会被永久跳过。
 */
@Service
public class KbMapSyncService {

    private static final Logger log = LoggerFactory.getLogger(KbMapSyncService.class);

    private final WikiMapClient client;
    private final KbMapStore store;

    public KbMapSyncService(WikiMapClient client, KbMapStore store) {
        this.client = client;
        this.store = store;
    }

    /**
     * 一次同步的结果。
     *
     * @param pages   命名空间里看到的页数
     * @param changed 版本变过（或本地没有）的页数
     * @param parsed  真正解析并写入的页数
     * @param markers 写入的 marker 总数
     * @param failed  解析失败的页数
     * @param dryRun  只比对版本、不取正文不落库
     */
    public record SyncReport(int pages, int changed, int parsed, int markers, int failed,
                             List<String> errors, boolean dryRun, long elapsedMs, Map<String, Integer> byGroup) {
    }

    public SyncReport sync(boolean dryRun) {
        long t0 = System.currentTimeMillis();
        if (!client.isEnabled()) {
            return new SyncReport(0, 0, 0, 0, 0, List.of("地图同步未启用（app.kb.map-sync.enabled=false）"),
                    dryRun, 0, Map.of());
        }
        if (!store.isAvailable()) {
            return new SyncReport(0, 0, 0, 0, 0, List.of("地图存储不可用（问答库没起来）"), dryRun, 0, Map.of());
        }

        List<String> pages = client.listPages();
        if (pages.isEmpty()) {
            return new SyncReport(0, 0, 0, 0, 0, List.of("命名空间里没有页面"), dryRun,
                    System.currentTimeMillis() - t0, Map.of());
        }

        // ① 只取版本号（很轻），比出哪几页变了
        Map<String, WikiMapClient.Meta> remote = client.fetchMeta(pages);
        Map<String, Long> known = store.knownRevisions();
        List<String> changed = new ArrayList<>();
        for (String page : pages) {
            WikiMapClient.Meta m = remote.get(page);
            if (m == null) {
                continue;
            }
            Long local = known.get(page);
            if (local == null || local != m.revid()) {
                changed.add(page);
            }
        }
        log.info("[KB-MAP] 共 {} 页，其中 {} 页版本变过（本地已知 {} 页）", pages.size(), changed.size(), known.size());

        if (dryRun) {
            return new SyncReport(pages.size(), changed.size(), 0, 0, 0, List.of(), true,
                    System.currentTimeMillis() - t0, store.countsByGroup(null));
        }

        // ② 只对变过的页取正文
        int parsed = 0;
        int markers = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        if (!changed.isEmpty()) {
            Map<String, WikiMapClient.Revision> content = client.fetchContent(changed);
            for (String page : changed) {
                WikiMapClient.Revision rev = content.get(page);
                if (rev == null) {
                    failed++;
                    errors.add(page + "：API 没返回内容");
                    store.markPageError(page, KbMapParser.mapOf(page), 0, "API 没返回内容");
                    continue;
                }
                try {
                    KbMapParser.Parsed p = KbMapParser.parse(page, rev.content());
                    int n = store.replacePage(page, rev.revid(), rev.timestamp(), p.meta(),
                            p.groups(), p.markers(), KbMapStore.now());
                    parsed++;
                    markers += n;
                } catch (Exception e) {
                    failed++;
                    errors.add(page + "：" + e.getMessage());
                    store.markPageError(page, KbMapParser.mapOf(page), rev.revid(), e.getMessage());
                }
            }
        }

        long ms = System.currentTimeMillis() - t0;
        log.info("[KB-MAP] 同步完成：解析 {} 页 / 写入 {} 个 marker / 失败 {} 页，耗时 {} ms",
                parsed, markers, failed, ms);
        return new SyncReport(pages.size(), changed.size(), parsed, markers, failed, errors, false, ms,
                store.countsByGroup(null));
    }

    /** 当前库里各分组的 marker 数（语料扩维时按它决定先做哪类） */
    public Map<String, Integer> groupCounts(String map) {
        return new LinkedHashMap<>(store.countsByGroup(map));
    }
}
