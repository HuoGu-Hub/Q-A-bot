package com.example.qqbot.kb;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A 路：正文向量召回，实现官方的 {@link ContentRetriever}。
 *
 * <p>问题向量<b>不由这里算</b> —— 它放在 {@link KbRetrievalContext} 里由编排层算一次：
 * 一次检索只该有一次 embedding 调用，而且 C 路必须和 A 路看同一个坐标系。
 * 这也是没有直接用官方 {@code EmbeddingStoreContentRetriever} 的原因：
 * 它内部必然自己 embed，换了它就得每轮多一次外部调用。
 *
 * <p>向量检索本身走官方 {@link dev.langchain4j.store.embedding.EmbeddingStore}
 * （{@link CorpusEmbeddingStore}）。
 */
@Component("vectorContentRetriever")
public class VectorContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(VectorContentRetriever.class);

    private final KbCorpus corpus;

    public VectorContentRetriever(KbCorpus corpus) {
        this.corpus = corpus;
    }

    @Override
    public List<Content> retrieve(Query query) {
        KbRetrievalContext ctx = KbRetrievalContext.of(query);
        if (ctx == null) {
            return List.of();
        }
        List<KbCorpus.Entry> entries = ctx.entries();
        float[] qv = ctx.queryVector();
        if (!corpus.isReady() || entries.isEmpty() || qv == null) {
            ctx.diagnostics().recordVector(List.of(), 0.0);
            return List.of();
        }

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(qv))
                .maxResults(ctx.pool())
                .minScore(ctx.minScore())
                .build();

        // 按本次检索的快照构造视图：官方接口的 search 没有传语料的位置，
        // 而"一次检索内所有路径看到同一份语料"是硬要求。
        CorpusEmbeddingStore.Outcome outcome =
                new CorpusEmbeddingStore(entries).searchWithDiagnostics(request);

        List<KbRetriever.Hit> hits = new ArrayList<>(outcome.found().size());
        for (CorpusEmbeddingStore.Found f : outcome.found()) {
            hits.add(new KbRetriever.Hit(f.entry(), f.score(), "vector"));
        }
        ctx.diagnostics().recordVector(hits, outcome.rawBestScore());

        if (hits.isEmpty()) {
            log.debug("[KB] A 路 0 块过阈值（最高相似度 {} < {}）",
                    String.format("%.3f", outcome.rawBestScore()), String.format("%.3f", ctx.minScore()));
        } else {
            log.info("[KB] A 路命中 {} 块，最高相似度 {}", hits.size(), String.format("%.3f", hits.get(0).score()));
        }
        return hits.stream().map(KbContents::of).toList();
    }
}
