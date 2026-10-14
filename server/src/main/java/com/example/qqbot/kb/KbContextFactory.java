package com.example.qqbot.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * 一次检索的共享输入工厂：语料快照、术语展开、问题向量。
 *
 * <h2>为什么要有这个类</h2>
 * 这套建法原来只在 {@link KbRetriever#retrieve} 里。而 {@link KbContextQueryTransformer}
 * 要用<b>同一套</b>逻辑把上下文挂到外部入口造出来的 {@code Query} 上 ——
 * 两处各写一份迟早会漂，漂了就是"某条入口检索永远返回空"。
 *
 * <h2>为什么是静态工具而不是 Bean</h2>
 * 为了让 transformer <b>不依赖 KbRetriever</b>。依赖了就是环：
 * KbRetriever 的构造器要建 augmentor，augmentor 要带 transformer，transformer 又要 KbRetriever。
 * 静态方法只依赖传进来的零件，环就断了。
 */
final class KbContextFactory {

    private static final Logger log = LoggerFactory.getLogger(KbContextFactory.class);

    private KbContextFactory() {
    }

    /**
     * 建一次检索的共享输入。
     *
     * @param k 最终条数；每路候选数按它的 4 倍算（最少 20），留够余量给融合与去重
     */
    static KbRetrievalContext build(String query, boolean allowEmbedding, int k, double minScore,
                                    boolean allowRerank, boolean expandSiblings, KbCorpus corpus,
                                    Glossary glossary, EmbeddingClient embedding,
                                    KbRetrievalDiagnostics diagnostics) {
        int pool = Math.max(k * 4, 20);
        // 语料只取一次快照，交给两条路共用：一次检索内看到的世界必须是一致的（写入是另一个线程）
        List<KbCorpus.Entry> entries = tryGet(corpus::entries, List.of(), "读取语料");
        // 术语展开只做一次，喂给关键词路和标题加成共用（matchChinese 内部要排序几千条，别重复做）
        List<String> terms = tryGet(() -> glossary.expand(query), List.of(), "术语展开");
        // ★ 问题向量只算一次，A 路和 C 路共用：
        //   一次检索最多只该有一次外部 embedding 调用（省的是钱，也是 200~300ms 延迟）
        float[] qv = allowEmbedding ? safeVector(query, corpus, embedding) : null;
        return new KbRetrievalContext(query, entries, terms, qv, k, pool, minScore, allowRerank,
                expandSiblings, diagnostics);
    }

    /**
     * 把问题向量化 —— <b>绝不抛异常</b>。
     *
     * <p>拿不到向量就返回 {@code null}，A 路和 C 路一起跳过（B 路是纯本地的，照常跑）。
     */
    static float[] safeVector(String query, KbCorpus corpus, EmbeddingClient embedding) {
        try {
            if (!embedding.isAvailable() || !corpus.isReady()) {
                return null;
            }
            float[] qv = embedding.embedOne(query);
            if (qv == null || qv.length != corpus.dimensions()) {
                return null;
            }
            return qv;
        } catch (Exception e) {
            log.warn("[KB] 问题向量化失败（A/C 路跳过，B 路照常）：{}", e.getMessage());
            return null;
        }
    }

    private static <T> T tryGet(Supplier<T> supplier, T fallback, String what) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("[KB] {}失败：{}", what, e.getMessage());
            return fallback;
        }
    }
}
