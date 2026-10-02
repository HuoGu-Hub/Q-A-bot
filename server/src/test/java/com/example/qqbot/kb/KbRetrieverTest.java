package com.example.qqbot.kb;

import com.example.qqbot.config.KbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索的验收测试。重点不是"能不能检索到"，而是
 * **A 路失败时会不会正确回落 B 路、两路都挂时会不会安静地不带资料**。
 *
 * <p>2026-09-28：语料来源换成了 {@link KbCorpus}（存储可换、算法只留一份）。
 * 所以这里不再桩 {@code KbIndex} 的"第 i 块 / 第 i 行向量"（那是"行号即身份"的写法），
 * 改成直接给出**条目列表** —— 那也正是新存储（id 即身份）的样子。
 * **断言逐条未改**，这正是这次重构的安全网。
 */
class KbRetrieverTest {

    private KbProperties props;
    private KbCorpus corpus;
    private Glossary glossary;
    private EmbeddingClient embedding;
    private KbRetriever retriever;

    @BeforeEach
    void setUp() {
        props = new KbProperties();
        corpus = mock(KbCorpus.class);
        glossary = mock(Glossary.class);
        embedding = mock(EmbeddingClient.class);
        retriever = new KbRetriever(props, corpus, glossary, embedding);

        when(corpus.isReady()).thenReturn(true);
        when(corpus.dimensions()).thenReturn(2);
    }

    /** 造一条可检索条目（向量由用例决定） */
    private static KbCorpus.Entry entry(String id, String title, String text,
                                        List<String> tags, String url, float[] v) {
        return new KbCorpus.Entry(id, title, text, url, tags, false, "", v);
    }

    private void given(KbCorpus.Entry... entries) {
        when(corpus.entries()).thenReturn(List.of(entries));
    }

    /** 默认那两块（是改造前那两条测试数据的原样搬运） */
    private void givenTwo(float[] scrapCupVec, float[] goatVec) {
        given(
                entry("scrap-cup", "Scrap Cup",
                        "Scrap Cup：Type: Furniture; Ingredients: Metal Scraps: 1",
                        List.of("Tableware"), "https://w/Scrap_Cup", scrapCupVec),
                entry("frizzy-goat", "Frizzy Goat",
                        "Frizzy Goat：tamed with Goat Food Bait",
                        List.of("Wildlife"), "https://w/Frizzy_Goat", goatVec));
    }

