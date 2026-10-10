package com.example.qqbot.kb;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 路：术语表关键词召回，实现官方的 {@link ContentRetriever}。
 *
 * <p>用术语表把中文问题里的游戏名词翻成英文，再按词频给块打分。打分刻意简单：
 * 命中标题 3 分，正文里每出现一次 1 分（最多 3 次）。它不追求"语义相似"，
 * 只追求"这个词确实在这页里" —— 这正是向量检索的短板。
 *
 * <p>术语展开与语料快照都由 {@link KbRetrievalContext} 带进来：
 * 展开结果同时被标题加成用（在聚合器里），不该算两遍。
 */
@Component("keywordContentRetriever")
public class KeywordContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(KeywordContentRetriever.class);

    /** 关键词检索里，命中标题的权重（标题比正文重要得多） */
    private static final double TITLE_WEIGHT = 3.0;

    private final KbCorpus corpus;

    public KeywordContentRetriever(KbCorpus corpus) {
        this.corpus = corpus;
    }

    @Override
    public List<Content> retrieve(Query query) {
        KbRetrievalContext ctx = KbRetrievalContext.of(query);
        if (ctx == null) {
            return List.of();
        }
        List<KbCorpus.Entry> entries = ctx.entries();
        List<String> terms = ctx.terms();
        if (!corpus.isReady() || entries.isEmpty() || terms.isEmpty()) {
            ctx.diagnostics().recordKeyword(List.of());
            return List.of();
        }

        List<KbRetriever.Hit> scored = new ArrayList<>();
        for (KbCorpus.Entry e : entries) {
            String title = e.title() == null ? "" : e.title().toLowerCase();
            String text = e.text() == null ? "" : e.text().toLowerCase();
            double score = 0;
            for (String term : terms) {
                if (containsTerm(title, term)) {
                    score += TITLE_WEIGHT;
                }
                score += Math.min(3, countOccurrences(text, term));
            }
            if (score > 0) {
                scored.add(new KbRetriever.Hit(e, score, "keyword"));
            }
        }
        scored.sort(Comparator.comparingDouble(KbRetriever.Hit::score).reversed());

        List<KbRetriever.Hit> out = scored.size() > ctx.pool() ? scored.subList(0, ctx.pool()) : scored;
        ctx.diagnostics().recordKeyword(out);
        if (!out.isEmpty()) {
            log.info("[KB] B 路命中 {} 块（查询词：{}），最高分 {}",
                    out.size(), String.join(",", terms), out.get(0).score());
        }
        return out.stream().map(KbContents::of).toList();
    }

    /**
     * 词条是否出现在文本里。
     *
     * <p>英文用<b>词边界</b>匹配：否则 "Cat" 会命中 "Category"、"Yak" 会命中 "Yaksomething"，
     * 关键词路立刻被噪音淹没。中文没有词边界概念，用子串即可。
     */
    static boolean containsTerm(String haystack, String term) {
        String t = term.toLowerCase();
        if (t.isEmpty()) {
            return false;
        }
        if (t.chars().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)) {
            return haystack.contains(t);
        }
        return Pattern.compile("\\b" + Pattern.quote(t) + "\\b").matcher(haystack).find();
    }

    static int countOccurrences(String haystack, String term) {
        String t = term.toLowerCase();
        if (t.isEmpty()) {
            return 0;
        }
        if (t.chars().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)) {
            int count = 0;
            int at = haystack.indexOf(t);
            while (at >= 0 && count < 5) {
                count++;
                at = haystack.indexOf(t, at + t.length());
            }
            return count;
        }
        Matcher m = Pattern.compile("\\b" + Pattern.quote(t) + "\\b").matcher(haystack);
        int count = 0;
        while (m.find() && count < 5) {
            count++;
        }
        return count;
    }
}
