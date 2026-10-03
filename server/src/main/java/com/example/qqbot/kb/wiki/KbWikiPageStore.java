package com.example.qqbot.kb.wiki;

import com.example.qqbot.persistence.KbWikiPageRepository;
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
 * wiki 文章的同步状态 —— **增量导入**的判据。
 *
 * <h2>为什么必须有这张表</h2>
 * 任务 + 机制合计约 170 篇。每次都全量重新清洗、重新向量化，
 * 既慢又白花钱（embedding 是按量计费的）。存下每篇的 `revid`，
 * 版本没变就一个字节都不动 —— 和地图同步是同一个思路。
 *
 * <p>同时记 `block_id` 与 `block_count`：这样"这篇文章对应哪些块"是可查的，
 * 页面在 wiki 上被删掉时能把对应块一起清掉。
 *
 * <p><b>2026-10-02：一页可能对应多块</b> —— 超长正文改为切块（见 {@code KbTextChunker}），
 * 块 id 约定 {@code <block_id>}、{@code <block_id>-2}、{@code <block_id>-3}…。
 * `block_count` 就是为"页面被改短时要清掉上次多出来的块"而存的 ——
 * 没有它就会留下永久孤儿块。
 */
@Component
public class KbWikiPageStore {

    private static final Logger log = LoggerFactory.getLogger(KbWikiPageStore.class);

    /** 数据访问全部委托给它 —— 本类只管"增量判据"的语义 */
    private final KbWikiPageRepository repo;
    private volatile boolean available;

    public KbWikiPageStore(KbWikiPageRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void init() {
        if (!repo.isAvailable()) {
            log.warn("[KB-WIKI] 问答库不可用，wiki 文章状态存储一并关闭");
            available = false;
            return;
        }
        try {
            repo.initSchema();
            available = true;
            log.info("[KB-WIKI] 文章状态就绪：{} 篇", repo.count());
        } catch (Exception e) {
            log.warn("[KB-WIKI] 初始化失败：{}", e.getMessage());
            available = false;
        }
    }

    public boolean isAvailable() {
        return available && repo.isAvailable();
    }

    /** 已知的 page -> revid */
    public Map<String, Long> knownRevisions() {
        return available ? repo.knownRevisions() : Map.of();
    }

    public List<KbWikiPageRepository.Page> pages() {
        return available ? repo.pages() : List.of();
    }

    public void upsert(String page, String source, long revid, String pageUpdatedAt, int chars,
                       String blockId, int blockCount) {
        if (!available) {
            return;
        }
        repo.upsert(page, source, revid, pageUpdatedAt, Instant.now().toString(), chars, blockId, blockCount);
    }

    /** 这页上一次切了几块（0 = 没记过）。用于清掉本次多出来的块 */
    public int blockCount(String page) {
        return available ? repo.blockCountOf(page) : 0;
    }

    /** 记一次失败；**不动 revid**（动了会把这页永久跳过，与地图同步同一条教训） */
    public void markError(String page, String source, long revid, String message) {
        if (!available) {
            return;
        }
        repo.markError(page, source, revid, Instant.now().toString(),
                message == null ? "" : message.substring(0, Math.min(300, message.length())));
    }

    /** 某个来源下的全部页面（用于按来源回滚） */
    public List<String> pagesOfSource(String source) {
        return available ? repo.pagesOfSource(source) : List.of();
    }

    public int count() {
        return available ? repo.count() : 0;
    }
}
