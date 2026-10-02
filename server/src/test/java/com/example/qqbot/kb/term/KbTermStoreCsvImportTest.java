package com.example.qqbot.kb.term;

import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.KbCorpus;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbTermRepository;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 术语表导入（Excel → CSV）的验收测试。
 *
 * <p>术语表是**人工成果**，导入写错一次就是在污染它，所以这里重点钉三件事：
 * <ol>
 *   <li><b>Excel 的引号</b>：别名里带逗号时 Excel 会加引号，直接 split 会静默拆错；</li>
 *   <li><b>别名并进中文名</b>：库里别名就是 {@code zh} 用 {@code 、} 分隔的写法，
 *       不新加列 —— 但合并去重必须对；</li>
 *   <li><b>没有表头的老文件还能导</b>（按列位置），不能因为升级就不认旧文件了。</li>
 * </ol>
 */
class KbTermStoreCsvImportTest {

    @TempDir
    Path base;

    private QaStore qaStore;
    private SqliteDatabase db;

    private KbTermStore store;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        qa.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(qa);
        db.init();
        qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();

        store = new KbTermStore(new KbTermRepository(new Jdbc(db)), mock(KbCorpus.class));
        store.init();
    }

    @AfterEach
    void closeStores() {
        db.close();
    }

    @Test
    @DisplayName("★ CSV（中文表头）：别名并进中文名，用 、 分隔")
    void csvWithChineseHeader() {
        String csv = String.join("\n",
                "英文名,中文名,别名,状态",
                "Kiln,熔炉,炉子,verified",
                "Flame Altar,灵火祭坛,祭坛,verified");

        KbTermStore.ImportResult r = store.importTable(csv);

        assertThat(r.created()).isEqualTo(2);
        assertThat(store.get("Kiln").zh()).isEqualTo("熔炉、炉子");
        assertThat(store.get("Flame Altar").zh()).isEqualTo("灵火祭坛、祭坛");
        assertThat(store.get("Kiln").status()).isEqualTo("verified");
    }

    @Test
    @DisplayName("★ Excel 的引号：别名里带逗号时必须当一个字段（直接 split 会静默拆错）")
    void quotedAliasWithComma() {
        String csv = String.join("\n",
                "英文名,中文名,别名,状态",
                "Kiln,熔炉,\"炉子,窑\",verified");

        store.importTable(csv);

        assertThat(store.get("Kiln").zh())
                .as("一个别名里的逗号不能被当成列分隔符")
                .isEqualTo("熔炉、炉子、窑");
    }

    @Test
    @DisplayName("英文表头 / id 作列名都认（与块 id 对齐）")
    void englishHeadersAndIdColumn() {
        store.importTable(String.join("\n",
                "id,zh,alias,status",
                "flame-altar,灵火祭坛,祭坛,draft",
                "kiln,熔炉,,draft"));

        assertThat(store.count()).isEqualTo(2);
        assertThat(store.get("flame-altar").zh()).isEqualTo("灵火祭坛、祭坛");
        assertThat(store.get("kiln").zh()).as("没有别名就只用中文名").isEqualTo("熔炉");
    }

    @Test
    @DisplayName("别名与中文名重复 → 去重（不出现「熔炉、熔炉」）")
    void aliasesAreDeduped() {
        store.importTable(String.join("\n",
                "英文名,中文名,别名",
                "Kiln,熔炉,熔炉/炉子"));

        assertThat(store.get("Kiln").zh()).isEqualTo("熔炉、炉子");
    }

    @Test
    @DisplayName("没有状态列 → 一律 draft；空行与 # 注释跳过")
    void defaultsAndSkippedLines() {
        store.importTable(String.join("\n",
                "# 这是我手动加的注释",
                "英文名,中文名",
                "",
                "Kiln,熔炉",
                ",缺英文名"));

        assertThat(store.count()).isEqualTo(1);
        assertThat(store.get("Kiln").status()).isEqualTo("draft");
    }

    @Test
    @DisplayName("★ 没有表头的老 TSV 文件还能导（按列位置，状态在第 5 列）")
    void legacyTsvWithoutHeaderStillWorks() {
        String tsv = String.join("\n",
                "Kiln\t熔炉\twiki页面\t类别\tverified",
                "Flame Altar\t灵火祭坛\twiki页面\t类别\tdraft");

        KbTermStore.ImportResult r = store.importTable(tsv);

        assertThat(r.created()).isEqualTo(2);
        assertThat(store.get("Kiln").zh()).isEqualTo("熔炉");
        assertThat(store.get("Kiln").status()).isEqualTo("verified");
    }

    @Test
    @DisplayName("同一份 CSV 导两次、以及改中文名再导：都是覆盖，不产生重复行")
    void reimportIsIdempotentAndOverwrites() {
        String csv = String.join("\n", "英文名,中文名,别名,状态", "Kiln,熔炉,炉子,verified");
        store.importTable(csv);
        KbTermStore.ImportResult again = store.importTable(csv);

        assertThat(again.created()).isZero();
        assertThat(again.updated()).isEqualTo(1);
        assertThat(store.count()).isEqualTo(1);

        store.importTable(String.join("\n", "英文名,中文名,状态", "Kiln,熔炼炉,verified"));
        assertThat(store.get("Kiln").zh()).as("以最新导入为准").isEqualTo("熔炼炉");
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("旧入口 importTsv 走的是同一套（自动识别分隔符）")
    void importTsvDelegates() {
        store.importTsv(String.join("\n", "英文名,中文名", "Kiln,熔炉"));
        assertThat(store.count()).isEqualTo(1);
    }
}
