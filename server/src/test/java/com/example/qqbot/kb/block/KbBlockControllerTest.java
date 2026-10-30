package com.example.qqbot.kb.block;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.EntryId;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.qa.QaStore;
import com.example.qqbot.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 块管理接口的契约测试。
 *
 * <p>不起 Spring MVC，直接调控制器方法 —— 验的是**请求体怎么解、响应体长什么样、
 * 出错回什么码**这三件事。界面就是照这个契约写的，改了这里界面就会对不上。
 */
class KbBlockControllerTest {

    @TempDir
    Path base;

    private QaStore qaStore;
    private SqliteDatabase db;

    private KbBlockStore store;
    private KbBlockIndex index;
    private KbBlockAdminService service;
    private KbBlockController controller;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();

        store = new KbBlockStore(new KbBlockRepository(new Jdbc(db)));
        store.init();
        index = new KbBlockIndex(store);

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenAnswer(inv -> new float[]{1f, 0f, 0f});
        when(embedding.embedChunked(org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv -> {
            java.util.List<String> texts = inv.getArgument(0);
            java.util.List<float[]> out = new java.util.ArrayList<>();
            for (String t : texts) {
                out.add(new float[]{1f, 0f, 0f});
            }
            return out;
        });

        service = new KbBlockAdminService(store, embedding, new KbDocImporter(store, embedding));
        controller = new KbBlockController(service);
    }

    private static String doc(String name, String id, String title, String text) {
        return String.join("\n",
                "<!-- doc", "name: " + name, "url: https://w/" + name, "tags: 基础", "-->", "",
                "=== " + title + " === <!-- id: " + id + " -->", text);
    }

    private static KbBlockController.AddBlockRequest add(String docId, String id, String title,
                                                        String text, List<String> tags) {
        return new KbBlockController.AddBlockRequest(docId, id, title, text, null, tags);
    }

    // ==================== 读 ====================

    @AfterEach
    void closeStores() {
        db.close();
    }

    @Test
    @DisplayName("stats / docs / blocks 三个读接口的形状")
    void readEndpoints() {
        var stats = controller.stats();
        assertThat(stats.available()).isTrue();
        assertThat(stats.blocks()).isZero();

        service.importBlocks(doc("flame-altar", "flame-altar-0", "灵火祭坛", "祭坛是复活点。"));

        var after = controller.stats();
        assertThat(after.blocks()).isEqualTo(1);
        assertThat(after.documents()).isEqualTo(1);
        assertThat(controller.docs().documents()).hasSize(1);

        var blocks = controller.blocks("flame-altar");
        assertThat(blocks.doc()).isEqualTo("flame-altar");
        assertThat(blocks.blocks()).hasSize(1);
    }

    // ==================== 预览 / 导入 ====================

    @Test
    @DisplayName("★ preview 只算不改，并把「会改多少条 + 哪些 id」回给界面")
    void previewShape() {
        service.importBlocks(doc("flame-altar", "flame-altar-0", "灵火祭坛", "旧正文"));

        var out = controller.preview(
                new KbBlockController.PreviewImportRequest(
                        doc("flame-altar", "flame-altar-0", "灵火祭坛", "新正文")));

        assertThat(out.added()).isZero();
        assertThat(out.updated()).isEqualTo(1);
        assertThat(out.unchanged()).isZero();
        assertThat(out.untouchedExisting()).isZero();
        assertThat(out.updatedIds()).containsExactly("flame-altar-0");
        assertThat(store.get(EntryId.of("flame-altar-0")).body()).as("预览不改数据").isEqualTo("旧正文");
    }

    // ==================== 写 ====================

    @Test
    @DisplayName("update：缺 id / 块不存在 / 正文为空 三种错都要分得清")
    void updateErrorPaths() {
        assertThatThrownBy(() -> controller.update(new KbBlockController.UpdateBlockRequest(null, "x")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("id");

        assertThatThrownBy(() -> controller.update(
                new KbBlockController.UpdateBlockRequest("没有这个", "正文")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("没有这个块");

        service.importBlocks(doc("d", "d-0", "甲", "正文"));
        assertThatThrownBy(() -> controller.update(
                new KbBlockController.UpdateBlockRequest("d-0", "   ")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("下架");
    }

    @Test
    @DisplayName("add：tags 用数组；中文文档名不给 id 要报错")
    void addShapes() {
        var ok = controller.add(add(
                "kiln", "kiln-0", "熔炉", "熔炉用来烧制。", List.of("制作", "基础")));
        assertThat(ok.block().id()).isEqualTo("kiln-0");

        assertThatThrownBy(() -> controller.add(add("灵火祭坛", null, "灵火祭坛", "正文", List.of())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无法自动生成 id");
    }

    @Test
    @DisplayName("retire / delete：默认 retired=true；块不存在回 404")
    void retireAndDelete() {
        controller.add(add("d", "d-0", "甲", "正文", List.of()));

        assertThat(controller.retire(
                new KbBlockController.RetireBlockRequest("d-0", null)).retired()).isTrue();
        assertThat(store.get(EntryId.of("d-0")).retired()).isTrue();

        assertThat(controller.retire(
                new KbBlockController.RetireBlockRequest("d-0", false)).retired()).isFalse();
        assertThat(store.get(EntryId.of("d-0")).retired()).isFalse();

        assertThatThrownBy(() -> controller.retire(new KbBlockController.RetireBlockRequest("没有", null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.delete("没有")).isInstanceOf(ApiException.class);
        assertThat(controller.delete("d-0").getStatusCode().value()).isEqualTo(204);
        assertThat(store.count()).isZero();
    }

    @Test
    @DisplayName("retire-doc：整份下架并回报影响了几块")
    void retireDoc() {
        controller.add(add("d", "d-0", "甲", "正文", List.of()));
        controller.add(add("d", "d-1", "乙", "正文", List.of()));
        controller.add(add("e", "e-0", "丙", "正文", List.of()));

        var res = controller.retireDoc(new KbBlockController.RetireDocRequest("d", true));

        assertThat(res.affected()).isEqualTo(2);
        assertThat(store.get(EntryId.of("e-0")).retired()).as("别的文档不受影响").isFalse();
    }

    @Test
    @DisplayName("clear：清空全部块")
    void clear() {
        service.importBlocks(doc("d", "d-0", "甲", "正文"));
        assertThat(controller.clear().getStatusCode().value()).isEqualTo(204);
        assertThat(store.count()).isZero();
    }

    // ==================== 标题向量补建（C 路）====================

    @Test
    @DisplayName("title-vectors/backfill：dryRun 只报数量不写库，真跑才补；再跑一遍报 0（界面就靠这个契约）")
    void backfillTitleVectorsShape() {
        service.importBlocks(doc("d", "d-0", "酸蚀之咬（Acid Bite）", "魔法弹药。"));
        store.deleteTitleVector(EntryId.of("d-0"));

        var dry = controller.backfillTitleVectors(new KbBlockController.BackfillRequest(true));
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.pending()).isEqualTo(1);
        assertThat(dry.missing()).isEqualTo(1);
        assertThat(dry.stale()).isZero();
        assertThat(dry.embedded()).isZero();
        assertThat(store.countTitleVectors()).as("dryRun 不写库").isZero();

        var real = controller.backfillTitleVectors(new KbBlockController.BackfillRequest(null));
        assertThat(real.dryRun()).isFalse();
        assertThat(real.embedded()).isEqualTo(1);
        assertThat(store.countTitleVectors()).isEqualTo(1);

        var again = controller.backfillTitleVectors(null);
        assertThat(again.pending()).as("body 可以不传；幂等：没有要补的").isZero();
    }
}
