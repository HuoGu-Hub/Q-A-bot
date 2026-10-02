package com.example.qqbot.kb.block;

import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 块管理接口的契约测试。
 *
 * <p>不起 Spring MVC，直接调控制器方法 —— 验的是**请求体怎么解、响应体长什么样、
 * 出错回什么码**这三件事。界面就是照这个契约写的，改了这里界面就会对不上。
 *
 * <p>为什么要有它：真机验收要起服务（宿主 Vite / 后端），环境不具备时就断了；
 * 契约测试不需要任何外部依赖，永远能跑。
 */
class KbBlockControllerTest {

    @TempDir
    Path base;

    private QaStore qaStore;

    private KbBlockStore store;
    private KbBlockIndex index;
    private KbBlockAdminService service;
    private KbBlockController controller;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        qa.setDb(base.resolve("qa.sqlite").toString());
        qaStore = new QaStore(qa, new ObjectMapper());
        qaStore.init();

        store = new KbBlockStore(qaStore);
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

        service = new KbBlockAdminService(store, index, embedding, new KbDocImporter(store, index, embedding,
                org.mockito.Mockito.mock(com.example.qqbot.kb.term.KbTermStore.class)));
        controller = new KbBlockController(service);
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String doc(String name, String id, String title, String text) {
        return String.join("\n",
                "<!-- doc", "name: " + name, "url: https://w/" + name, "tags: 基础", "-->", "",
                "=== " + title + " === <!-- id: " + id + " -->", text);
    }

    // ==================== 读 ====================

    @AfterEach
    void closeStores() {
        qaStore.close();
    }

    @Test
    @DisplayName("stats / docs / blocks 三个读接口的形状")
    void readEndpoints() {
        assertThat(controller.stats()).containsEntry("available", true)
                .containsEntry("blocks", 0);

        controller.importDoc(body("text", doc("flame-altar", "flame-altar-0", "灵火祭坛", "祭坛是复活点。")));

        assertThat(controller.stats()).containsEntry("blocks", 1).containsEntry("documents", 1);
        assertThat(controller.docs()).extracting("documents").asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);

