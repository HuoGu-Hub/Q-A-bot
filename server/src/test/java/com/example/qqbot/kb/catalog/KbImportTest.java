package com.example.qqbot.kb.catalog;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.EntryId;
import com.example.qqbot.kb.block.KbBlock;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.block.KbDocImporter;
import com.example.qqbot.kb.category.CategoryService;
import com.example.qqbot.kb.category.CategoryStore;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.KbCategoryRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * **导入 = 落块（= 落词条）** —— 文档导入把内容切割成一个个单独的词条。
 *
 * <h2>为什么这块必须钉死</h2>
 * "一个块 = 一个词条"是本模型的核心约定：一份 3 块的文档要出 3 个词条，不是 1 个。
 * 别名是人工成果，**再导一次不能被冲掉**。分类只认分块符号里的标签，
 * 标签不在清单里就归「未分类」（不做任何智能猜测）。
 */
class KbImportTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private KbBlockStore blocks;
    private CategoryService categories;
    private KbCatalog catalog;
    private KbImportController controller;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();

        blocks = new KbBlockStore(new KbBlockRepository(new Jdbc(db)));
        blocks.init();

        CategoryStore catStore = new CategoryStore(new KbCategoryRepository(new Jdbc(db)));
        catStore.init();
        categories = new CategoryService(catStore, blocks);

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenAnswer(inv -> new float[]{1f, 0f, 0f});
        when(embedding.embedChunked(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> {
                    java.util.List<?> in = inv.getArgument(0);
                    java.util.List<float[]> out = new java.util.ArrayList<>();
                    for (int i = 0; i < in.size(); i++) {
                        out.add(new float[]{1f, 0f, 0f});
                    }
                    return out;
                });

        KbDocImporter importer = new KbDocImporter(blocks, embedding);
        catalog = new KbCatalog(blocks, categories, importer);
        controller = new KbImportController(catalog);
    }

    @AfterEach
    void closeStores() {
        db.close();
    }

    /** 一份带文档头、含多个块的文档（文档头不参与命名，命名只看块标题） */
    private static String doc(String name, String... titleAndIdPairs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- doc").append('\n')
                .append("name: ").append(name).append('\n')
                .append("url: https://example.invalid/").append(name).append('\n')
                .append("-->").append('\n').append('\n');
        for (int i = 0; i + 1 < titleAndIdPairs.length; i += 2) {
            sb.append("=== ").append(titleAndIdPairs[i]).append(" === <!-- id: ")
                    .append(titleAndIdPairs[i + 1]).append(" -->").append('\n')
                    .append("正文。").append('\n').append('\n');
        }
        return sb.toString();
    }

    /** 一份单块的文档：文档名 / id / 标题 / 正文分开给 —— 端点测试要能分别控制它们 */
    private static String single(String name, String id, String title, String text) {
        return String.join("\n",
                "<!-- doc", "name: " + name, "url: https://w/" + name, "tags: 基础", "-->", "",
                "=== " + title + " === <!-- id: " + id + " -->", text);
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("★ 一份 3 块的文档 → 3 个词条（不是 1 个）")
    void oneTermPerBlock() {
        catalog.importDocument(doc("物品图鉴·武器",
                "废料杯", "scrap-cup",
                "灵火祭坛", "flame-altar",
                "熔炉", "kiln"));

        assertThat(blocks.count()).as("★ 三个块 → 三个词条").isEqualTo(3);
        assertThat(catalog.get("scrap-cup").title()).isEqualTo("废料杯");
        assertThat(catalog.get("flame-altar").title()).isEqualTo("灵火祭坛");
        assertThat(catalog.get("kiln").title()).isEqualTo("熔炉");
    }

    @Test
    @DisplayName("★ 标题里的「（English）」成为别名 —— 短中文名才匹配得上群友的问法")
    void englishInParensBecomesAlias() {
        catalog.importDocument(doc("物品图鉴·双手武器",
                "深渊之翼斧（Abyssal Wing Axe）", "abyssal-wing-axe"));

        assertThat(catalog.get("abyssal-wing-axe").alias()).isEqualTo("Abyssal Wing Axe");
        assertThat(catalog.get("abyssal-wing-axe").title()).isEqualTo("深渊之翼斧（Abyssal Wing Axe）");
    }

    @Test
    @DisplayName("括号不在结尾时不派生别名")
    void parensInMiddleKeptAsIs() {
        catalog.importDocument(doc("d", "前缀（说明）后缀", "weird-0"));

        assertThat(catalog.get("weird-0").alias()).isEmpty();
    }

    @Test
    @DisplayName("★ 已经人工写过的别名 → 再导一次**不覆盖**（人工成果不能被冲掉）")
    void doesNotClobberHumanEditedAlias() {
        catalog.importDocument(doc("d", "灵火祭坛", "flame-altar"));
        blocks.updateAlias(EntryId.of("flame-altar"), "灵火圣坛");

        catalog.importDocument(doc("d", "灵火祭坛改名了", "flame-altar"));

        assertThat(catalog.get("flame-altar").alias()).as("★ 人工写过的别名没被冲掉").isEqualTo("灵火圣坛");
    }

    @Test
    @DisplayName("幂等：同一份文档导两次，第二次不新建")
    void reimportDoesNotDuplicate() {
        String d = doc("d", "灵火祭坛", "flame-altar", "熔炉", "kiln");
        catalog.importDocument(d);
        catalog.importDocument(d);

        assertThat(blocks.count()).as("两条 + 没多").isEqualTo(2);
    }

    @Test
    @DisplayName("块没写 id 时（标题顶替）也要有词条 —— 身份就是标题")
    void blockWithoutIdStillGetsTerm() {
        String text = "<!-- doc" + '\n' + "name: d" + '\n' + "-->" + '\n' + '\n'
                + "=== 无名块 ===" + '\n' + "正文。" + '\n';

        catalog.importDocument(text);

        assertThat(blocks.get(EntryId.of("无名块"))).as("没 id → 词条身份用标题").isNotNull();
    }

    // ==================== 分类：只认标签，不做智能分类 ====================

    @Test
    @DisplayName("★ 标签在分类清单里 → 归到那个分类")
    void tagInCatalogBecomesCategory() {
        categories.add("武器", "武器");
        catalog.importDocument(String.join("\n",
                "<!-- doc", "name: d", "tags: 武器", "-->", "",
                "=== 灵火祭坛 === <!-- id: flame-altar -->", "正文。"));

        assertThat(catalog.get("flame-altar").categories()).containsExactly("武器");
        assertThat(catalog.get("flame-altar").uncategorized()).isFalse();
    }

    @Test
    @DisplayName("★ 标签不在清单里（或压根没标签）→ 未分类，不做任何猜测")
    void unknownTagIsUncategorized() {
        catalog.importDocument(String.join("\n",
                "<!-- doc", "name: d", "tags: 武器", "-->", "",
                "=== 灵火祭坛 === <!-- id: flame-altar -->", "正文。"));
        catalog.importDocument(doc("d2", "熔炉", "kiln"));

        assertThat(catalog.get("flame-altar").uncategorized()).as("清单里没有「武器」").isTrue();
        assertThat(catalog.get("kiln").uncategorized()).isTrue();
        assertThat(categories.uncategorizedCount()).isEqualTo(2);
    }

    // ==================== HTTP 形状 ====================

    @Test
    @DisplayName("★ /blocks/import 按 id 覆盖 / 新增，没出现的块一个都不动")
    void importShape() {
        controller.importDoc(body("text", single("d", "d-0", "甲", "甲正文")));
        controller.importDoc(body("text", single("d", "d-1", "乙", "乙正文")));

        ResponseEntity<Map<String, Object>> res = controller.importDoc(
                body("text", single("d", "d-1", "乙", "乙新正文")));

        Map<String, Object> out = res.getBody();
        assertThat(out).containsEntry("added", 0).containsEntry("updated", 1).containsEntry("untouched", 1);
        assertThat(blocks.get(EntryId.of("d-0")).body()).as("没出现的那块原样").isEqualTo("甲正文");
        assertThat(blocks.get(EntryId.of("d-1")).body()).isEqualTo("乙新正文");
    }

    @Test
    @DisplayName("格式坏的文档 → 400 并把逐条原因带回去（界面直接显示）")
    void badDocumentGives400() {
        ResponseEntity<Map<String, Object>> res = controller.importDoc(
                body("text", single("d", "中文id", "甲", "正文")));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(res.getBody().get("error"))).contains("id 只能由英文字母、数字和 - 组成");
        assertThat(blocks.count()).isZero();
    }

    @Test
    @DisplayName("别名写入：POST /kb/terms 只改别名，不碰正文")
    void aliasEndpointOnlyTouchesAlias() {
        catalog.importDocument(doc("d", "灵火祭坛", "flame-altar"));
        KbCatalogController termController = new KbCatalogController(catalog,
                new com.example.qqbot.kb.block.KbBlockAdminService(blocks, mock(EmbeddingClient.class),
                        new KbDocImporter(blocks, mock(EmbeddingClient.class))));

        termController.saveAlias(body("id", "flame-altar", "alias", "灵火圣坛、Flame Altar"));

        KbBlock b = blocks.get(EntryId.of("flame-altar"));
        assertThat(b.alias()).isEqualTo("灵火圣坛、Flame Altar");
        assertThat(b.body()).as("正文没被动").isEqualTo("正文。");
    }
}
