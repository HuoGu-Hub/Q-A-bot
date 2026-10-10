package com.example.qqbot.kb;

import com.example.qqbot.trace.KbTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 统计重建的验收 —— 官方管线驱动的检索走这一条。
 *
 * <p><b>为什么必须钉住</b>：切换 {@code ChatService} 之后，检索发生在 augmentor 内部，
 * {@code augment()} 不把结果返回给调用方，命中块与余弦只留在诊断容器里。
 * 没有这条通道，问答统计会<b>静默变空</b> —— 机器人照常回答，后台数据却一片零，
 * 而且没有任何报错。
 */
class KbTraceFromDiagnosticsTest {

    private static KbRetriever retriever() {
        KbPolicy props = mock(KbPolicy.class);
        when(props.isEnabled()).thenReturn(true);
        KbCorpus corpus = mock(KbCorpus.class);
        Glossary glossary = mock(Glossary.class);
        when(glossary.matchChinese(anyString()))
                .thenReturn(List.of(new Glossary.Term("力量", "Strength")));
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        return new KbRetriever(props, corpus, glossary, embedding);
    }

    private static KbRetriever.Hit hit(String id, String title, String text, double score, String source) {
        KbCorpus.Entry e = new KbCorpus.Entry(id, "doc-" + id, title, text, "https://w/" + id,
                List.of(), false, "", new float[]{1f, 0f});
        return new KbRetriever.Hit(e, score, source);
    }

    @Test
    @DisplayName("★ 从诊断容器重建统计：命中块、余弦、术语命中都不丢")
    void rebuildsTraceFromDiagnostics() {
        KbRetrievalDiagnostics d = new KbRetrievalDiagnostics();
        KbRetriever.Hit h = hit("wiki-strength", "力量、Strength", "力量提升生命上限。",
                0.9, "vector+rerank");
        d.recordVector(List.of(h), 0.95);
        d.recordKeyword(List.of());
        d.recordFinal(List.of(h));

        KbTrace t = retriever().traceOf(d, "力量怎么加", 12);

        assertThat(t.enabled()).isTrue();
        assertThat(t.hits()).hasSize(1);
        assertThat(t.hits().get(0).id()).isEqualTo("wiki-strength");
        assertThat(t.hits().get(0).source()).as("来源口径要留住，统计靠它区分哪条路命中").isEqualTo("vector+rerank");
        assertThat(t.bestCosine())
                .as("卡完阈值的最高余弦，取自向量路")
                .isEqualTo(0.9);
        assertThat(t.bestCosineRaw())
                .as("卡阈值之前的最高余弦 —— 只有它能说明是「库里没有」还是「差一点被挡」")
                .isEqualTo(0.95);
        assertThat(t.matchedTerms()).hasSize(1);
        assertThat(t.matchedTerms().get(0).en()).isEqualTo("Strength");
    }

    @Test
    @DisplayName("诊断容器为 null → 返回「未启用」，不崩")
    void nullDiagnosticsYieldsDisabled() {
        assertThat(retriever().traceOf(null, "力量", 5).enabled()).isFalse();
    }
}
