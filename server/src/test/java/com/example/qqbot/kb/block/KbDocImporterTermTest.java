package com.example.qqbot.kb.block;

import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.KbCorpus;
import com.example.qqbot.kb.term.KbTermStore;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「**一个块 = 一个词条**」的验收测试。
 *
 * <p>为什么必须是一块一条：一份文档里有很多块，每块讲一个东西（一把武器、一个材料）。
 * 按文档建词条的话，导一份 147 块的图鉴只会多出 1 个词条 —— 那根本没法"通过词条定位分块"。
 *
 * <p>词条身份 = **块 id**，中文名 = **块标题**。同时钉住两条保护：
 * 标题里 {@code （English）} 要拆成别名（短中文名才匹配得上），
 * 以及**人工改过的名字不能被下次导入冲掉**。
 */
class KbDocImporterTermTest {

    @TempDir
    Path base;

    private KbTermStore termStore;
    private KbDocImporter importer;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        qa.setDb(base.resolve("qa.sqlite").toString());
        QaStore qaStore = new QaStore(qa, new ObjectMapper());
        qaStore.init();

        KbBlockStore store = new KbBlockStore(qaStore);
        store.init();
        KbBlockIndex index = new KbBlockIndex(store);

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.embedOne(anyString())).thenAnswer(inv -> new float[]{1f, 0f, 0f});

        KbProperties props = new KbProperties();
        props.setDir(base.resolve("kb").toString());
        termStore = new KbTermStore(qaStore, props, new ObjectMapper(), mock(KbCorpus.class));
        termStore.init();

        importer = new KbDocImporter(store, index, embedding, termStore);
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

    @Test
    @DisplayName("★ 一份 3 块的文档 → 3 个词条（不是 1 个）")
    void oneTermPerBlock() {
        KbDocImporter.Result r = importer.importDocument(doc("物品图鉴·武器",
                "废料杯", "scrap-cup",
                "灵火祭坛", "flame-altar",
                "熔炉", "kiln"));

        assertThat(r.terms()).as("返回里带上了登记条数").isEqualTo(3);
        assertThat(termStore.count()).as("★ 三个块 → 三个词条").isEqualTo(3);
        assertThat(termStore.get("scrap-cup").zh()).isEqualTo("废料杯");
        assertThat(termStore.get("flame-altar").zh()).isEqualTo("灵火祭坛");
        assertThat(termStore.get("kiln").zh()).isEqualTo("熔炉");
        assertThat(termStore.get("scrap-cup").status()).isEqualTo("draft");
    }

    @Test
    @DisplayName("★ 标题里的「（English）」拆成别名 —— 短中文名才匹配得上群友的问法")
    void englishInParensBecomesAlias() {
        importer.importDocument(doc("物品图鉴·双手武器",
                "深渊之翼斧（Abyssal Wing Axe）", "abyssal-wing-axe"));

        assertThat(termStore.get("abyssal-wing-axe").zh())
                .isEqualTo("深渊之翼斧、Abyssal Wing Axe");
    }

    @Test
    @DisplayName("括号不在结尾时不乱动")
    void parensInMiddleKeptAsIs() {
        importer.importDocument(doc("d", "前缀（说明）后缀", "weird-0"));

        assertThat(termStore.get("weird-0").zh()).isEqualTo("前缀（说明）后缀");
    }

    @Test
    @DisplayName("★ 已经有人工改过的名字 → 再导一次**不覆盖**（人工成果不能被冲掉）")
    void doesNotClobberHumanEditedName() {
        importer.importDocument(doc("d", "灵火祭坛", "flame-altar"));
        termStore.upsert("flame-altar", "灵火圣坛", "verified");

        KbDocImporter.Result r = importer.importDocument(doc("d", "灵火祭坛改名了", "flame-altar"));

        KbTermStore.Entry t = termStore.get("flame-altar");
        assertThat(t.zh()).as("★ 人工改过的名字没被冲掉").isEqualTo("灵火圣坛");
        assertThat(t.status()).isEqualTo("verified");
        assertThat(r.terms()).as("没有新建/补齐").isZero();
    }

    @Test
    @DisplayName("词条存在但中文名是空的 → 补齐（只补空，不算覆盖）")
    void fillsBlankName() {
        termStore.upsert("flame-altar", "", "draft");
        KbDocImporter.Result r = importer.importDocument(doc("d", "灵火祭坛", "flame-altar"));

        assertThat(termStore.get("flame-altar").zh()).isEqualTo("灵火祭坛");
        assertThat(r.terms()).isEqualTo(1);
    }

    @Test
    @DisplayName("幂等：同一份文档导两次，第二次不再新建词条")
    void reimportDoesNotDuplicateTerm() {
        String d = doc("d", "灵火祭坛", "flame-altar", "熔炉", "kiln");
        importer.importDocument(d);
        KbDocImporter.Result second = importer.importDocument(d);

        assertThat(second.terms()).isZero();
        assertThat(termStore.count()).as("两条 + 没多").isEqualTo(2);
    }

    @Test
    @DisplayName("块没写 id 时（标题顶替）也要有词条 —— 名字就是标题")
    void blockWithoutIdStillGetsTerm() {
        String text = "<!-- doc" + '\n' + "name: d" + '\n' + "-->" + '\n' + '\n'
                + "=== 无名块 ===" + '\n' + "正文。" + '\n';

        importer.importDocument(text);

        assertThat(termStore.get("无名块")).as("没 id → 词条身份用标题").isNotNull();
    }
}