    @Test
    @DisplayName("A 路：按余弦排序，且低于阈值的被丢掉")
    void vectorPathRanksAndFilters() {
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        givenTwo(new float[]{1f, 0f}, new float[]{0.1f, 0.99f});   // cos = 1.0 / ≈0.1

        KbRetriever.Retrieval result = retriever.retrieve("废料杯怎么合成");

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).entry().title()).isEqualTo("Scrap Cup");
        assertThat(result.hits().get(0).source()).isEqualTo("vector");
        assertThat(result.bestCosine()).as("★ bestCosine 必须是余弦，不是融合分").isEqualTo(1.0);
    }

    @Test
    @DisplayName("★ A 路全部低于阈值 → 自动回落 B 路（关键词）")
    void fallsBackToKeywordWhenVectorScoresTooLow() {
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        givenTwo(new float[]{0f, 1f}, new float[]{0f, 1f});        // cos = 0，低于阈值
        when(glossary.expand(anyString())).thenReturn(List.of("Scrap Cup"));

        KbRetriever.Retrieval result = retriever.retrieve("废料杯怎么合成");

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).source()).isEqualTo("keyword");
        assertThat(result.hits().get(0).entry().title()).isEqualTo("Scrap Cup");
        assertThat(result.bestCosine()).as("向量路全被阈值滤掉，最高余弦仍是 0").isZero();
    }

    @Test
    @DisplayName("★ A 路抛异常（key 过期/网络挂）→ 也能回落 B 路，绝不冒泡")
    void fallsBackWhenVectorThrows() {
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString()))
                .thenThrow(new EmbeddingClient.EmbeddingException("向量模型 HTTP 401"));
        givenTwo(new float[]{0f, 1f}, new float[]{0f, 1f});
        when(glossary.expand(anyString())).thenReturn(List.of("Frizzy Goat"));

        List<KbRetriever.Hit> hits = retriever.retrieve("卷毛山羊怎么驯服").hits();

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).source()).isEqualTo("keyword");
    }

    @Test
    @DisplayName("★ 两路都挂 → 返回空列表，不抛异常（机器人照常回答）")
    void bothPathsFailSilently() {
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenThrow(new RuntimeException("网络炸了"));
        when(glossary.expand(anyString())).thenThrow(new RuntimeException("术语表读不了"));
        givenTwo(new float[]{0f, 1f}, new float[]{0f, 1f});

        assertThat(retriever.retrieve("废料杯怎么合成").hits()).isEmpty();
    }

    @Test
    @DisplayName("索引没建好 → 直接空，不报错")
    void emptyWhenIndexNotReady() {
        when(corpus.isReady()).thenReturn(false);
        givenTwo(new float[]{1f, 0f}, new float[]{0f, 1f});

        assertThat(retriever.retrieve("废料杯怎么合成").hits()).isEmpty();
    }

    @Test
    @DisplayName("总开关关掉 → 完全不检索")
    void disabledMeansNoRetrieval() {
        props.setEnabled(false);
        givenTwo(new float[]{1f, 0f}, new float[]{0f, 1f});

        assertThat(retriever.retrieve("废料杯怎么合成").hits()).isEmpty();
    }

    @Test
    @DisplayName("★ 融合：向量路完全看不见的目标，被关键词路顶到第一")
    void keywordRescuesChunkInvisibleToVector() {
        // 三个块：目标块 Scrap Cup 的向量和问题完全正交（余弦 0，会被阈值滤掉），
        // 但术语表里「废料杯 → Scrap Cup」能精确命中它
        given(
                entry("scrap-cup", "Scrap Cup",
                        "Scrap Cup：Type: Furniture; Ingredients: Metal Scraps: 1",
                        List.of("Tableware"), "https://w/Scrap_Cup", new float[]{0f, 1f}),
                entry("frizzy-goat", "Frizzy Goat", "Frizzy Goat：tamed with Goat Food Bait",
                        List.of("Wildlife"), "https://w/Frizzy_Goat", new float[]{0.2f, 0.98f}),
                entry("crucible", "Crucible", "Crucible：用于冶炼",
                        List.of("Stations"), "https://w/Crucible", new float[]{1f, 0f}));

        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        when(glossary.expand(anyString())).thenReturn(List.of("Scrap Cup"));
        props.setTopK(2);

        List<KbRetriever.Hit> hits = retriever.retrieve("废料杯怎么合成？").hits();

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).entry().title())
                .as("关键词路命中标题，权重更高，应排在向量路第一名之前")
                .isEqualTo("Scrap Cup");
        assertThat(hits.get(0).source()).isEqualTo("keyword");
        assertThat(hits.get(1).entry().title()).isEqualTo("Crucible");
    }

    @Test
    @DisplayName("B 路：命中标题的块排在命中正文的前面")
    void keywordWeightsTitleHigher() {
        when(embedding.isAvailable()).thenReturn(false);
        when(glossary.expand(anyString())).thenReturn(List.of("Goat"));
        // Scrap Cup 正文里没有 Goat；Frizzy Goat 标题里有
        givenTwo(new float[]{1f, 0f}, new float[]{0f, 1f});

        List<KbRetriever.Hit> hits = retriever.retrieve("goat").hits();

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).entry().title()).isEqualTo("Frizzy Goat");
    }

    @Test
    @DisplayName("★ 标题精确命中：物品本体页压过「正文提到它」的分类页")
    void exactTitleBeatsMentionPage() {
        // 实测问题：搜「废料杯」时 Tableware 页面两路都靠前，把 Scrap Cup 本体挤到第二
        given(
                entry("scrap-cup", "Scrap Cup",
                        "Scrap Cup：Type: Furniture; Ingredients: Metal Scraps: 1",
                        List.of("Tableware"), "https://w/Scrap_Cup", new float[]{0.3f, 0.95f}),
                entry("frizzy-goat", "Frizzy Goat", "Frizzy Goat：tamed with Goat Food Bait",
                        List.of("Wildlife"), "https://w/Frizzy_Goat", new float[]{0.3f, 0.95f}),
                entry("tableware", "Decorations/Tableware", "Scrap Cup and other tableware",
                        List.of("Tableware"), "https://w/Tableware", new float[]{1f, 0f}));

        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        when(glossary.expand(anyString())).thenReturn(List.of("Scrap Cup"));

        List<KbRetriever.Hit> hits = retriever.retrieve("废料杯", true, 2, 0.32).hits();

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).entry().title())
                .as("Scrap Cup 是标题精确命中，必须排第一")
                .isEqualTo("Scrap Cup");
        assertThat(hits.get(1).entry().title()).isEqualTo("Decorations/Tableware");
    }

    @Test
    @DisplayName("★ 显式阈值/条数重载：公开站用更松的阈值拿到更多候选")
    void explicitThresholdAndTopKOverrideConfig() {
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        givenTwo(new float[]{1f, 0f}, new float[]{0.4f, 0.92f});   // cos = 1.0 / ≈0.4
        when(glossary.expand(anyString())).thenReturn(List.of());

        // 默认配置 min-score=0.45 → 第二块被滤掉
        assertThat(retriever.retrieve("灵火祭坛").hits()).hasSize(1);

        // 公开站阈值 0.32 → 两块都要，且能指定只取 1 条
        assertThat(retriever.retrieve("灵火祭坛", true, 5, 0.32).hits()).hasSize(2);
        assertThat(retriever.retrieve("灵火祭坛", true, 1, 0.32).hits()).hasSize(1);
    }

    @Test
    @DisplayName("余弦计算本身是对的")
    void cosineIsCorrect() {
        assertThat(KbRetriever.cosine(new float[]{1f, 0f}, new float[]{1f, 0f})).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(KbRetriever.cosine(new float[]{1f, 0f}, new float[]{0f, 1f})).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(KbRetriever.cosine(new float[]{0f, 0f}, new float[]{1f, 0f})).isZero();
    }

    @Test
    @DisplayName("★ 标题带「（English）」时，中文名仍算精确命中（否则本体被同类挤下去）")
    void exactTitleWithEnglishParensStillWins() {
        // 实测问题：「酸性菌丝有什么用」把本体排到第 3，前面是两个「菌丝体」——
        // 因为标题是「酸性菌丝（Acidic Mycelium）」，和词条名「酸性菌丝」不相等，
        // 吃不到"标题精确命中"那份加成。
        given(
                entry("acidic-mycelium", "酸性菌丝（Acidic Mycelium）", "酸性菌丝在雾水盆地采集",
                        List.of("材料"), "https://w/Acidic_Mycelium", new float[]{0.3f, 0.95f}),
                entry("mycelium-material", "菌丝体（Mycelium (Material)）", "菌丝体是制作材料",
                        List.of("材料"), "https://w/Mycelium_Material", new float[]{1f, 0f}));

        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        when(glossary.expand(anyString())).thenReturn(List.of("酸性菌丝"));

        List<KbRetriever.Hit> hits = retriever.retrieve("酸性菌丝有什么用", true, 2, 0.3).hits();

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).entry().title())
                .as("★ 中文名精确命中要压过向量路的排名")
                .startsWith("酸性菌丝");
    }

    @Test
    @DisplayName("直接打英文原名（括号里那半截）也算精确命中")
    void englishInParensCountsAsExact() {
        given(
                entry("acidic-mycelium", "酸性菌丝（Acidic Mycelium）", "酸性菌丝在雾水盆地采集",
                        List.of("材料"), "https://w/Acidic_Mycelium", new float[]{0.3f, 0.95f}),
                entry("mycelium-material", "菌丝体（Mycelium (Material)）", "菌丝体是制作材料",
                        List.of("材料"), "https://w/Mycelium_Material", new float[]{1f, 0f}));

        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        when(glossary.expand(anyString())).thenReturn(List.of("Acidic Mycelium"));

        List<KbRetriever.Hit> hits = retriever.retrieve("Acidic Mycelium", true, 2, 0.3).hits();

        assertThat(hits.get(0).entry().title()).startsWith("酸性菌丝");
    }
}