        Map<String, Object> blocks = controller.blocks("flame-altar");
        assertThat(blocks).containsEntry("doc", "flame-altar");
        assertThat((List<?>) blocks.get("blocks")).hasSize(1);
    }

    // ==================== 预览 / 导入 ====================

    @Test
    @DisplayName("★ preview 只算不改，并把「会改多少条 + 哪些 id」回给界面")
    void previewShape() {
        controller.importDoc(body("text", doc("flame-altar", "flame-altar-0", "灵火祭坛", "旧正文")));

        ResponseEntity<Map<String, Object>> res = controller.preview(
                body("text", doc("flame-altar", "flame-altar-0", "灵火祭坛", "新正文")));

        Map<String, Object> out = res.getBody();
        assertThat(out).containsEntry("added", 0)
                .containsEntry("updated", 1)
                .containsEntry("unchanged", 0)
                .containsEntry("untouchedExisting", 0);
        assertThat(out.get("updatedIds")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).containsExactly("flame-altar-0");
        assertThat(store.get("flame-altar-0").body()).as("预览不改数据").isEqualTo("旧正文");
    }

    @Test
    @DisplayName("★ import 按 id 覆盖 / 新增，没出现的块一个都不动")
    void importShape() {
        controller.importDoc(body("text", doc("d", "d-0", "甲", "甲正文")));
        controller.importDoc(body("text", doc("d", "d-1", "乙", "乙正文")));

        // 只给 d-1 的新正文
        ResponseEntity<Map<String, Object>> res = controller.importDoc(
                body("text", doc("d", "d-1", "乙", "乙新正文")));

        Map<String, Object> out = res.getBody();
        assertThat(out).containsEntry("added", 0).containsEntry("updated", 1).containsEntry("untouched", 1);
        assertThat(store.get("d-0").body()).as("没出现的那块原样").isEqualTo("甲正文");
        assertThat(store.get("d-1").body()).isEqualTo("乙新正文");
    }

    @Test
    @DisplayName("格式坏的文档 → 400 并把逐条原因带回去（界面直接显示）")
    void badDocumentGives400() {
        ResponseEntity<Map<String, Object>> res = controller.importDoc(
                body("text", doc("d", "中文id", "甲", "正文")));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(res.getBody().get("error"))).contains("id 只能由英文字母、数字和 - 组成");
        assertThat(store.count()).isZero();
    }

    // ==================== 写 ====================

    @Test
    @DisplayName("update：缺 id / 块不存在 / 正文为空 三种错都要分得清")
    void updateErrorPaths() {
        assertThat(controller.update(body("text", "x")).getStatusCode().value()).isEqualTo(400);

        assertThat(controller.update(body("id", "没有这个", "text", "正文")).getStatusCode().value())
                .isEqualTo(404);

        controller.importDoc(body("text", doc("d", "d-0", "甲", "正文")));
        ResponseEntity<Map<String, Object>> empty = controller.update(body("id", "d-0", "text", "   "));
        assertThat(empty.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(empty.getBody().get("error"))).contains("下架");
    }

    @Test
    @DisplayName("add：tags 支持数组和逗号字符串两种写法；中文文档名不给 id 要报错")
    void addShapes() {
        ResponseEntity<Map<String, Object>> ok = controller.add(body(
                "docId", "kiln", "id", "kiln-0", "title", "熔炉", "text", "熔炉用来烧制。",
                "tags", List.of("制作", "基础")));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> viaString = controller.add(body(
                "docId", "kiln", "id", "kiln-1", "title", "熔炉2", "text", "正文",
                "tags", "制作, 基础"));
        assertThat(viaString.getStatusCode().value()).isEqualTo(200);
        assertThat(store.get("kiln-1").tags()).containsExactly("制作", "基础");

        ResponseEntity<Map<String, Object>> bad = controller.add(body(
                "docId", "灵火祭坛", "title", "灵火祭坛", "text", "正文"));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(bad.getBody().get("error"))).contains("无法自动生成 id");
    }

    @Test
    @DisplayName("retire / delete：默认 retired=true；块不存在回 404")
    void retireAndDelete() {
        controller.add(body("docId", "d", "id", "d-0", "title", "甲", "text", "正文"));

        assertThat(controller.retire(body("id", "d-0")).getStatusCode().value()).isEqualTo(200);
        assertThat(store.get("d-0").retired()).isTrue();

        assertThat(controller.retire(body("id", "d-0", "retired", false)).getStatusCode().value())
                .isEqualTo(200);
        assertThat(store.get("d-0").retired()).isFalse();

        assertThat(controller.retire(body("id", "没有")).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.delete(body("id", "没有")).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.delete(body("id", "d-0")).getStatusCode().value()).isEqualTo(200);
        assertThat(store.count()).isZero();
    }

    @Test
    @DisplayName("retire-doc：整份下架并回报影响了几块")
    void retireDoc() {
        controller.add(body("docId", "d", "id", "d-0", "title", "甲", "text", "正文"));
        controller.add(body("docId", "d", "id", "d-1", "title", "乙", "text", "正文"));
        controller.add(body("docId", "e", "id", "e-0", "title", "丙", "text", "正文"));

        ResponseEntity<Map<String, Object>> res = controller.retireDoc(body("doc", "d", "retired", true));

        assertThat(res.getBody()).containsEntry("affected", 2);
        assertThat(store.get("e-0").retired()).as("别的文档不受影响").isFalse();
    }

    @Test
    @DisplayName("clear：清空全部块")
    void clear() {
        controller.importDoc(body("text", doc("d", "d-0", "甲", "正文")));
        assertThat(controller.clear().getStatusCode().value()).isEqualTo(200);
        assertThat(store.count()).isZero();
    }

    // ==================== 标题向量补建（C 路）====================

    @Test
    @DisplayName("title-vectors/backfill：dryRun 只报数量不写库，真跑才补；再跑一遍报 0（界面就靠这个契约）")
    void backfillTitleVectorsShape() {
        controller.importDoc(body("text", doc("d", "d-0", "酸蚀之咬（Acid Bite）", "魔法弹药。")));
        // 手工把标题向量删掉，模拟"功能上线前就在库里的老数据"
        store.deleteTitleVector("d-0");

        Map<String, Object> dry = controller.backfillTitleVectors(body("dryRun", true)).getBody();
        assertThat(dry).containsEntry("dryRun", true).containsEntry("pending", 1)
                .containsEntry("missing", 1).containsEntry("stale", 0).containsEntry("embedded", 0);
        assertThat(store.countTitleVectors()).as("dryRun 不写库").isZero();

        Map<String, Object> real = controller.backfillTitleVectors(body()).getBody();
        assertThat(real).containsEntry("dryRun", false).containsEntry("embedded", 1);
        assertThat(store.countTitleVectors()).isEqualTo(1);

        Map<String, Object> again = controller.backfillTitleVectors(null).getBody();
        assertThat(again).as("body 可以不传；幂等：没有要补的").containsEntry("pending", 0);
    }
}
