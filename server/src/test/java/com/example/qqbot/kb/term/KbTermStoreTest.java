package com.example.qqbot.kb.term;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.Glossary;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词条存储的验收测试。
 *
 * <p>它顶掉了原来的 {@code GlossaryStoreTest} + {@code GlossaryStoreConcurrencyTest}：
 * 那两个测试的大半篇幅在验证"整表重写不能丢并发写"——**文件当数据库**才会有的问题。
 * 换成单行 UPDATE 之后，这类测试只需要证明"并发写一条不少"就够了。
 *
 * <p>核心仍然要证明那件事：后台改了中文名之后，{@link Glossary#expand}
 * **不需要重启、也不需要调用方记得刷新**就能用到新词。
 */
class KbTermStoreTest {

    /** 这些测试不关心"页面从哪来"，给个空语料即可（entries() 默认返回空表） */
    private static com.example.qqbot.kb.KbCorpus kCorpus() {
        return org.mockito.Mockito.mock(com.example.qqbot.kb.KbCorpus.class);
    }


    @TempDir
    Path base;

    private QaStore qaStore;
    private SqliteDatabase db;
    private KbTermStore store;
    private Glossary glossary;

    @BeforeEach
    void setUp() {
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("qa.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        qaStore = new QaStore(new QaStoreRepository(new Jdbc(db)), qa, new ObjectMapper());
        qaStore.init();

        store = new KbTermStore(new KbTermRepository(new Jdbc(db)), kCorpus());
        store.init();
        glossary = new Glossary(store);
    }

    @AfterEach
    void closeStores() {
        db.close();
    }

    @Test
    @DisplayName("写入后读得到；空值或乱写的状态一律归 draft")
    void upsertAndNormalize() {
        assertThat(store.isAvailable()).isTrue();

        store.upsert("Scrap Cup", "废料杯", "draft");
        store.upsert("Flame Altar", "火焰祭坛", "VERIFIED");
        store.upsert("X", "某某", "瞎写的");

        assertThat(store.get("Scrap Cup").zh()).isEqualTo("废料杯");
        assertThat(store.get("Flame Altar").status()).isEqualTo("verified");
        assertThat(store.get("X").status()).as("认不出来的一律 draft").isEqualTo("draft");
        assertThat(store.list()).hasSize(3);
    }

    @Test
    @DisplayName("★ 新增一条后立即生效 —— 不重启、也不需要调用方记得 reload()")
    void writeTakesEffectWithoutManualReload() {
        assertThat(glossary.expand("灵火祭坛在哪")).as("新增前认不出来").doesNotContain("Flame Altar");

        store.upsert("Flame Altar", "灵火祭坛、火焰祭坛", "verified");

        assertThat(glossary.expand("灵火祭坛在哪")).as("★ 自动感知写入").contains("Flame Altar");
        assertThat(glossary.expand("火焰祭坛怎么升级")).contains("Flame Altar");
    }

    @Test
    @DisplayName("★ 改成 rejected 后立即失效")
    void rejectedTakesEffect() {
        store.upsert("Flame Altar", "火焰祭坛", "verified");
        assertThat(glossary.expand("火焰祭坛")).contains("Flame Altar");

        store.setStatus("Flame Altar", "rejected");

        assertThat(glossary.expand("火焰祭坛")).doesNotContain("Flame Altar");
    }

    @Test
    @DisplayName("清掉中文名后立即失效，但**行还在**（下次启动 reconcile 不会再补一遍）")
    void clearNameKeepsRow() {
        store.upsert("Flame Altar", "火焰祭坛", "verified");
        long before = store.count();

        assertThat(store.clearName("Flame Altar")).isTrue();

        assertThat(store.count()).as("行数不变").isEqualTo(before);
        assertThat(store.get("Flame Altar").zh()).isEmpty();
        assertThat(store.get("Flame Altar").status()).isEqualTo("draft");
        assertThat(glossary.expand("火焰祭坛")).doesNotContain("Flame Altar");
    }

    @Test
    @DisplayName("批量改状态：分得清 真改了 / 本来就对 / 没这条")
    void batchStatus() {
        store.upsert("A", "甲", "draft");
        store.upsert("B", "乙", "verified");
        store.upsert("C", "丙", "draft");

        KbTermStore.BatchResult r = store.setStatusBatch(List.of("A", "B", "C", "Nope"), "verified");

        assertThat(r.updated()).as("A 和 C 需要改").isEqualTo(2);
        assertThat(r.unchanged()).as("B 本来就是 verified").isEqualTo(1);
        assertThat(r.missing()).containsExactly("Nope");
        assertThat(store.get("A").status()).isEqualTo("verified");
        assertThat(store.get("C").status()).isEqualTo("verified");
    }

    @Test
    @DisplayName("导入 TSV 只认第 1/2/5 列，中间两列（派生信息）改了也没用")
    void importIgnoresDerivedColumns() {
        String tsv = """
                # 注释
                Scrap Cup\t废料杯\t随便写的页面名\t随便写的分类\tverified
                Flame Altar\t火焰祭坛\t\t\t
                只有一列会被跳过
                """;

        KbTermStore.ImportResult r = store.importTsv(tsv);

        assertThat(r.created()).isEqualTo(2);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(store.get("Scrap Cup").zh()).isEqualTo("废料杯");
        assertThat(store.get("Flame Altar").status()).as("缺状态列按 draft").isEqualTo("draft");
        assertThat(store.get("Flame Altar").zh()).isEqualTo("火焰祭坛");
    }

    @Test
    @DisplayName("★ 并发写：16 线程各写 8 条，一条都不能丢")
    void concurrentWritesLoseNothing() throws Exception {
        int threads = 16;
        int perThread = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.upsert("Term-" + tid + "-" + i, "词条" + tid + "-" + i, "draft");
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("线程应当在超时前跑完").isTrue();
        pool.shutdownNow();

        assertThat(failures.get()).as("不该有线程抛异常").isZero();
        assertThat(store.list()).as("★ 并发写不能丢任何一条").hasSize(threads * perThread);
    }

    @Test
    @DisplayName("语料不在时 reconcile 不报错，只是没得补")
    void reconcileWithoutCorpusIsFine() {
        assertThat(store.reconcile()).isZero();
        assertThat(store.count()).isZero();
    }
}
