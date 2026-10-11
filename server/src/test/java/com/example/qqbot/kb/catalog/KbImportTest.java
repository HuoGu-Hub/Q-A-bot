package com.example.qqbot.kb.catalog;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.KbCorpus;
import com.example.qqbot.kb.KnowledgeChangedEvent;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.block.KbDocImporter;
import com.example.qqbot.kb.category.CategoryService;
import com.example.qqbot.kb.term.KbTermService;
import com.example.qqbot.kb.term.KbTermStore;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.KbTermRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * **导入 = 落块 + 登记名字** —— 一条跨两个真源的用例的验收测试。
 *
 * <p>它原先叫 {@code KbDocImporterTermTest}，住在块包里：那时导入器自己往词条表写名字，
 * 于是块层依赖名字层。现在两半由组合层编排（{@link KbCatalog#importDocument}），
 * 测试跟着用例搬到组合层。
 *
 * <h2>为什么这块必须钉死</h2>
 * 名字登记漏掉**不会报错**：块进库了、正文也在，只是中文提问够不到这些页。
 * 这类故障在日志里看不出来，只有"词条表少了几条"能发现。所以每条保护都要有断言：
 * <ul>
 *   <li>"一个块 = 一个词条"：一份 3 块的文档要出 3 个词条，不是 1 个；</li>
 *   <li>标题里的「（English）」要拆成别名 —— 短中文名才匹配得上群友的问法；</li>
 *   <li><b>人工改过的名字不能被下次导入冲掉</b>；</li>
 *   <li>幂等：同一份文档导两次，第二次不新建。</li>
 * </ul>
 */
class KbImportTest {

    @TempDir
    Path base;

    private SqliteDatabase db;
    private KbBlockStore blocks;
    private KbTermStore termStore;
    private ApplicationEventPublisher events;
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
        KbBlockIndex index = new KbBlockIndex(blocks);

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenAnswer(inv -> new float[]{1f, 0f, 0f});

        termStore = new KbTermStore(new KbTermRepository(new Jdbc(db)), mock(KbCorpus.class));
        termStore.init();

        events = mock(ApplicationEventPublisher.class);
        KbTermService termService = new KbTermService(termStore, events);
        KbDocImporter importer = new KbDocImporter(blocks, embedding);
        catalog = new KbCatalog(blocks, termStore, mock(CategoryService.class), importer, termService);
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
        KbCatalog.ImportOutcome out = catalog.importDocument(doc("物品图鉴·武器",
                "废料杯", "scrap-cup",
                "灵火祭坛", "flame-altar",
                "熔炉", "kiln"));

        assertThat(out.terms()).as("返回里带上了登记条数").isEqualTo(3);
        assertThat(termStore.count()).as("★ 三个块 → 三个词条").isEqualTo(3);
        assertThat(termStore.get("scrap-cup").zh()).isEqualTo("废料杯");
        assertThat(termStore.get("flame-altar").zh()).isEqualTo("灵火祭坛");
        assertThat(termStore.get("kiln").zh()).isEqualTo("熔炉");
        assertThat(termStore.get("scrap-cup").status()).isEqualTo("draft");
    }

    @Test
    @DisplayName("★ 标题里的「（English）」拆成别名 —— 短中文名才匹配得上群友的问法")
    void englishInParensBecomesAlias() {
        catalog.importDocument(doc("物品图鉴·双手武器",
                "深渊之翼斧（Abyssal Wing Axe）", "abyssal-wing-axe"));

        assertThat(termStore.get("abyssal-wing-axe").zh())
                .isEqualTo("深渊之翼斧、Abyssal Wing Axe");
    }

    @Test
    @DisplayName("括号不在结尾时不乱动")
    void parensInMiddleKeptAsIs() {
        catalog.importDocument(doc("d", "前缀（说明）后缀", "weird-0"));

        assertThat(termStore.get("weird-0").zh()).isEqualTo("前缀（说明）后缀");
    }

    @Test
    @DisplayName("★ 已经有人工改过的名字 → 再导一次**不覆盖**（人工成果不能被冲掉）")
    void doesNotClobberHumanEditedName() {
        catalog.importDocument(doc("d", "灵火祭坛", "flame-altar"));
        termStore.upsert("flame-altar", "灵火圣坛", "verified");

        KbCatalog.ImportOutcome out = catalog.importDocument(doc("d", "灵火祭坛改名了", "flame-altar"));

        KbTermStore.Entry t = termStore.get("flame-altar");
        assertThat(t.zh()).as("★ 人工改过的名字没被冲掉").isEqualTo("灵火圣坛");
        assertThat(t.status()).isEqualTo("verified");
        assertThat(out.terms()).as("没有新建/补齐").isZero();
    }

    @Test
    @DisplayName("词条存在但中文名是空的 → 补齐（只补空，不算覆盖）")
    void fillsBlankName() {
        termStore.upsert("flame-altar", "", "draft");
        KbCatalog.ImportOutcome out = catalog.importDocument(doc("d", "灵火祭坛", "flame-altar"));

        assertThat(termStore.get("flame-altar").zh()).isEqualTo("灵火祭坛");
        assertThat(out.terms()).isEqualTo(1);
    }

    @Test
    @DisplayName("幂等：同一份文档导两次，第二次不再新建词条")
    void reimportDoesNotDuplicateTerm() {
        String d = doc("d", "灵火祭坛", "flame-altar", "熔炉", "kiln");
        catalog.importDocument(d);
        KbCatalog.ImportOutcome second = catalog.importDocument(d);

        assertThat(second.terms()).isZero();
        assertThat(termStore.count()).as("两条 + 没多").isEqualTo(2);
    }

    @Test
    @DisplayName("块没写 id 时（标题顶替）也要有词条 —— 名字就是标题")
    void blockWithoutIdStillGetsTerm() {
        String text = "<!-- doc" + '\n' + "name: d" + '\n' + "-->" + '\n' + '\n'
                + "=== 无名块 ===" + '\n' + "正文。" + '\n';

        catalog.importDocument(text);

        assertThat(termStore.get("无名块")).as("没 id → 词条身份用标题").isNotNull();
    }

    @Test
    @DisplayName("★ 登记成功要通知公众站缓存失效 —— 老路径漏了这一步，只表现为「刚导完搜不到」")
    void registrationNotifiesKnowledgeChanged() {
        catalog.importDocument(doc("d", "灵火祭坛", "flame-altar"));

        verify(events).publishEvent(any(KnowledgeChangedEvent.class));
    }

    // ==================== HTTP 形状（端点住在组合层）====================

    @Test
    @DisplayName("★ /blocks/import 按 id 覆盖 / 新增，没出现的块一个都不动")
    void importShape() {
        controller.importDoc(body("text", single("d", "d-0", "甲", "甲正文")));
        controller.importDoc(body("text", single("d", "d-1", "乙", "乙正文")));

        // 只给 d-1 的新正文
        ResponseEntity<Map<String, Object>> res = controller.importDoc(
                body("text", single("d", "d-1", "乙", "乙新正文")));

        Map<String, Object> out = res.getBody();
        assertThat(out).containsEntry("added", 0).containsEntry("updated", 1).containsEntry("untouched", 1);
        assertThat(blocks.get("d-0").body()).as("没出现的那块原样").isEqualTo("甲正文");
        assertThat(blocks.get("d-1").body()).isEqualTo("乙新正文");
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
}
