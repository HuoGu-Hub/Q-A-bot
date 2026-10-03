package com.example.qqbot.kb.wiki;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.Glossary;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.kb.RerankClient;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.term.KbTermStore;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.KbTermRepository;
import com.example.qqbot.persistence.KbWikiPageRepository;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * wiki 文章导入的**端到端**验收：真拉 wiki → 真清洗 → 真向量化 → 真检索。
 *
 * <p>默认跳过（{@code -Dqqbot.wiki.import=on}）：会访问 wiki、会调向量与重排模型。
 *
 * <p>规模刻意压小（每个来源 30 页）—— 全量任务有 155 篇，那是运维动作，
 * 不该塞进每次构建。这里验的是**链路通不通、增量对不对、回滚干不干净**。
 *
 * <p>跑在**临时库**上，绝不碰生产库。
 */
class WikiArticleImporterE2ETest {

    @TempDir
    Path tmp;

    private QaStore qaStore;
    private SqliteDatabase sqlite;

    @AfterEach
    void tearDown() {
        if (qaStore != null) {
            sqlite.close();
        }
    }

    @Test
    @DisplayName("★ 导入任务+机制：清洗入库 / 增量空转 / 能检索 / 可回滚")
    void importAndRetrieve() throws IOException {
        Assumptions.assumeTrue("on".equalsIgnoreCase(System.getProperty("qqbot.wiki.import")),
                "需要 -Dqqbot.wiki.import=on（会访问 wiki 并调用模型）");

        Path repo = findRepoRoot();
        Assumptions.assumeTrue(repo != null, "找不到工程根");
        Path prodDb = repo.resolve("server/data/qa/qqbot.sqlite");
        Assumptions.assumeTrue(Files.isRegularFile(prodDb), "没有生产语料库：" + prodDb);

        Path db = tmp.resolve("corpus.sqlite");
        Files.copy(prodDb, db);
        for (String side : new String[]{"-wal", "-shm"}) {
            Path s = Path.of(prodDb + side);
            if (Files.exists(s)) {
                Files.copy(s, Path.of(db + side));
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        QaProperties qa = new QaProperties();
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(db.toString());
        sqlite = new SqliteDatabase(persist);
        sqlite.init();
        qaStore = new QaStore(new QaStoreRepository(new Jdbc(sqlite)), qa, mapper);
        qaStore.init();

        KbBlockStore blocks = new KbBlockStore(new KbBlockRepository(new Jdbc(sqlite)));
        blocks.init();
        KbBlockIndex index = new KbBlockIndex(blocks);
        index.reload();
        int before = blocks.count();

        KbProperties kb = new KbProperties();
        String key = readEnv(repo.resolve(".env"), "SILICONFLOW_API_KEY");
        if (key != null && !key.isEmpty()) {
            kb.getEmbedding().setApiKey(key);
            kb.getRerank().setApiKey(key);
        }
        EmbeddingClient embedding = new EmbeddingClient(kb, mapper);
        Assumptions.assumeTrue(embedding.isAvailable(), "需要 SILICONFLOW_API_KEY");

        // 压小规模：任务只取前 30 页，另加两个机制类
        kb.getWikiImport().setPrefixes(List.of("Quests/"));
        kb.getWikiImport().setCategories(List.of("Gameplay", "Bosses"));
        kb.getWikiImport().setMaxPagesPerSource(30);

        KbWikiPageStore pageStore = new KbWikiPageStore(new KbWikiPageRepository(new Jdbc(sqlite)));
        pageStore.init();
        WikiApiClient client = new WikiApiClient(kb, mapper);
        // 词条表要在导入之前就绪：导入器写完块会调 reconcile，让新块**立刻**对 B 路可见
        KbTermStore terms = new KbTermStore(new KbTermRepository(new Jdbc(sqlite)), index);
        terms.init();
        WikiArticleImporter importer = new WikiArticleImporter(kb, client, pageStore, blocks, index, embedding, terms);

        // ① dryRun：只列页、比版本
        WikiArticleImporter.ImportReport dry = importer.importAll(true);
        System.out.printf("dryRun：来源 %d 个 / 页面 %d 篇 / 需导入 %d 篇  bySource=%s%n",
                dry.sources(), dry.pages(), dry.changed(), dry.bySource());
        assertThat(dry.pages()).isGreaterThan(20);

        // ② 真导入
        long termsBefore = terms.count();
        WikiArticleImporter.ImportReport r = importer.importAll(false);
        System.out.printf("导入：写入 %d 块 / 正文 %d 字 / 失败 %d%n", r.imported(), r.chars(), r.failed());
        r.errors().stream().limit(8).forEach(e -> System.out.println("   ! " + e));
        assertThat(r.failed()).as("失败：" + r.errors()).isZero();
        assertThat(r.imported()).isGreaterThan(20);
        assertThat(blocks.count()).isEqualTo(before + r.imported());
        // ★ 本轮修的东西：新块必须**立刻**对 B 路（关键词）可见，不必等重启。
        //   词条表只在 KbTermStore.init() 时 reconcile 一次；导入器写完块要再补一次，
        //   否则运行期导入的块在下次重启前进不了词表 —— 中文提问走关键词路就永远找不到它。
        assertThat(terms.count())
                .as("导入后词条应当同步长出来（否则新块对 B 路不可见）")
                .isEqualTo(termsBefore + r.imported());

        // ③ 增量：立刻再跑一次，revid 没变 → 应当一个块都不写
        WikiArticleImporter.ImportReport again = importer.importAll(false);
        assertThat(again.changed()).as("revid 未变，第二次不该再处理").isZero();
        assertThat(again.imported()).isZero();

        // ④ 真检索：任务与机制类问题能不能拿到资料
        KbRetriever retriever = new KbRetriever(kb, index, new Glossary(terms), embedding,
                new RerankClient(kb, mapper));

        // ⚠️ 断言必须**明确要求命中导入的块**（id 以 wiki- 开头）。
        //    只断言"结果非空"是假成功 —— 物品语料本来就会返回一堆东西，
        //    导入块排不进前五也照样"非空"。这个坑第一版就踩了。
        int retrieved = 0;
        for (String q : List.of("蜂箱熏制器任务", "战斗机制 抗性", "独眼巨人 怎么打", "怎么制作护甲")) {
            List<KbRetriever.Hit> hits = retriever.retrieve(q).hits();
            boolean hasWiki = hits.stream().anyMatch(h -> h.entry().id().startsWith("wiki-"));
            if (hasWiki) {
                retrieved++;
            }
            System.out.printf("  %s 问「%s」-> %s%n", hasWiki ? "✅" : "❌", q,
                    hits.isEmpty() ? "（空）" : hits.stream().map(h -> h.entry().title()).toList());
        }
        System.out.printf("  导入块进前五的查询：%d/4%n", retrieved);
        // 实测 2/4（补中文名之前是 1/4）—— 仍是**已知缺口**，不是"全好了"。
        //   补了 NameZhIndex 之后：
        //     「蜂箱熏制器任务」-> 蜂巢喷烟器（A Beehive Smoker）第 1  ✅（原 ❌）
        //     「战斗机制 抗性」  -> Combat Mechanics 第 1                ✅
        //   仍然失败的两种，原因不同：
        //     「独眼巨人 怎么打」-> Boss 页已改名为「独眼巨人（Cyclops）」，
        //        但正文太短、而中文物品"独眼巨人头骨/战利品"有 6 条且字面完全命中 → 仍被压住。
        //        解法方向：给文章块补**中文摘要**（现在只有正文开头那一个中文名）。
        //     「怎么制作护甲」  -> Crafting 这类**机制词条语料里根本没有译名**，
        //        NameZhIndex 只能返回 null（**刻意不编**），标题保持英文 → 检索不到。
        //        解法方向：机制别名表，人工补一小批（Gameplay 只有 11 篇，是可控的）。
        //   门槛设在实测值上：已知缺口不该把构建常年卡红，但也不该被伪装成通过。
        assertThat(retrieved).as("至少命中两个导入块（实测 2/4，见上方注释）").isGreaterThanOrEqualTo(2);

        // ⑤ ★ 切块生效（2026-10-02）：超长页面产出**多块**，而不是被 substring 截掉。
        //    这条断言的意义是：以后谁把切块改回截断，这里会红。
        List<com.example.qqbot.persistence.KbWikiPageRepository.Page> pages = pageStore.pages();
        long multi = pages.stream().filter(p -> p.blockCount() > 1).count();
        int maxParts = pages.stream().mapToInt(com.example.qqbot.persistence.KbWikiPageRepository.Page::blockCount)
                .max().orElse(0);
        System.out.printf("  切块：%d/%d 页产出多块，最多一页 %d 块%n", multi, pages.size(), maxParts);
        assertThat(multi).as("应当有页面被切成多块 —— 全是 1 块就等于没切").isGreaterThan(0);
        // ⚠️ 只对**有块的页**要求记账：清洗后没有正文的纯模板页
        //    （Category:Debuffs / Equipment 这种）本来就不建块，blockCount=0 是对的
        assertThat(pages).allSatisfy(p -> {
            if (!p.blockId().isBlank()) {
                assertThat(p.blockCount()).as("有块就必须有 blockCount 记账（否则 purge 会漏块）")
                        .isGreaterThan(0);
            }
        });

        // 每一块都不超过单块上限 —— 这正是"不再截断"的直接体现。
        // +64 是给"中文名。前缀"留的余量（它是导入器加的，不属于切块内容）。
        int limit = kb.getWikiImport().getMaxBodyChars();
        List<com.example.qqbot.kb.block.KbBlock> importedBlocks = blocks.allActive().stream()
                .filter(b -> b.docId() != null && b.docId().startsWith("wiki·"))
                .toList();
        assertThat(importedBlocks).as("导入的块应当都在库里").hasSize(r.imported());
        assertThat(importedBlocks).allSatisfy(b ->
                assertThat(b.body().length()).as("块 %s 超过单块上限", b.id())
                        .isLessThanOrEqualTo(limit + 64));

        // ⑥ 回滚：purge 之后块数回到原值
        int purged = importer.purge();
        System.out.printf("回滚：清除 %d 块，块总数回到 %d%n", purged, blocks.count());
        assertThat(purged).as("purge 必须把**每一块**都清掉（含续块）").isEqualTo(r.imported());
        assertThat(blocks.count()).isEqualTo(before);
        assertThat(blocks.allActive().stream().filter(b -> b.docId() != null && b.docId().startsWith("wiki·")))
                .as("purge 后不该留下任何续块孤儿").isEmpty();

        // ⑦ ★ 回滚必须是**双向**的：purge 之后要能重新导入。
        //    原先 purge() 不清 kb_wiki_page 的状态行，而增量判据就是"revid 变过吗"——
        //    状态行留着 → 再导入**一篇都不处理** → purge 是一扇单向门。
        //    这条断言就是盯它的：把 purge 改回"只删块不清状态"会红。
        WikiArticleImporter.ImportReport again2 = importer.importAll(false);
        System.out.printf("回滚后重新导入：写入 %d 块（首次是 %d）%n", again2.imported(), r.imported());
        assertThat(again2.imported())
                .as("purge 后必须能重新导入 —— 否则回滚是单向门")
                .isEqualTo(r.imported());
        assertThat(blocks.count()).isEqualTo(before + r.imported());
    }

    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("tests/golden/retrieval-golden.tsv"))) {
                return p;
            }
        }
        return null;
    }

    private static String readEnv(Path envFile, String key) {
        if (!Files.isRegularFile(envFile)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith(key + "=")) {
                    return t.substring(key.length() + 1).trim();
                }
            }
        } catch (IOException ignored) {
            // 读不到就当没有
        }
        return null;
    }
}
