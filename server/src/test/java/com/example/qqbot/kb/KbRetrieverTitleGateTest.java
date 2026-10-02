package com.example.qqbot.kb;

import com.example.qqbot.trace.KbTrace;

import com.example.qqbot.config.KbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C 路（标题向量闸门 + 置顶）的验收测试。
 *
 * <p>它钉的是四件容易做错的事（都有实测依据，见
 * {@code docs/md/知识库标题向量召回方案.md}）：
 * <ol>
 *   <li><b>置顶而不是投票</b> —— 正文路在"名字提问"上是系统性错的（正文不重复标题里的名字），
 *       让它平等参与 RRF 会把错误答案抬上来（R@1 27% → 49.5%）；置顶的写法是 99.5%。</li>
 *   <li><b>闸门必须真的关得住</b> —— 无阈值时标题路会把一半口语提问的 top1 换掉。</li>
 *   <li><b>不污染 A 路的读数</b> —— bestCosine / min-score 只反映正文路，C 路不参与。</li>
 *   <li><b>一次检索只调一次 embedding</b> —— A/C 两路共用同一个问题向量。</li>
 * </ol>
 */
class KbRetrieverTitleGateTest {

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
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        // 隔离 B 路：这里只验 C 路的行为
        when(glossary.expand(anyString())).thenReturn(List.of());
    }

    /** 造一条带标题向量的条目（titleVec 传 null = 这个块还没补标题向量） */
    private static KbCorpus.Entry entry(String id, String title, String text,
                                        float[] body, float[] titleVec) {
        KbCorpus.Entry e = new KbCorpus.Entry(id, title, text, "https://w/" + id,
                List.of(), false, "", body);
        return titleVec == null ? e : e.withTitleVector(titleVec);
    }

    private void given(KbCorpus.Entry... es) {
        when(corpus.entries()).thenReturn(List.of(es));
    }

    private static String id(KbRetriever.Hit h) {
        return h.entry().id();
    }

    @Test
    @DisplayName("★ 正文路看不见的名字：标题向量把它顶到第 1（正文那块本来排第一）")
    void titleGatePinsTheNameThatBodyVectorCannotSee() {
        given(
                // 正文是简介，一个字都没重复"酸蚀之咬"；正文向量和问题正交 → 会被 min-score 滤掉
                entry("acid-bite", "酸蚀之咬（Acid Bite）", "魔法弹药，向前喷出一道锥形酸液。",
                        new float[]{0f, 1f}, new float[]{1f, 0f}),
                // 同族词：正文向量和问题完全一致，本来稳坐第一
                entry("eternal", "永恒酸蚀咬击（Eternal Acid Bite）", "取之不尽的酸液之井。",
                        new float[]{1f, 0f}, null));

        List<KbRetriever.Hit> hits = retriever.retrieve("什么是酸蚀之咬").hits();

        assertThat(hits).extracting(KbRetrieverTitleGateTest::id)
                .containsExactly("acid-bite", "eternal");
        assertThat(hits.get(0).source()).isEqualTo("title");
        assertThat(hits.get(0).score())
                .as("置顶那条给的是 RRF 量纲的报告分，不是余弦（余弦会污染问答统计）")
                .isBetween(0.0, 0.2);
    }

    @Test
    @DisplayName("★ 闸门命中但也确实被向量路捞到 → 只换位置，不出现重复条目")
    void pinningMovesInsteadOfDuplicating() {
        given(
                entry("eternal", "永恒酸蚀咬击（Eternal Acid Bite）", "酸液之井。",
                        new float[]{1f, 0f}, null),
                entry("acid-bite", "酸蚀之咬（Acid Bite）", "魔法弹药。",
                        new float[]{0.6f, 0.8f}, new float[]{1f, 0f}));

        List<KbRetriever.Hit> hits = retriever.retrieve("什么是酸蚀之咬").hits();

        assertThat(hits).extracting(KbRetrieverTitleGateTest::id)
                .containsExactly("acid-bite", "eternal");
        assertThat(hits.get(0).source())
                .as("来源标成 vector+title：既保留它原来是被哪一路找到的，也看得出闸门开过")
                .isEqualTo("vector+title");
        assertThat(hits).hasSize(2);
    }

    @Test
    @DisplayName("★ 余弦低于闸门 → 一条都不动（这是「别把口语提问带偏」的那道闸）")
    void belowThresholdChangesNothing() {
        props.setTitleGate(0.90);
        given(
                entry("eternal", "永恒酸蚀咬击（Eternal Acid Bite）", "酸液之井。",
                        new float[]{1f, 0f}, null),
                // 标题余弦 0.6 < 0.90：名字只是"有点像"，不该置顶
                entry("acid-bite", "酸蚀之咬（Acid Bite）", "魔法弹药。",
                        new float[]{0.6f, 0.8f}, new float[]{0.6f, 0.8f}));

        List<KbRetriever.Hit> hits = retriever.retrieve("怎么给水箱加水").hits();

        assertThat(hits).extracting(KbRetrieverTitleGateTest::id)
                .containsExactly("eternal", "acid-bite");
        assertThat(hits).noneMatch(h -> h.source().contains("title"));
    }

    @Test
    @DisplayName("闸门设成 0（回滚开关）→ 哪怕标题向量完全对齐也不置顶")
    void zeroGateDisablesThePath() {
        props.setTitleGate(0.0);
        given(
                entry("eternal", "永恒酸蚀咬击（Eternal Acid Bite）", "酸液之井。",
                        new float[]{1f, 0f}, null),
                entry("acid-bite", "酸蚀之咬（Acid Bite）", "魔法弹药。",
                        new float[]{0.6f, 0.8f}, new float[]{1f, 0f}));

        List<KbRetriever.Hit> hits = retriever.retrieve("什么是酸蚀之咬").hits();

        assertThat(hits.get(0).entry().id()).isEqualTo("eternal");
        assertThat(hits).noneMatch(h -> h.source().contains("title"));
    }

    @Test
    @DisplayName("没补标题向量的块 → 安静跳过，不崩也不错位（老数据照常能用）")
    void entriesWithoutTitleVectorAreSkipped() {
        given(
                entry("eternal", "永恒酸蚀咬击", "酸液之井。", new float[]{1f, 0f}, null),
                entry("acid-bite", "酸蚀之咬", "魔法弹药。", new float[]{0.6f, 0.8f}, null));

        List<KbRetriever.Hit> hits = retriever.retrieve("什么是酸蚀之咬").hits();

        assertThat(hits).extracting(KbRetrieverTitleGateTest::id)
                .containsExactly("eternal", "acid-bite");
    }

    @Test
    @DisplayName("★ 标题向量维度不对（换了模型算的）→ 跳过，不拿它算一个「看着有值」的错余弦")
    void titleVectorWithWrongDimensionIsIgnored() {
        given(
                entry("eternal", "永恒酸蚀咬击", "酸液之井。", new float[]{1f, 0f}, null),
                entry("acid-bite", "酸蚀之咬", "魔法弹药。", new float[]{0.6f, 0.8f},
                        new float[]{1f, 0f, 0f}));

        List<KbRetriever.Hit> hits = retriever.retrieve("什么是酸蚀之咬").hits();

        assertThat(hits).extracting(KbRetrieverTitleGateTest::id)
                .containsExactly("eternal", "acid-bite");
        assertThat(hits).noneMatch(h -> h.source().contains("title"));
    }

    @Test
    @DisplayName("★ 正文路一条都没过阈值，但名字直击 → 照样给出那一条资料（不再是空手）")
    void gateRescuesWhenBodyPathFindsNothing() {
        given(entry("acid-bite", "酸蚀之咬（Acid Bite）", "魔法弹药，向前喷出一道锥形酸液。",
                new float[]{0f, 1f}, new float[]{1f, 0f}));

        KbRetriever.Retrieval r = retriever.retrieve("什么是酸蚀之咬");

        assertThat(r.hits()).hasSize(1);
        assertThat(r.hits().get(0).entry().id()).isEqualTo("acid-bite");
        assertThat(r.hits().get(0).source()).isEqualTo("title");
        assertThat(r.bestCosine())
                .as("★ A 路的读数不被 C 路污染：调 min-score 看的就是它")
                .isZero();
    }

    @Test
    @DisplayName("★ 一次检索只调一次 embedding（A/C 两路共用同一个问题向量）")
    void questionIsEmbeddedOnce() {
        given(
                entry("eternal", "永恒酸蚀咬击", "酸液之井。", new float[]{1f, 0f}, null),
                entry("acid-bite", "酸蚀之咬", "魔法弹药。", new float[]{0.6f, 0.8f}, new float[]{1f, 0f}));

        retriever.retrieve("什么是酸蚀之咬");

        verify(embedding, times(1)).embedOne(anyString());
    }

    @Test
    @DisplayName("问答统计的来源口径：闸门开过就记 title（它是调 title-gate 的唯一线上信号）")
    void traceReportsTitleSource() {
        given(entry("acid-bite", "酸蚀之咬（Acid Bite）", "魔法弹药。",
                new float[]{0f, 1f}, new float[]{1f, 0f}));

        KbRetriever.Retrieval r = retriever.retrieve("什么是酸蚀之咬");
        KbTrace trace = KbRetriever.traceOf(r, 12);

        assertThat(trace.sources()).isEqualTo("title");
    }

    @Test
    @DisplayName("embedding 挂了 → C 路和 A 路一起跳过，B 路照常（知识库只是锦上添花）")
    void embeddingDownMeansNoTitlePath() {
        when(embedding.embedOne(anyString()))
                .thenThrow(new EmbeddingClient.EmbeddingException("向量模型 HTTP 401"));
        when(glossary.expand(anyString())).thenReturn(List.of("Acid Bite"));
        given(entry("acid-bite", "酸蚀之咬（Acid Bite）", "魔法弹药。",
                new float[]{0f, 1f}, new float[]{1f, 0f}));

        List<KbRetriever.Hit> hits = retriever.retrieve("什么是酸蚀之咬").hits();

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).source()).isEqualTo("keyword");
    }
}
