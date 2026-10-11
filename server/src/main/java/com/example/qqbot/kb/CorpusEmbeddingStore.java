package com.example.qqbot.kb;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 语料快照的向量视图 —— 实现官方 {@link EmbeddingStore} 接口。
 *
 * <p><b>为什么要实现官方接口</b>：官方 RAG 管线的检索那一格要的就是
 * {@code EmbeddingStore&lt;TextSegment&gt;}（{@code EmbeddingStoreContentRetriever} 消费它）。
 * A 路先落到这个接口上，下一轮把编排换成 {@code DefaultRetrievalAugmentor} 时，
 * 换的才是"某一格"而不是又一段自研代码。
 *
 * <p><b>为什么按快照构造，而不是做成单例 Bean</b>：官方接口的
 * {@link #search(EmbeddingSearchRequest)} 没有传语料的位置，做成单例就只能在每次检索里
 * 现读 {@code corpus.entries()} —— 那等于把"一次检索内所有路径看到同一份语料"这条保证
 * 悄悄丢掉。这个视图只包一层引用，不复制数据，构造本身不花钱。
 *
 * <p><b>只读</b>：语料的增删改由存储层负责（{@code KbBlockStore} 写完自己涨版本），
 * 这里是一个查询视图，写入一律不支持 —— 与其静默丢弃，不如当场报错。
 */
public class CorpusEmbeddingStore implements EmbeddingStore<TextSegment> {

    /** 扫描中的一条（还没有包装成 TextSegment，省掉落选者的分配） */
    private record Scored(KbCorpus.Entry entry, double score) {
    }

    /**
     * 一条命中：既给出官方形状的 {@link EmbeddingMatch}，也给出我们的条目。
     *
     * <p>带上条目是为了免掉一次 id → 条目的查表：扫描时手里就有它。
     */
    public record Found(KbCorpus.Entry entry, double score, EmbeddingMatch<TextSegment> match) {
    }

    /**
     * 一次扫描的完整结果。
     *
     * @param found       过了阈值、且不超过 maxResults 的命中
     * @param rawBestScore <b>卡阈值之前</b>的最高分。卡完再取的话未命中都只能是 0，
     *                     就分不出"库里没有"和"差一点被挡"了 —— 而调阈值全靠这个区分
     */
    public record Outcome(List<Found> found, double rawBestScore) {
    }

    private final List<KbCorpus.Entry> entries;

    public CorpusEmbeddingStore(List<KbCorpus.Entry> entries) {
        this.entries = entries == null ? List.of() : entries;
    }

    /**
     * 一次扫描，同时给出命中与诊断分。
     *
     * <p>{@link #search(EmbeddingSearchRequest)} 就是在它之上丢掉诊断分得到的 ——
     * 官方方法必须有，但诊断分只有我们这一路要，所以多留一个入口，
     * 而不是让调用方扫两遍。
     */
    public Outcome searchWithDiagnostics(EmbeddingSearchRequest request) {
        float[] qv = request.queryEmbedding().vector();

        List<Scored> scored = new ArrayList<>(entries.size());
        for (KbCorpus.Entry e : entries) {
            scored.add(new Scored(e, KbVectors.cosine(qv, e.vector())));
        }
        if (scored.isEmpty()) {
            return new Outcome(List.of(), 0.0);
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        double rawBest = scored.get(0).score();

        double min = request.minScore();
        List<Found> out = new ArrayList<>();
        for (Scored s : scored) {
            if (s.score() < min || out.size() >= request.maxResults()) {
                break;
            }
            out.add(toFound(s));
        }
        return new Outcome(out, rawBest);
    }

    /** 官方检索入口 —— 与 {@link #searchWithDiagnostics} 同一次扫描的语义，只是不要诊断分 */
    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        List<EmbeddingMatch<TextSegment>> matches = searchWithDiagnostics(request).found().stream()
                .map(Found::match)
                .toList();
        return new EmbeddingSearchResult<>(matches);
    }

    /** 只给落选者之外的那几条构造 TextSegment —— 语料三千多条，不该为没入选的付分配 */
    private static Found toFound(Scored s) {
        KbCorpus.Entry e = s.entry();
        // 元数据只由 KbContents 定义一处：两份键名迟早会漂移，
        // 而漂移的表现是"某一路的来源标不出来"，不报错。
        TextSegment segment = KbContents.segmentOf(e, "vector");
        EmbeddingMatch<TextSegment> match =
                new EmbeddingMatch<>(s.score(), e.id().value(), Embedding.from(e.vector()), segment);
        return new Found(e, s.score(), match);
    }

    // ==================== 只读视图：写入一律不支持 ====================

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException(
                "CorpusEmbeddingStore 是语料快照的只读视图：写入请走 KbBlockStore（写完它自己涨版本，索引据此重载）");
    }

    @Override
    public String add(Embedding embedding) {
        throw readOnly();
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw readOnly();
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        throw readOnly();
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw readOnly();
    }
}
