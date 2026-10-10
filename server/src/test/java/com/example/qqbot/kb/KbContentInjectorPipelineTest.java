package com.example.qqbot.kb;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资料注入在<b>官方管线里</b>的验收。
 *
 * <p>{@link KbContentInjectorTest} 只验注入器本身；这一条验的是
 * "官方 {@link DefaultRetrievalAugmentor} 真的会调它、并且产出的消息带着契约"。
 * 两者是不同的风险：注入器写对了但没接进管线，一样是静默失效。
 */
class KbContentInjectorPipelineTest {

    private static KbRetriever.Hit hit(String id, String title, String text, boolean curated, String at) {
        KbCorpus.Entry e = new KbCorpus.Entry(id, "doc-" + id, title, text, "https://w/" + id,
                List.of(), curated, at, null);
        return new KbRetriever.Hit(e, 0.9, "vector");
    }

    @Test
    @DisplayName("★ 官方 augmentor 走完一轮：注入后的用户消息带着 <<<KNOWLEDGE>>> 与两段出处")
    void pipelineProducesKnowledgeContract() {
        // 检索器与聚合器都换成桩：这一条要测的是注入那一格，不是检索
        ContentRetriever stubRetriever = query -> List.of(
                KbContents.of(hit("ours", "灵火祭坛", "我们改过的正文", true, "2026-10-01")),
                KbContents.of(hit("imported", "酸性菌丝", "导入的正文", false, "2026-09-20")));
        ContentAggregator passThrough = map -> map.values().stream()
                .flatMap(Collection::stream)   // 各检索器返回的 List<Content>
                .flatMap(Collection::stream)   // 摊平成 Content
                .toList();

        RetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .queryRouter(new DefaultQueryRouter(stubRetriever))
                .contentAggregator(passThrough)
                .contentInjector(new KbContentInjector(new TwoSectionInjector()))
                .build();

        // 与生产同构：元数据由 KbRetrievalContext 组装，不自己拼
        // 注意 Query.from(String) 造出来的元数据是 null，而 AugmentationRequest 拒绝 null
        KbRetrievalContext ctx = new KbRetrievalContext(
                "灵火祭坛在哪", List.of(), List.of(), null, 5, 20, 0.45, false, null);
        AugmentationResult result = augmentor.augment(
                new AugmentationRequest(UserMessage.from("灵火祭坛在哪"), ctx.toQuery().metadata()));

        String text = ((UserMessage) result.chatMessage()).singleText();
        assertThat(text).as("用户原话必须还在").startsWith("灵火祭坛在哪");
        assertThat(text).contains("<<<KNOWLEDGE").contains("KNOWLEDGE>>>");
        assertThat(text).contains("【我们维护的内容】").contains("【导入的资料");
        assertThat(text.indexOf("【我们维护的内容】")).isLessThan(text.indexOf("【导入的资料"));
    }
}
