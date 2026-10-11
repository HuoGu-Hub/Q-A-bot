package com.example.qqbot.kb;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 融合 + 闸门置顶 + 重排 + 意图提权 —— 官方管线里那一格 {@link ContentAggregator}。
 *
 * <p><b>为什么这四件事必须在同一个聚合器里</b>：官方管线只有一个聚合器槽位，
 * 而它们有严格的先后顺序（实测调出来的，见 {@link KbRetriever} 的类注释）：
 * 先按排名融合 → 闸门置顶 → 重排（要有翻盘空间就必须留着多于 top-k 的候选）
 * → 聚合意图提权 → 最后才截断。任何一步提前截断都会让后面的步骤失效
 * （「空洞大厅有什么」的地点块实测排第 7，截到 5 就没了）。
 *
 * <p>它把活分给三个协作者：{@link RrfFuser}、{@link TitleVectorGate}、{@link CrossEncoderReranker}，
 * 自己只负责顺序与"官方 Content ↔ 内部 Hit"的转换。
 *
 * <p>这三个协作者曾经各配一个只有唯一实现的接口（KbFuser / KbTitleGateSource / KbReranker）。
 * 接口没有带来可替换性 —— 没有第二个实现，也没有任何测试替身 ——
 * 只多了一层跳转与两处签名维护，所以按 YAGNI 收敛成具体类；
 * 各自的契约写在实现类的类注释里，那才是它真正被使用的地方。
 */
@Component
public class KbContentAggregator implements ContentAggregator {

    private final KbPolicy props;
    private final RrfFuser fuser;
    private final TitleVectorGate titleGateSource;
    private final CrossEncoderReranker reranker;

    public KbContentAggregator(KbPolicy props, RrfFuser fuser,
                               TitleVectorGate titleGateSource, CrossEncoderReranker reranker) {
        this.props = props;
        this.fuser = fuser;
        this.titleGateSource = titleGateSource;
        this.reranker = reranker;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> contentsByQuery) {
        if (contentsByQuery == null || contentsByQuery.isEmpty()) {
            return List.of();
        }
        Map.Entry<Query, Collection<List<Content>>> entry = contentsByQuery.entrySet().iterator().next();
        KbRetrievalContext ctx = KbRetrievalContext.of(entry.getKey());
        if (ctx == null) {
            return List.of();
        }

        // 各路回来的 Content 还原成内部 Hit。按**来源元数据**区分而不是按顺序 ——
        // 顺序取决于路由器怎么排，来源是内容自己带的，后者不会因为接线变化而错。
        List<KbRetriever.Hit> byVector = new ArrayList<>();
        List<KbRetriever.Hit> byKeyword = new ArrayList<>();
        for (List<Content> oneRetriever : entry.getValue()) {
            for (Content c : oneRetriever) {
                KbRetriever.Hit hit = toHit(ctx, c);
                if (hit == null) {
                    continue;
                }
                if ("vector".equals(hit.source())) {
                    byVector.add(hit);
                } else {
                    byKeyword.add(hit);
                }
            }
        }

        // C 路：标题向量闸门。命中就把那一条置顶（不参与 RRF 投票）
        KbRetriever.TitleGate gate = ctx.queryVector() == null
                ? KbRetriever.TitleGate.NONE
                : titleGateSource.check(ctx, props.getTitleGate());
        ctx.diagnostics().recordGate(gate);

        int k = ctx.topK();
        boolean doRerank = ctx.allowRerank() && reranker.isAvailable();
        // 要重排就必须融合出多于 top-k 的候选，否则重排没有翻盘空间
        int fuseLimit = doRerank ? Math.max(k, Math.max(1, props.getRerank().getCandidateLimit())) : k;

        List<KbRetriever.Hit> fused = fuser.fuse(byVector, byKeyword, fuseLimit, ctx.terms());
        fused = fuser.pin(fused, gate, fuseLimit);
        if (doRerank) {
            fused = reranker.rerank(ctx.query(), fused, fuseLimit, gate.hit());
        }
        // 先提权、后截断 —— 反过来的话地点块早就被截掉了
        fused = KbRetriever.applyPlaceIntent(fused, ctx.query());
        if (fused.size() > k) {
            fused = fused.subList(0, k);
        }

        ctx.diagnostics().recordFinal(fused);
        return fused.stream().map(KbContents::of).toList();
    }

    /** 官方 Content → 内部 Hit；没有 kb_id 的说明不是我们的内容，跳过 */
    private static KbRetriever.Hit toHit(KbRetrievalContext ctx, Content content) {
        TextSegment segment = content.textSegment();
        if (segment == null || segment.metadata() == null) {
            return null;
        }
        KbCorpus.Entry entry = ctx.entryOf(segment.metadata().getString(KbContents.META_ID));
        if (entry == null) {
            return null;
        }
        String source = segment.metadata().getString(KbContents.META_SOURCE);
        Object raw = content.metadata() == null ? null : content.metadata().get(ContentMetadata.SCORE);
        double score = raw instanceof Number n ? n.doubleValue() : 0.0;
        return new KbRetriever.Hit(entry, score, source == null || source.isEmpty() ? "vector" : source);
    }
}
