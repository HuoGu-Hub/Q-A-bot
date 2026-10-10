package com.example.qqbot.kb;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一次检索的共享上下文 —— 走官方管线的 <b>正式</b>通道传给各个检索器。
 *
 * <p><b>为什么要它</b>：官方 {@code ContentRetriever.retrieve(Query)} 只给一个问题文本，
 * 而我们的检索需要更多：语料快照、术语展开、已经算好的问题向量。
 * 早先这些靠"编排层持有、按参数传"解决；一旦编排交给官方管线，检索器就是**独立的**组件，
 * 必须有地方读这些输入。
 *
 * <p><b>为什么不是 ThreadLocal</b>：官方本来就有地方放 ——
 * {@code InvocationParameters} 是个 {@code Map<String, Object>}，
 * 通过 {@code InvocationContext} 挂在 {@code Query.metadata()} 上。
 * 用它意味着"上下文"是显式传递的、跟着这次调用走，而不是藏在某个线程的暗格里。
 *
 * <p>这些东西必须"一次检索内只算一遍"：语料快照决定所有路径看到的是同一个世界；
 * 问题向量决定 A 与 C 看的是同一个坐标系，而且一次检索只该有一次 embedding 调用。
 */
public final class KbRetrievalContext {

    /** 在 InvocationParameters 里的键 */
    static final String KEY = "kb.retrieval.context";

    private final String query;
    private final List<KbCorpus.Entry> entries;
    private final List<String> terms;
    /** 为 {@code null} 表示这次不走向量（A 路与 C 路一起跳过） */
    private final float[] queryVector;
    private final int topK;
    private final int pool;
    private final double minScore;
    private final boolean allowRerank;
    private final KbRetrievalDiagnostics diagnostics;

    /** id → 条目。官方 Content 只带得动块 id，聚合器要靠它还原条目，所以按需建一次 */
    private volatile Map<String, KbCorpus.Entry> byId;

    public KbRetrievalContext(String query, List<KbCorpus.Entry> entries, List<String> terms,
                              float[] queryVector, int topK, int pool, double minScore, boolean allowRerank,
                              KbRetrievalDiagnostics diagnostics) {
        this.query = query;
        this.entries = entries == null ? List.of() : entries;
        this.terms = terms == null ? List.of() : terms;
        this.queryVector = queryVector;
        this.topK = Math.max(1, topK);
        this.pool = pool;
        this.minScore = minScore;
        this.allowRerank = allowRerank;
        this.diagnostics = diagnostics == null ? new KbRetrievalDiagnostics() : diagnostics;
    }

    public String query() {
        return query;
    }

    public List<KbCorpus.Entry> entries() {
        return entries;
    }

    public List<String> terms() {
        return terms;
    }

    public float[] queryVector() {
        return queryVector;
    }

    /** 最终要几条 */
    public int topK() {
        return topK;
    }

    public int pool() {
        return pool;
    }

    /** 按块 id 找回条目；找不到返回 {@code null} */
    public KbCorpus.Entry entryOf(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        Map<String, KbCorpus.Entry> map = byId;
        if (map == null) {
            Map<String, KbCorpus.Entry> built = new HashMap<>(entries.size() * 2);
            for (KbCorpus.Entry e : entries) {
                if (e.id() != null) {
                    built.put(e.id(), e);
                }
            }
            map = built;
            byId = built;
        }
        return map.get(id);
    }

    public double minScore() {
        return minScore;
    }

    public boolean allowRerank() {
        return allowRerank;
    }

    public KbRetrievalDiagnostics diagnostics() {
        return diagnostics;
    }

    /** 组一个只带本上下文的官方 {@link Query} */
    Query toQuery() {
        InvocationParameters params = InvocationParameters.from(KEY, this);
        InvocationContext invocation = InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .timestampNow()
                .invocationParameters(params)
                .build();
        Metadata metadata = Metadata.builder()
                .chatMessage(UserMessage.from(query))
                .invocationContext(invocation)
                .build();
        return Query.from(query, metadata);
    }

    /** 从官方 {@link Query} 取回上下文；取不到返回 {@code null}（由调用方决定怎么处理） */
    public static KbRetrievalContext of(Query query) {
        if (query == null || query.metadata() == null) {
            return null;
        }
        try {
            InvocationParameters params = query.metadata().invocationParameters();
            return params == null ? null : params.get(KEY);
        } catch (Exception e) {
            return null;
        }
    }
}
