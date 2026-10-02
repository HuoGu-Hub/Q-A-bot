package com.example.qqbot.publicapi;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.KbRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 公开站检索的"公网刹车"验收测试。
 *
 * <p>重点不是"搜得准不准"（那是 {@link com.example.qqbot.kb.KbRetriever} 的事），
 * 而是三件保命的事：**同页去重、配额用完能降级、重复搜索不重复花钱**。
 */
class PublicSearchServiceTest {

    private KbRetriever retriever;
    private com.example.qqbot.kb.KbCorpus corpus;
    private EmbeddingClient embedding;
    private KbProperties props;
    private PublicSearchService service;

    private static final float[] V = {1f, 0f};
    private final com.example.qqbot.kb.KbCorpus.Entry altarA = new com.example.qqbot.kb.KbCorpus.Entry(
            "altar-0", "Flame Altar", "Place this Flame Altar above the Shroud",
            "https://w/Flame_Altar", List.of("Essentials"), false, "", V);
    private final com.example.qqbot.kb.KbCorpus.Entry altarB = new com.example.qqbot.kb.KbCorpus.Entry(
            "altar-1", "Flame Altar", "Strengthen the Flame",
            "https://w/Flame_Altar", List.of("Essentials"), false, "", V);
    private final com.example.qqbot.kb.KbCorpus.Entry scrapCup = new com.example.qqbot.kb.KbCorpus.Entry(
            "scrap-cup-0", "Scrap Cup", "Ingredients: Metal Scraps",
            "https://w/Scrap_Cup", List.of("Tableware"), false, "", V);

    @BeforeEach
    void setUp() {
        retriever = mock(KbRetriever.class);
        corpus = mock(com.example.qqbot.kb.KbCorpus.class);
        embedding = mock(EmbeddingClient.class);
        props = new KbProperties();
        service = new PublicSearchService(retriever, corpus, embedding, props);

        when(corpus.isReady()).thenReturn(true);
        when(embedding.isAvailable()).thenReturn(true);
    }

    private void answer(List<KbRetriever.Hit> hits) {
        when(retriever.retrieve(anyString(), anyBoolean(), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(new KbRetriever.Retrieval(hits, 0.6, 0.6, hits.size(), 0));
        // 注意 5 个参数：最后一个是 allowRerank。公开站**明确传 false** ——
        // 它有自己的按天配额与 LRU 缓存，"要不要给资料"的重排判断在这里没有意义，
        // 多一次外部调用只是白花钱。下面几处 verify 都用 eq(false) 把这条锁住。
    }

    @Test
    @DisplayName("★ 同页去重：一个页面的多块只留分最高的那条")
    void dedupesSameTitle() {
        answer(List.of(
                new KbRetriever.Hit(altarA, 0.9, "vector"),
                new KbRetriever.Hit(altarB, 0.5, "vector"),
                new KbRetriever.Hit(scrapCup, 0.3, "keyword")));

        PublicSearchService.Outcome out = service.search("灵火祭坛", 20);

        assertThat(out.hits()).hasSize(2);
        assertThat(out.hits().get(0).entry().url()).isEqualTo("https://w/Flame_Altar");
        assertThat(out.hits().get(0).entry().id()).as("留的是分高的那块").isEqualTo("altar-0");
        assertThat(out.hits().get(1).entry().title()).isEqualTo("Scrap Cup");
    }

    @Test
    @DisplayName("默认走向量路，模式标为 semantic")
    void usesVectorByDefault() {
        answer(List.of(new KbRetriever.Hit(scrapCup, 0.6, "vector")));

        PublicSearchService.Outcome out = service.search("废料杯", 20);

        assertThat(out.mode()).isEqualTo(PublicSearchService.MODE_SEMANTIC);
        verify(retriever).retrieve(eq("废料杯"), eq(true), anyInt(), anyDouble(), eq(false));
    }

    @Test
    @DisplayName("★ 同一句话搜两次，只调一次检索（LRU 缓存挡住重复开销）")
    void cachesRepeatedQuery() {
        answer(List.of(new KbRetriever.Hit(scrapCup, 0.6, "vector")));

        service.search("废料杯怎么合成", 20);
        service.search("废料杯怎么合成", 20);

        verify(retriever, times(1)).retrieve(anyString(), anyBoolean(), anyInt(), anyDouble(), anyBoolean());
    }

    @Test
    @DisplayName("★ 日配额用完 → 静默降级为纯关键词，搜索照常可用")
    void degradesWhenQuotaExhausted() {
        props.getPublicSearch().setVectorDailyLimit(0);
        answer(List.of(new KbRetriever.Hit(scrapCup, 0.6, "keyword")));

        PublicSearchService.Outcome out = service.search("废料杯", 20);

        assertThat(out.mode()).isEqualTo(PublicSearchService.MODE_KEYWORD);
        assertThat(out.hits()).as("降级不等于没结果").hasSize(1);
        verify(retriever).retrieve(eq("废料杯"), eq(false), anyInt(), anyDouble(), eq(false));
    }

    @Test
    @DisplayName("向量模型不可用（没配 key）→ 不调 API，直接走本地关键词")
    void degradesWhenEmbeddingUnavailable() {
        when(embedding.isAvailable()).thenReturn(false);
        answer(List.of(new KbRetriever.Hit(scrapCup, 0.6, "keyword")));

        PublicSearchService.Outcome out = service.search("废料杯", 20);

        assertThat(out.mode()).isEqualTo(PublicSearchService.MODE_KEYWORD);
        verify(retriever).retrieve(eq("废料杯"), eq(false), anyInt(), anyDouble(), eq(false));
    }

    @Test
    @DisplayName("空查询直接返回空，不触发检索")
    void blankQueryIsFree() {
        assertThat(service.search("   ", 20).hits()).isEmpty();
        verify(retriever, times(0)).retrieve(anyString(), anyBoolean(), anyInt(), anyDouble(), anyBoolean());
    }

    @Test
    @DisplayName("用量统计：已用/剩余/缓存条数")
    void statsExposeUsage() {
        props.getPublicSearch().setVectorDailyLimit(10);
        answer(List.of(new KbRetriever.Hit(scrapCup, 0.6, "vector")));
        service.search("废料杯", 20);

        var stats = service.stats();
        assertThat(stats.get("usedToday")).isEqualTo(1);
        assertThat(stats.get("remainingToday")).isEqualTo(9);
        assertThat(stats.get("cached")).isEqualTo(1);
    }
}
