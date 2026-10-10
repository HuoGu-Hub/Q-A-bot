package com.example.qqbot.kb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检索结果缓存 —— 同一个问题在群里被反复问时，不必每次都付两次外部调用。
 *
 * <p><b>为什么现在才有</b>：公开站一直有 LRU 缓存与按天配额，群聊路径却是裸的 ——
 * 每一条消息都会触发一次 embedding 加一次 rerank。群里「这个东西怎么合成」这种问题
 * 一天能被问很多遍，重复的部分全是白付的钱和延迟。
 *
 * <p><b>为什么键里带语料版本</b>：缓存必须跟着语料失效。{@link KbCorpus#version()}
 * 每次写成功 +1，所以写完之后旧条目自然不再命中 —— 不需要任何"记得清缓存"的约定。
 * 那种约定这个项目踩过：全项目 12 处手工 reload，漏一处就悄悄用旧语料且不报错。
 *
 * <p><b>为什么键里带检索参数</b>：群内问答与公开站用的是不同阈值与条数
 * （0.45 / 5 条 vs 0.32 / 40 条），参数不同就是不同的问题，不能互相命中。
 *
 * <p>有界 LRU：超过容量按最近最少使用淘汰。缓存的是 {@link KbRetriever.Retrieval}，
 * 里面的条目是对语料快照的引用，不复制正文，所以占用很小。
 */
final class KbRetrievalCache {

    /** 与公开站缓存同量级。做成可配置项是后话，先让它有界且够用。 */
    static final int DEFAULT_SIZE = 512;

    record Key(long corpusVersion, String query, boolean allowEmbedding,
               int topK, double minScore, boolean allowRerank) {
    }

    private final int maxSize;
    private final Map<Key, KbRetriever.Retrieval> entries;

    KbRetrievalCache(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
        this.entries = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, KbRetriever.Retrieval> eldest) {
                return size() > KbRetrievalCache.this.maxSize;
            }
        };
    }

    synchronized KbRetriever.Retrieval get(Key key) {
        return entries.get(key);
    }

    synchronized void put(Key key, KbRetriever.Retrieval value) {
        if (value != null) {
            entries.put(key, value);
        }
    }

    synchronized int size() {
        return entries.size();
    }
}
