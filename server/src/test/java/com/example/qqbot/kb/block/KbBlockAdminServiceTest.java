package com.example.qqbot.kb.block;

import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理端块操作的验收测试（按 id，不再是行号）。
 *
 * <p>重点钉三件容易出事的事：
 * <ol>
 *   <li><b>改正文必须重算向量</b> —— 否则"保存成功"但检索还是按旧正文匹配；</li>
 *   <li><b>中文文档名不能自动生成 id</b> —— 我们的语料正是中文的，这是最容易踩的坑；</li>
 *   <li><b>整份下架只动那一份</b> —— 别把别人的块也翻了。</li>
 * </ol>
 */
class KbBlockAdminServiceTest {

    private static final int DIM = 3;

    @TempDir
    Path base;

    private QaStore qaStore;
    private KbBlockStore store;
    private KbBlockIndex index;
    private EmbeddingClient embedding;
    private KbBlockAdminService service;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        qa.setDb(base.resolve("qa.sqlite").toString());
        qaStore = new QaStore(qa, new ObjectMapper());
        qaStore.init();

        store = new KbBlockStore(new KbBlockRepository(new Jdbc(qaStore)));
        store.init();
        index = new KbBlockIndex(store);

        embedding = mock(EmbeddingClient.class);
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenAnswer(inv -> vecOf(inv.getArgument(0)));
        // 标题向量走批量接口（一次请求多条），所以这里也得给 mock 一个实现
        when(embedding.embedChunked(org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv -> {
            java.util.List<String> texts = inv.getArgument(0);
            java.util.List<float[]> out = new java.util.ArrayList<>();
            for (String t : texts) {
                out.add(vecOf(t));
            }
            return out;
        });

        service = new KbBlockAdminService(store, index, embedding, new KbDocImporter(store, index, embedding,
                org.mockito.Mockito.mock(com.example.qqbot.kb.term.KbTermStore.class)));
    }

    private static float[] vecOf(String text) {
        float[] v = new float[DIM];
        int h = text == null ? 0 : text.hashCode();
        for (int i = 0; i < DIM; i++) {
            v[i] = (((h >>> (i * 7)) & 0x3F) + 1) / 64f;
        }
        return v;
    }

    /* ==================== 新增 ==================== */

    @AfterEach
    void closeStores() {
        qaStore.close();
    }

    @Test
    @DisplayName("显式给 id 新增：块进库，向量按正文算好")
    void addWithExplicitId() {
        KbBlockAdminService.BlockView v = service.add("flame-altar", "flame-altar-0", "灵火祭坛",
                "祭坛是复活点。", "https://w/Flame_Altar", java.util.List.of("基础"));

        assertThat(v.id()).isEqualTo("flame-altar-0");
        assertThat(v.source()).isEqualTo("manual");
        assertThat(store.vector("flame-altar-0")).containsExactly(vecOf("祭坛是复活点。"));
        assertThat(index.block("flame-altar-0")).isNotNull();
    }

    @Test
    @DisplayName("文档名是英文时可自动编号：flame-altar-0、flame-altar-1")
    void autoNumberingWhenDocIsAscii() {
        service.add("flame-altar", null, "灵火祭坛", "第一块", "", null);
        KbBlockAdminService.BlockView second = service.add("flame-altar", null, "灵火祭坛", "第二块", "", null);

        assertThat(service.blocksOfDoc("flame-altar")).extracting(KbBlockAdminService.BlockView::id)
                .containsExactly("flame-altar-0", "flame-altar-1");
        assertThat(second.id()).isEqualTo("flame-altar-1");
    }

