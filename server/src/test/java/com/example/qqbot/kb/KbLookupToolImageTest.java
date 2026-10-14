package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code lookup} 工具的配图验收：命中块的正文要**连同标记一起**交给模型。
 *
 * <p>这是"agent 触发工具 → 消息里发图"的最后一段链：工具结果里有标记，
 * 模型才会把它抄进回答；抄进回答，出站层才会换成图。
 */
class KbLookupToolImageTest {

    private static KbRetriever retrieverWith(KbRetriever.Hit hit) {
        KbRetriever retriever = mock(KbRetriever.class);
        when(retriever.retrieve(anyString()))
                .thenReturn(new KbRetriever.Retrieval(List.of(hit), 0.87, 0.87, 1, 1, List.of()));
        return retriever;
    }

    private static KbRetriever.Hit acidCleaver() {
        return new KbRetriever.Hit(new KbCorpus.Entry("acid-cleaver", "acid-cleaver",
                "酸蚀砍刀（Acid Cleaver）", "一把涂满酸液的砍刀。[[img:kb:acid-cleaver]]",
                "https://example.com/acid", List.of("武器"), false, "2026-01-01", new float[] {1f}),
                0.87, "vector");
    }

    private static String lookup(KbRetriever retriever) {
        KbLookupTool tool = new KbLookupTool(retriever, mock(KbCorpus.class),
                mock(PlaceDirectory.class), mock(MapOptions.class));
        return tool.lookup("酸蚀砍刀", null);
    }

    @Test
    @DisplayName("★ lookup 的输出里带着配图标记，且在块尾")
    void lookupReturnsImageMarker() {
        String out = lookup(retrieverWith(acidCleaver()));

        assertThat(out).contains("酸蚀砍刀（Acid Cleaver）").contains("[[img:kb:acid-cleaver]]");
        assertThat(out).contains("砍刀。\n[[img:kb:acid-cleaver]]");
    }

    @Test
    @DisplayName("模型没有可供照抄的标记时，工具输出里就不该有 image 字样")
    void lookupWithoutMarkerHasNone() {
        KbRetriever.Hit plain = new KbRetriever.Hit(new KbCorpus.Entry("flame-altar-0", "flame-altar-0",
                "灵火祭坛", "灵火祭坛是复活点。", "https://example.com/altar", List.of("据点"), false,
                "2026-01-01", new float[] {1f}), 0.8, "vector");

        assertThat(lookup(retrieverWith(plain))).doesNotContain("[[img:");
    }
}
