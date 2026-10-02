package com.example.qqbot.publicapi;

import com.example.qqbot.kb.KbPolicy;
import com.example.qqbot.kb.PublicSearch;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.KbCorpus;
import com.example.qqbot.kb.KbRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 公开站检索 —— 在 {@link KbRetriever} 外面包一层"公网刹车"。
 *
 * <p><b>为什么需要这一层</b>：群内只有群友能问，公开站是任何人都能打。
 * 直接让公开接口走向量路，等于把外部模型 API 的额度敞开给公网。
 * 但完全不走向量路，中文口语提问（"木头"、"等级上限"）就一条都搜不到，
 * 因为语料是英文的，本地关键词路只认得术语表里那 3,300 个标准名词。
 *
 * <p>所以这一层做三件事：
 * <ol>
 *   <li><b>结果缓存</b>（LRU）—— 同一句话重复搜不再调 API。刷同一个词不花钱</li>
 *   <li><b>按天配额</b> —— 每天最多 N 次走向量，超出后**静默降级**成纯关键词，
 *       搜索照常能用，只是少了语义能力</li>
 *   <li><b>同页去重</b> —— 一个 Wiki 页面会被切成好几块，不去重的话
 *       搜"灵火祭坛"会看到 4 条一模一样的 Flame Altar</li>
 * </ol>
 *
 * <p>开关与配额见 {@code app.kb.public-search.*}，管理端可热改。
 */
@Component
public class PublicSearchService {

    private static final Logger log = LoggerFactory.getLogger(PublicSearchService.class);

    /** 检索模式的对外标识 */
    public static final String MODE_SEMANTIC = "semantic";
    public static final String MODE_KEYWORD = "keyword";

    private final KbRetriever retriever;
    private final KbCorpus corpus;
    private final EmbeddingClient embedding;
    private final KbPolicy props;

    /** LRU 结果缓存。用 LinkedHashMap(accessOrder) 手搓一个就够，没必要引 Caffeine */
    private final Map<String, Cached> cache;

    private final AtomicInteger usedToday = new AtomicInteger();
    private volatile LocalDate day = LocalDate.now();
    private volatile boolean quotaWarned;

    public PublicSearchService(KbRetriever retriever, KbCorpus corpus,
                               EmbeddingClient embedding, KbPolicy props) {
        this.retriever = retriever;
        this.corpus = corpus;
        this.embedding = embedding;
        this.props = props;
        int size = Math.max(16, props.getPublicSearch().getCacheSize());
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > size;
            }
        };
    }

    /** 一次公开检索的结果 */
    public record Outcome(List<KbRetriever.Hit> hits, String mode) {
    }

    private record Cached(KbRetriever.Retrieval retrieval, String mode) {
    }

    /**
     * 公开检索。
     *
     * @param query 用户输入（已 trim，长度由调用方限制）
     * @param limit 最多返回几条（同页去重之后）
     */
    public Outcome search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return new Outcome(List.of(), MODE_KEYWORD);
        }
        PublicSearch cfg = props.getPublicSearch();
        String key = query.toLowerCase(Locale.ROOT).trim();

        Cached hit = get(key);
        if (hit != null) {
            return new Outcome(dedupe(hit.retrieval().hits(), limit), hit.mode());
        }

        boolean semantic = cfg.isVectorEnabled()
                && embedding.isAvailable()
                && corpus.isReady()
                && takeQuota(cfg.getVectorDailyLimit());

        long t0 = System.currentTimeMillis();
        KbRetriever.Retrieval r = retriever.retrieve(query, semantic, cfg.getCandidatePool(), cfg.getMinScore(), false);
        String mode = semantic ? MODE_SEMANTIC : MODE_KEYWORD;

        if (semantic) {
            put(key, new Cached(r, mode));
        }
        List<KbRetriever.Hit> hits = dedupe(r.hits(), limit);
        log.info("[PUBLIC] 检索「{}」→ {} 条（模式={}，候选 {}，耗时 {}ms）",
                query, hits.size(), mode, r.hits().size(), System.currentTimeMillis() - t0);
        return new Outcome(hits, mode);
    }

    /**
     * 同页去重：一个页面切成多块时，只留分最高的那块。
     *
     * <p>入参已按分数降序，所以"先到先得"就是"留最高的"。
     */
    static List<KbRetriever.Hit> dedupe(List<KbRetriever.Hit> hits, int limit) {
        Map<String, KbRetriever.Hit> best = new LinkedHashMap<>();
        for (KbRetriever.Hit h : hits) {
            String title = h.entry().title() == null ? "" : h.entry().title().trim().toLowerCase(Locale.ROOT);
            best.putIfAbsent(title, h);
        }
        List<KbRetriever.Hit> out = new ArrayList<>(best.values());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** 今日用量与剩余（管理端展示，让"花了多少"看得见） */
    public Map<String, Object> stats() {
        PublicSearch cfg = props.getPublicSearch();
        resetIfNewDay();
        int used = usedToday.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vectorEnabled", cfg.isVectorEnabled());
        out.put("dailyLimit", cfg.getVectorDailyLimit());
        out.put("usedToday", used);
        out.put("remainingToday", Math.max(0, cfg.getVectorDailyLimit() - used));
        out.put("cached", cache.size());
        out.put("embeddingReady", embedding.isAvailable());
        return out;
    }

    // ==================== 内部 ====================

    /** 当日配额是否还够（并发安全：CAS 自增，不会超发） */
    private boolean takeQuota(int limit) {
        if (limit <= 0) {
            return false;
        }
        resetIfNewDay();
        while (true) {
            int cur = usedToday.get();
            if (cur >= limit) {
                if (!quotaWarned) {
                    quotaWarned = true;
                    log.warn("[PUBLIC] 今日语义搜索配额已用完（{} 次），后续自动降级为纯关键词检索", limit);
                }
                return false;
            }
            if (usedToday.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    private void resetIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(day)) {
            synchronized (this) {
                if (!today.equals(day)) {
                    day = today;
                    usedToday.set(0);
                    quotaWarned = false;
                    log.info("[PUBLIC] 语义搜索配额已跨天重置");
                }
            }
        }
    }

    /**
     * 让结果缓存失效。
     *
     * <p><b>为什么必须有它</b>：公开站检索走 LRU 结果缓存，而知识库是可以改的
     * （改文本 / 下架块 / 下架词条 / 追加新块）。写入之后如果不清缓存，
     * 同一个查询会一直返回**改动前的旧结果** —— 实测：下架「Scrap Cup」后，
     * 用新查询检索已经查不到它了（墓碑生效），但下架前查过的那条查询
     * 仍然从缓存里把「Scrap Cup」吐出来。
     *
     * <p>由所有会改动知识库的写入路径调用。
     */
    /**
     * 知识库内容变了 → 清掉检索缓存，否则会返回改动前的旧结果。
     *
     * <p>订阅而不是被知识库直接调用：**公开站知道自己有缓存，知识库不需要知道**。
     * 原先那根反向依赖（kb -> publicapi）已按此拆掉。
     */
    @org.springframework.context.event.EventListener
    public void onKnowledgeChanged(com.example.qqbot.kb.KnowledgeChangedEvent event) {
        log.info("[PUBLIC] 知识库变更（{}），清空检索缓存", event.reason());
        invalidate();
    }

    public synchronized void invalidate() {
        cache.clear();
    }

    private synchronized Cached get(String key) {
        return cache.get(key);
    }

    private synchronized void put(String key, Cached value) {
        cache.put(key, value);
    }
}
