package com.example.qqbot.kb;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.term.KbTermStore;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbTermRepository;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 术语匹配逻辑测试（词形匹配，与存储无关）。
 *
 * <p>其中「中文→英文方向」这条是**真跑端到端才发现的 bug**：
 * 原来把 key/value 用反了，导致中文问题一个英文词都扩展不出来，B 路形同虚设。
 *
 * <p>数据源已从 TSV 文件换成 {@link KbTermStore}（SQLite 单表），
 * 所以这里用一张临时库来喂同样的数据 —— 顺带证明"改存储没有改匹配语义"。
 */
class GlossaryTest {

    /** 这些测试不关心"页面从哪来"，给个空语料即可（entries() 默认返回空表） */
    private static com.example.qqbot.kb.KbCorpus kCorpus() {
        return org.mockito.Mockito.mock(com.example.qqbot.kb.KbCorpus.class);
    }


    @TempDir
    Path base;

    /** glossaryWith() 每调一次就开一个库，全部留着在 @AfterEach 里关掉 */
    private final java.util.List<SqliteDatabase> createdDbs = new java.util.ArrayList<>();

    /** 用若干「英文名 → 中文名 / 状态」建一个真的词条库，再包成 Glossary */
    private Glossary glossaryWith(String... enZhStatus) {
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa-" + System.nanoTime() + ".sqlite").toString());
        SqliteDatabase db = new SqliteDatabase(persist);
        db.init();
        createdDbs.add(db);
        QaStore qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();

        KbTermStore store = new KbTermStore(new KbTermRepository(new Jdbc(db)), kCorpus());
        store.init();
        for (int i = 0; i + 2 < enZhStatus.length; i += 3) {
            store.upsert(enZhStatus[i], enZhStatus[i + 1], enZhStatus[i + 2]);
        }
        return new Glossary(store);
    }

    @AfterEach
    void closeStores() {
        for (SqliteDatabase d : createdDbs) {
            d.close();
        }
    }

    @Test
    @DisplayName("★ 中文问题能扩展出英文术语（方向不能反）")
    void expandsChineseToEnglish() {
        Glossary glossary = glossaryWith(
                "Scrap Cup", "废料杯", "draft",
                "Flame Altar", "火焰祭坛", "verified");

        assertThat(glossary.expand("废料杯怎么合成？")).contains("Scrap Cup");
        assertThat(glossary.expand("火焰祭坛在哪")).contains("Flame Altar");
        assertThat(glossary.expand("今天天气怎么样")).doesNotContain("Scrap Cup");
    }

    @Test
    @DisplayName("用户直接打英文时，英文词也能作为查询词")
    void keepsAsciiWordsFromQuery() {
        Glossary glossary = glossaryWith("Scrap Cup", "废料杯", "draft");

        assertThat(glossary.expand("Explosive Arrow 怎么获得")).contains("Explosive").contains("Arrow");
    }

    @Test
    @DisplayName("罗马数字被过滤掉（III 会匹配到一大片装备）")
    void filtersRomanNumerals() {
        Glossary glossary = glossaryWith("Scrap Cup", "废料杯", "draft");

        assertThat(glossary.expand("Explosive Arrow III")).doesNotContain("III");
    }

    @Test
    @DisplayName("rejected 的条目不参与检索；单个汉字的中文名也不收（会命中一大片）")
    void skipsRejectedAndTooShort() {
        Glossary glossary = glossaryWith(
                "Scrap Cup", "废料杯", "rejected",
                "Bad Thing", "坏东西", "draft",
                "Sword", "剑", "draft");

        assertThat(glossary.expand("废料杯")).doesNotContain("Scrap Cup");
        assertThat(glossary.expand("坏东西")).contains("Bad Thing");
        assertThat(glossary.size()).as("只剩「坏东西」一个别名").isEqualTo(1);
    }