    @Test
    @DisplayName("★ 文档名是中文且没给 id → 报错并说清怎么办（中文语料下最容易踩）")
    void chineseDocNameCannotAutoGenerateId() {
        assertThatThrownBy(() -> service.add("灵火祭坛", null, "灵火祭坛", "正文", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法自动生成 id")
                .hasMessageContaining("显式指定 id");
    }

    @Test
    @DisplayName("id 重复 / 非法 / 正文为空 → 都拦住，并给人看得懂的话")
    void addRejectsBadInput() {
        service.add("doc", "doc-0", "标题", "正文", "", null);

        assertThatThrownBy(() -> service.add("doc", "doc-0", "标题", "别的正文", "", null))
                .hasMessageContaining("id 已存在");
        assertThatThrownBy(() -> service.add("doc", "中文id", "标题", "正文", "", null))
                .hasMessageContaining("id 只能由英文字母、数字和 - 组成");
        assertThatThrownBy(() -> service.add("doc", "doc-1", "标题", "   ", "", null))
                .hasMessageContaining("正文不能为空");
    }

    /* ==================== ★ 改正文必须重算向量 ==================== */

    @Test
    @DisplayName("★ 改正文会重算向量 —— 否则保存成功但检索还按旧正文匹配")
    void updateTextRecomputesVector() {
        service.add("doc", "doc-0", "标题", "旧正文", "", null);
        assertThat(store.vector("doc-0")).containsExactly(vecOf("旧正文"));

        KbBlockAdminService.BlockView v = service.updateText("doc-0", "全新的正文");

        assertThat(v.text()).isEqualTo("全新的正文");
        assertThat(store.get("doc-0").body()).isEqualTo("全新的正文");
        assertThat(store.vector("doc-0"))
                .as("★ 向量必须是按新正文算的")
                .containsExactly(vecOf("全新的正文"))
                .isNotEqualTo(vecOf("旧正文"));
    }

    @Test
    @DisplayName("改不存在的块 → 返回 null（不是抛异常，方便接口回 404）")
    void updateMissingReturnsNull() {
        assertThat(service.updateText("没有这个", "正文")).isNull();
    }

    @Test
    @DisplayName("把正文改成空 → 报错并提示用「下架」")
    void updateToEmptyIsRejected() {
        service.add("doc", "doc-0", "标题", "正文", "", null);

        assertThatThrownBy(() -> service.updateText("doc-0", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("下架");
    }

    /* ==================== 下架 / 删除 / 整份 ==================== */

    @Test
    @DisplayName("下架是标记（数据还在、能恢复）；删除是真删")
    void retireAndDelete() {
        service.add("doc", "doc-0", "甲", "正文甲", "", null);
        service.add("doc", "doc-1", "乙", "正文乙", "", null);

        assertThat(service.setRetired("doc-0", true)).isTrue();
        assertThat(store.count()).as("下架不删数据").isEqualTo(2);
        assertThat(index.block("doc-0")).as("但不再进检索索引").isNull();

        assertThat(service.setRetired("doc-0", false)).isTrue();
        assertThat(index.block("doc-0")).isNotNull();

        assertThat(service.delete("doc-1")).isTrue();
        assertThat(store.count()).isEqualTo(1);
        assertThat(service.delete("doc-1")).as("再删一次返回 false").isFalse();
    }

    @Test
    @DisplayName("★ 整份文档下架：只动那一份，别的文档一块都不碰")
    void retireDocTouchesOnlyThatDoc() {
        service.add("docA", "docA-0", "甲", "正文", "", null);
        service.add("docA", "docA-1", "乙", "正文", "", null);
        service.add("docB", "docB-0", "丙", "正文", "", null);

        int n = service.setDocRetired("docA", true);

        assertThat(n).isEqualTo(2);
        assertThat(store.get("docA-0").retired()).isTrue();
        assertThat(store.get("docA-1").retired()).isTrue();
        assertThat(store.get("docB-0").retired()).as("docB 不受影响").isFalse();

        assertThat(service.setDocRetired("docA", false)).isEqualTo(2);
        assertThat(store.get("docA-0").retired()).isFalse();
    }

    /* ==================== 概览 ==================== */

    @Test
    @DisplayName("文档概览：每份文档的块数与已下架数")
    void docsSummary() {
        service.add("docA", "docA-0", "甲", "正文", "", null);
        service.add("docA", "docA-1", "乙", "正文", "", null);
        service.add("docB", "docB-0", "丙", "正文", "", null);
        service.setRetired("docA-1", true);

        assertThat(service.docs()).extracting(KbBlockAdminService.DocSummary::docId)
                .containsExactly("docA", "docB");
        KbBlockAdminService.DocSummary a = service.docs().get(0);
        assertThat(a.blocks()).isEqualTo(2);
        assertThat(a.retired()).isEqualTo(1);

        assertThat(service.stats()).containsEntry("blocks", 3)
                .containsEntry("retired", 1)
                .containsEntry("documents", 2)
                .containsEntry("vectors", 3);
    }

    @Test
    @DisplayName("blocksOfDoc 含已下架的块（管理端要看得到）")
    void blocksOfDocIncludesRetired() {
        service.add("doc", "doc-0", "甲", "正文", "", null);
        service.add("doc", "doc-1", "乙", "正文", "", null);
        service.setRetired("doc-1", true);

        assertThat(service.blocksOfDoc("doc")).hasSize(2);
        assertThat(service.blocksOfDoc("doc")).extracting(KbBlockAdminService.BlockView::retired)
                .containsExactly(false, true);
    }

    /* ==================== 导入 / 清空 ==================== */

    @Test
    @DisplayName("导入委托给 KbDocImporter：文档进库、预览只算不改")
    void importAndPreview() {
        String doc = String.join("\n",
                "<!-- doc", "name: flame-altar", "url: https://w/F", "-->", "",
                "=== 灵火祭坛 === <!-- id: flame-altar-0 -->", "祭坛是复活点。");

        assertThat(service.preview(doc).added()).hasSize(1);
        assertThat(store.count()).as("预览不改数据").isZero();

        KbDocImporter.Result r = service.importDoc(doc);
        assertThat(r.added()).isEqualTo(1);
        assertThat(store.get("flame-altar-0").title()).isEqualTo("灵火祭坛");
    }

    @Test
    @DisplayName("清空（首次导入前用）：块和索引都空")
    void clearAll() {
        service.add("doc", "doc-0", "甲", "正文", "", null);
        service.clearAll();

        assertThat(store.count()).isZero();
        assertThat(index.isReady()).isFalse();
        assertThat(service.docs()).isEmpty();
    }

    /* ==================== ★ 标题向量（C 路） ==================== */

    @Test
    @DisplayName("新增块时**顺手把标题向量也算好**（否则新块永远吃不到 C 路）")
    void addAlsoWritesTitleVector() {
        service.add("doc", "doc-0", "灵火祭坛（Flame Altar）", "祭坛是复活点。", "", null);

        assertThat(store.titleVector("doc-0")).isNotNull();
        assertThat(store.titleVector("doc-0").title()).isEqualTo("灵火祭坛（Flame Altar）");
        assertThat(store.titleVector("doc-0").vec()).containsExactly(vecOf("灵火祭坛（Flame Altar）"));
        assertThat(index.titleVector("doc-0")).as("索引里立刻可见，不用重启").isNotNull();
    }

    @Test
    @DisplayName("补建：缺的补齐、过期的重算、再跑一遍什么都不做（幂等）")
    void backfillFillsMissingAndStale() {
        // ① 用手工 upsert 造两个"没有标题向量"的块（模拟标题向量功能上线前的老数据）
        store.upsert(new KbBlock("a", "d", "酸蚀之咬（Acid Bite）", "正文 A", "", java.util.List.of(),
                KbBlock.SRC_DOC, false, ""), vecOf("正文 A"));
        store.upsert(new KbBlock("b", "d", "酸蚀砍刀（Acid Cleaver）", "正文 B", "", java.util.List.of(),
                KbBlock.SRC_DOC, false, ""), vecOf("正文 B"));

        KbBlockAdminService.TitleBackfill preview = service.backfillTitleVectors(true);
        assertThat(preview.missing()).isEqualTo(2);
        assertThat(preview.embedded()).as("dryRun 不写库、不调模型").isZero();
        assertThat(store.countTitleVectors()).isZero();

        KbBlockAdminService.TitleBackfill first = service.backfillTitleVectors(false);
        assertThat(first.pending()).isEqualTo(2);
        assertThat(first.embedded()).isEqualTo(2);
        assertThat(store.countTitleVectors()).isEqualTo(2);
        assertThat(index.titleVector("a")).as("补完自动 reload，不用重启").isNotNull();

        KbBlockAdminService.TitleBackfill again = service.backfillTitleVectors(false);
        assertThat(again.pending()).as("幂等：再跑一遍没有要补的").isZero();
        assertThat(again.embedded()).isZero();
    }

    @Test
    @DisplayName("★ 标题改了 → 判为过期并重算（标题向量的输入变了，它就作废了）")
    void backfillDetectsStaleTitle() {
        store.upsert(new KbBlock("a", "d", "旧标题", "正文", "", java.util.List.of(),
                KbBlock.SRC_DOC, false, ""), vecOf("正文"));
        store.upsertTitleVector("a", "旧标题", vecOf("旧标题"));
        assertThat(store.countTitleVectors()).isEqualTo(1);

        // 只改标题（正文向量不动 —— 这是 upsert 的既有语义）
        store.upsert(new KbBlock("a", "d", "新标题", "正文", "", java.util.List.of(),
                KbBlock.SRC_DOC, false, ""), null);

        KbBlockAdminService.TitleBackfill r = service.backfillTitleVectors(false);
        assertThat(r.stale()).isEqualTo(1);
        assertThat(r.missing()).isZero();
        assertThat(store.titleVector("a").title()).isEqualTo("新标题");
        assertThat(store.titleVector("a").vec()).containsExactly(vecOf("新标题"));
    }

    @Test
    @DisplayName("没标题的块不参与补建（没有「标题向量」可言，不该白花一次调用）")
    void backfillSkipsBlankTitle() {
        store.upsert(new KbBlock("a", "d", "", "正文", "", java.util.List.of(),
                KbBlock.SRC_DOC, false, ""), vecOf("正文"));

        KbBlockAdminService.TitleBackfill r = service.backfillTitleVectors(false);
        assertThat(r.pending()).isZero();
        assertThat(store.countTitleVectors()).isZero();
    }
}
