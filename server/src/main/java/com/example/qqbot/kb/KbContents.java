package com.example.qqbot.kb;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;

import java.util.List;
import java.util.Map;

/**
 * 官方 {@link Content} 与我们内部 {@link KbRetriever.Hit} 之间的转换 —— 只此一处。
 *
 * <p><b>为什么元数据要写这么多</b>：官方管线的货币是 {@code Content}，
 * 它只带一个 {@code TextSegment}（正文 + Metadata）和一个
 * {@code Map<ContentMetadata, Object>}，而 {@code ContentMetadata} 是个<b>枚举</b>
 * —— 只有 SCORE / RERANKED_SCORE / EMBEDDING_ID 三个键，装不下"这一条是哪条路找到的"。
 * 所以来源、块 id 这些只能写进 {@code TextSegment} 的 Metadata（它接受任意字符串键）。
 *
 * <p>块 id 是必须的：从 {@code Content} 回到我们的条目只能靠它，
 * 而条目里还有标题、出处、是否人工维护这些下游要用的东西。
 */
public final class KbContents {

    static final String META_ID = "kb_id";
    static final String META_SOURCE = "kb_source";
    static final String META_DOC_ID = "kb_doc_id";
    static final String META_TITLE = "kb_title";
    static final String META_URL = "kb_url";
    /**
     * {@code "1"} / {@code "0"} —— Metadata 没有布尔类型。
     *
     * <p>刻意存<b>字符串</b>而不是整数：{@code Metadata.getString()} 遇到 Integer 会抛类型错，
     * 而这个值要在 {@link #toHit} 里原样读回来。
     */
    static final String META_CURATED = "kb_curated";
    static final String META_CONTENT_AT = "kb_content_at";

    private KbContents() {
    }

    static Metadata metadataOf(KbCorpus.Entry e, String source) {
        return new Metadata(Map.of(
                META_ID, e.id() == null ? "" : e.id(),
                META_SOURCE, source == null ? "" : source,
                META_DOC_ID, e.docId() == null ? "" : e.docId(),
                META_TITLE, e.title() == null ? "" : e.title(),
                META_URL, e.url() == null ? "" : e.url(),
                META_CURATED, e.curated() ? "1" : "0",
                META_CONTENT_AT, e.contentAt() == null ? "" : e.contentAt()));
    }

    static TextSegment segmentOf(KbCorpus.Entry e, String source) {
        return TextSegment.from(e.text() == null ? "" : e.text(), metadataOf(e, source));
    }

    /**
     * 一条命中 → 官方 Content；分数放进 {@link ContentMetadata#SCORE}。
     *
     * <p>与 {@link #toHit} 一起构成本模块的对外边界：官方管线的货币是 {@code Content}，
     * 而我们的条目带着出处与时效。跨包需要这条转换的地方一律走这里，
     * 不要各自拼元数据键 —— 拼错不会报错，只会静默丢掉出处。
     */
    public static Content of(KbRetriever.Hit hit) {
        return Content.from(segmentOf(hit.entry(), hit.source()),
                Map.of(ContentMetadata.SCORE, hit.score()));
    }

    /**
     * 官方 {@link Content} → 我们的 {@link KbRetriever.Hit} —— {@link #of} 的逆运算。
     *
     * <p><b>为什么需要它</b>：官方管线的末端是 {@code ContentInjector}，
     * 而我们要按"我们自己维护的 / 导入的"分两段渲染 —— 那个判断读的是
     * {@code Entry.curated()} 与 {@code contentAt()}，都在条目上，不在 {@code Content} 上。
     * 所以注入器必须先把 {@code Content} 还原成条目。
     *
     * <p>还原得出标题、出处、时间这些<b>渲染要用的</b>字段；向量不参与渲染，
     * 也不在元数据里，给 {@code null}。
     */
    static KbRetriever.Hit toHit(Content content) {
        TextSegment seg = content.textSegment();
        Metadata m = seg.metadata();
        KbCorpus.Entry entry = new KbCorpus.Entry(
                str(m, META_ID),
                str(m, META_DOC_ID),
                str(m, META_TITLE),
                seg.text() == null ? "" : seg.text(),
                str(m, META_URL),
                List.of(),
                "1".equals(str(m, META_CURATED)),
                str(m, META_CONTENT_AT),
                null);
        Object score = content.metadata() == null ? null : content.metadata().get(ContentMetadata.SCORE);
        return new KbRetriever.Hit(entry, score instanceof Number num ? num.doubleValue() : 0.0,
                str(m, META_SOURCE));
    }

    private static String str(Metadata m, String key) {
        try {
            String v = m.getString(key);
            return v == null ? "" : v;
        } catch (Exception e) {
            // 元数据缺失或类型不符都不该让整条检索炸掉 —— 渲染少一个字段而已
            return "";
        }
    }
}
