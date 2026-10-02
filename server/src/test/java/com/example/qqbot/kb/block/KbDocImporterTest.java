package com.example.qqbot.kb.block;

import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.doc.ChunkImportPlan;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档导入的端到端验收测试：**文档 → 块表 → 索引**。
 *
 * <p>这是"系统只做 文档→知识库"这条边界的第一个完整竖直切片。
 * 钉的是用户定下的导入语义：
 * <b>文件里的 id 存在就覆盖、不存在就新增；文件里没出现的一个都不动；不保护手工订正。</b>
 */
class KbDocImporterTest {

    private static final int DIM = 3;

    @TempDir
    Path base;

    private QaStore qaStore;
    private KbBlockStore store;
    private KbBlockIndex index;
    private EmbeddingClient embedding;
    private KbDocImporter importer;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        qa.setDb(base.resolve("qa.sqlite").toString());
        qaStore = new QaStore(qa, new ObjectMapper());
        qaStore.init();

        store = new KbBlockStore(qaStore);
        store.init();
        index = new KbBlockIndex(store);

        embedding = mock(EmbeddingClient.class);
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenAnswer(inv -> vecOf(inv.getArgument(0)));

        importer = new KbDocImporter(store, index, embedding,
                org.mockito.Mockito.mock(com.example.qqbot.kb.term.KbTermStore.class));
    }

    /** 假向量：按文本算出确定性向量，不联网、不花钱 */
    private static float[] vecOf(String text) {
        float[] v = new float[DIM];
        int h = text == null ? 0 : text.hashCode();
        for (int i = 0; i < DIM; i++) {
            v[i] = (((h >>> (i * 7)) & 0x3F) + 1) / 64f;
        }
        return v;
    }

    /** 一份带文档头的文档 */
    private static String doc(String name, String... blockLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- doc").append('\n')
                .append("name: ").append(name).append('\n')
                .append("url: https://w/").append(name).append('\n')
                .append("tags: 基础, 据点").append('\n')
                .append("-->").append('\n').append('\n');
        for (String l : blockLines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /* ==================== 首次导入 ==================== */

    @Test
    @DisplayName("首次导入：块进库、带上文档头里的 url 与 tags、索引随之可用")
    void firstImport() {
        KbDocImporter.Result r = importer.importDocument(doc("灵火祭坛",
                "=== 灵火祭坛 === <!-- id: flame-altar-0 -->",
                "祭坛是复活点。",
                "",
                "=== 升级材料 === <!-- id: flame-altar-1 -->",
                "升级需要 5 个瘴气木。"));

        assertThat(r.added()).isEqualTo(2);
        assertThat(r.updated()).isZero();
        assertThat(store.count()).isEqualTo(2);

        KbBlock b = store.get("flame-altar-0");
        assertThat(b.title()).isEqualTo("灵火祭坛");
        assertThat(b.body()).isEqualTo("祭坛是复活点。");
        assertThat(b.url()).as("出处来自文档头 —— 回答时要给群友").isEqualTo("https://w/灵火祭坛");
        assertThat(b.tags()).containsExactly("基础", "据点");
        assertThat(b.docId()).isEqualTo("灵火祭坛");

        assertThat(index.isReady()).isTrue();
        assertThat(index.searchable()).hasSize(2);
        assertThat(index.vector("flame-altar-0")).hasSize(DIM);
    }

    /* ==================== ★ 用户的核心场景：只给变更的块 ==================== */

    @Test
    @DisplayName("★ 库 3 块、文档只给 1 块且改了正文 → 只覆盖那 1 块，其余 2 块一字未动")
    void incrementalImportOnlyTouchesMentionedBlocks() {
        importer.importDocument(doc("页面",
                "=== 甲 === <!-- id: p-0 -->", "旧正文甲",
                "", "=== 乙 === <!-- id: p-1 -->", "旧正文乙",
                "", "=== 丙 === <!-- id: p-2 -->", "旧正文丙"));

        KbDocImporter.Result r = importer.importDocument(doc("页面",
                "=== 乙 === <!-- id: p-1 -->", "新正文乙"));

        assertThat(r.added()).isZero();
        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.untouched()).as("甲和丙没被碰").isEqualTo(2);
        assertThat(store.count()).as("总块数不变").isEqualTo(3);

        assertThat(store.get("p-1").body()).isEqualTo("新正文乙");
        assertThat(store.get("p-0").body()).as("甲原样").isEqualTo("旧正文甲");
        assertThat(store.get("p-2").body()).as("丙原样").isEqualTo("旧正文丙");
        assertThat(index.block("p-1").body()).as("索引也跟着更新了").isEqualTo("新正文乙");
    }

    @Test
    @DisplayName("★ 幂等：同一份文档导两次，第二次一条都不改")
    void reimportChangesNothing() {
        String d = doc("页面", "=== 甲 === <!-- id: p-0 -->", "正文甲");

        importer.importDocument(d);
        KbDocImporter.Result second = importer.importDocument(d);

        assertThat(second.changed()).isZero();
        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("新 id → 新增（和库里已有的块并存）")
    void brandNewIdIsAdded() {
        importer.importDocument(doc("页面", "=== 甲 === <!-- id: p-0 -->", "正文甲"));
        KbDocImporter.Result r = importer.importDocument(doc("页面",
                "=== 新块 === <!-- id: p-9 -->", "正文新"));

        assertThat(r.added()).isEqualTo(1);
        assertThat(store.count()).isEqualTo(2);
    }

    /* ==================== 预览（只算不改）==================== */

    @Test
    @DisplayName("★ preview 只算不改：调用后库里的数据一个字都没变")
    void previewDoesNotWrite() {
        importer.importDocument(doc("页面", "=== 甲 === <!-- id: p-0 -->", "旧正文"));

        ChunkImportPlan<com.example.qqbot.kb.doc.ChunkMarkup.Block> plan = ChunkImportPlan.of(library(), parse(doc("页面",
                "=== 甲 === <!-- id: p-0 -->", "新正文")));

        assertThat(plan.updated()).hasSize(1);
        assertThat(store.get("p-0").body()).as("预览不改数据").isEqualTo("旧正文");
    }

    /* ==================== 省 embedding ==================== */

    @Test
    @DisplayName("★ 只为新增/修改的块算向量：3 块首次导入 3 次，再导 1 块只多 1 次")
    void onlyChangedBlocksAreEmbedded() {
        importer.importDocument(doc("页面",
                "=== 甲 === <!-- id: p-0 -->", "正文甲",
                "", "=== 乙 === <!-- id: p-1 -->", "正文乙",
                "", "=== 丙 === <!-- id: p-2 -->", "正文丙"));
        verify(embedding, times(3)).embedOne(anyString());

        clearInvocations(embedding);
        importer.importDocument(doc("页面", "=== 乙 === <!-- id: p-1 -->", "新的正文乙"));

        verify(embedding, times(1)).embedOne(anyString());
    }

    /* ==================== 坏文档 ==================== */

    @Test
    @DisplayName("★ 格式有致命错误 → 抛错，且**一条数据都不写入**")
    void brokenDocumentWritesNothing() {
        assertThatThrownBy(() -> importer.importDocument(doc("页面",
                "=== 甲 === <!-- id: 中文id -->", "正文")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id 只能由英文字母、数字和 - 组成");

        assertThat(store.count()).as("坏文档不能留下半个知识库").isZero();
    }

    @Test
    @DisplayName("一个块都没有的文档 → 抛错")
    void emptyDocumentIsRejected() {
        assertThatThrownBy(() -> importer.importDocument("<!-- doc\nname: x\n-->\n没有分块"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有找到任何分块分隔符");
    }

    /* ==================== 清空 ==================== */

    @Test
    @DisplayName("清空（首次导入中文语料前用）→ 块和索引都空了")
    void clearAll() {
        importer.importDocument(doc("页面", "=== 甲 === <!-- id: p-0 -->", "正文"));
        importer.clearAll();

        assertThat(store.count()).isZero();
        assertThat(index.isReady()).isFalse();
    }

    // ==================== 工具 ====================

    private java.util.Map<String, KbBlock> library() {
        java.util.Map<String, KbBlock> lib = new java.util.LinkedHashMap<>();
        for (KbBlock b : store.all()) {
            lib.put(b.id(), b);
        }
        return lib;
    }

    private static java.util.List<com.example.qqbot.kb.doc.ChunkMarkup.Block> parse(String text) {
        return com.example.qqbot.kb.doc.ChunkMarkup.parse(text).blocks();
    }
}