    @Test
    @DisplayName("一个英文名可以配多个中文别名（人工核对时补俗名用）")
    void supportsMultipleAliases() {
        Glossary glossary = glossaryWith("Flame Altar", "灵火祭坛、火焰祭坛", "verified");

        assertThat(glossary.expand("灵火祭坛在哪")).contains("Flame Altar");
        assertThat(glossary.expand("火焰祭坛怎么升级")).contains("Flame Altar");
        assertThat(glossary.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 最长优先：爆炸箭 III 不该同时命中 爆炸箭 I 和 II")
    void prefersLongestMatch() {
        Glossary glossary = glossaryWith(
                "Explosive Arrow I", "爆炸箭 I", "draft",
                "Explosive Arrow II", "爆炸箭 II", "draft",
                "Explosive Arrow III", "爆炸箭 III", "draft");

        List<Glossary.Term> terms = glossary.matchChinese("爆炸箭 III 是什么？");

        assertThat(terms).hasSize(1);
        assertThat(terms.get(0).en()).isEqualTo("Explosive Arrow III");
    }

    @Test
    @DisplayName("没有任何词条时不报错，只是扩展不出东西")
    void emptyIsFine() {
        assertThat(glossaryWith().expand("废料杯怎么合成")).isEmpty();
    }

    /* ==================== 中文子串匹配（不依赖人工写别名）==================== */

    @Test
    @DisplayName("★ 问句里的短词命中更长的名字：「抱枕怎么得」→「大型农场动物抱枕」")
    void fragmentMatchesLongerName() {
        Glossary g = glossaryWith("big-farm-animal-pillow", "大型农场动物抱枕", "draft");

        assertThat(g.expand("抱枕怎么得")).contains("大型农场动物抱枕");
    }

    @Test
    @DisplayName("片段在名字中间也能命中（「农场动物」是「大型农场动物抱枕」的一段）")
    void fragmentMatchesMiddle() {
        Glossary g = glossaryWith("big-farm-animal-pillow", "大型农场动物抱枕", "draft");

        assertThat(g.expand("农场动物在哪")).contains("大型农场动物抱枕");
    }

    @Test
    @DisplayName("★ 太泛的片段整段丢掉（等于一个会跟着语料变的停用词表）")
    void tooCommonFragmentIsDropped() {
        String[] many = new String[45 * 3];
        for (int i = 0; i < 45; i++) {
            many[i * 3] = "mat-" + i;
            many[i * 3 + 1] = "材料" + i + "号";
            many[i * 3 + 2] = "draft";
        }
        Glossary g = glossaryWith(many);

        assertThat(g.expand("材料")).as("命中 45 个名字 → 这个片段没有区分度").isEmpty();
    }

    @Test
    @DisplayName("只命中几个名字的片段要保留（别把有用的也滤掉）")
    void rareFragmentIsKept() {
        String[] few = new String[3 * 3];
        for (int i = 0; i < 3; i++) {
            few[i * 3] = "mat-" + i;
            few[i * 3 + 1] = "材料" + i + "号";
            few[i * 3 + 2] = "draft";
        }
        Glossary g = glossaryWith(few);

        assertThat(g.expand("材料")).containsExactly("材料0号", "材料1号", "材料2号");
    }

    @Test
    @DisplayName("精确命中过的名字不重复塞进去")
    void exactMatchNotDuplicated() {
        Glossary g = glossaryWith("big-farm-animal-pillow", "大型农场动物抱枕", "draft");

        List<String> terms = g.expand("大型农场动物抱枕怎么得");

        assertThat(terms.stream().filter(t -> t.equals("大型农场动物抱枕")).count())
                .as("同一个名字只出现一次").isEqualTo(1);
    }

    @Test
    @DisplayName("问句和名字没有交集时，不乱匹配")
    void noOverlapMeansNoMatch() {
        Glossary g = glossaryWith("big-farm-animal-pillow", "大型农场动物抱枕", "draft");

        assertThat(g.expand("今天天气怎么样")).isEmpty();
    }

    @Test
    @DisplayName("片段匹配最多只补 8 个名字（不能把关键词路的扫描量撑爆）")
    void fragmentMatchesAreCapped() {
        String[] many = new String[20 * 3];
        for (int i = 0; i < 20; i++) {
            many[i * 3] = "pillow-" + i;
            many[i * 3 + 1] = "动物抱枕" + i + "号";
            many[i * 3 + 2] = "draft";
        }
        Glossary g = glossaryWith(many);

        assertThat(g.expand("抱枕")).hasSizeLessThanOrEqualTo(8);
    }
}
