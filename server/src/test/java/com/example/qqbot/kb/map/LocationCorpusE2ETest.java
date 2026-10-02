package com.example.qqbot.kb.map;

import com.example.qqbot.kb.wiki.WikiApiClient;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.ProjectFiles;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.EmbeddingClient;
import com.example.qqbot.kb.Glossary;
import com.example.qqbot.kb.KbRetriever;
import com.example.qqbot.kb.RerankClient;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.kb.term.KbTermStore;
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
 * 地点语料的**端到端**验收：真同步 → 真派生 → 真检索，看"地点类问题"到底能不能答。
 *
 * <p>默认跳过（{@code -Dqqbot.map.sync=on}）：会访问 wiki、会调向量与重排模型。
 *
 * <p>关键设计：它把**生产语料库复制一份**再做实验，所以测的是"物品语料 + 地点语料"
 * 混在一起的**真实检索环境**，而不是一个只有地点块的空库
 * （空库上任何查询都"命中"，证明不了什么）。
 */
class LocationCorpusE2ETest {

    @TempDir
    Path tmp;

    private QaStore qaStore;

    @AfterEach
    void tearDown() {
        if (qaStore != null) {
            qaStore.close();
        }
    }

    @Test
    @DisplayName("★ 同步→派生→检索：地点类问题从「库里没资料」变成能答")
    void deriveAndRetrieve() throws IOException {
        Assumptions.assumeTrue("on".equalsIgnoreCase(System.getProperty("qqbot.map.sync")),
                "需要 -Dqqbot.map.sync=on（会访问 wiki 并调用模型）");

        Path repo = findRepoRoot();
        Assumptions.assumeTrue(repo != null, "找不到工程根");
        Path prodDb = repo.resolve("server/data/qa/qqbot.sqlite");
        Assumptions.assumeTrue(Files.isRegularFile(prodDb), "没有生产语料库：" + prodDb);

        // 复制生产库 —— 在真实混合语料上做实验，绝不碰生产库
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
        qa.setDb(db.toString());
        qaStore = new QaStore(qa, mapper);
        qaStore.init();

        KbBlockStore blocks = new KbBlockStore(qaStore);
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
        RerankClient rerank = new RerankClient(kb, mapper);
        Assumptions.assumeTrue(embedding.isAvailable(), "需要 SILICONFLOW_API_KEY 才能向量化派生块");

        // ① 同步地图数据
        KbMapStore mapStore = new KbMapStore(qaStore);
        mapStore.init();
        WikiApiClient client = new WikiApiClient(kb, mapper);
        KbMapSyncService sync = new KbMapSyncService(client, mapStore);
        KbMapSyncService.SyncReport sr = sync.sync(false);
        System.out.printf("地图同步：%d 页 / %d 个 marker / 失败 %d%n", sr.parsed(), sr.markers(), sr.failed());
        assertThat(sr.failed()).isZero();

        // ② 派生地点语料
        // 词条表要在派生之前就绪：派生器写完块会调 reconcile，让新块**立刻**对 B 路可见
        KbTermStore terms = new KbTermStore(qaStore, kb, mapper, index);
        terms.init();
        LocationCorpusBuilder builder = new LocationCorpusBuilder(mapStore, blocks, index, embedding, terms);
        LocationCorpusBuilder.BuildReport plan = builder.planOnly();
        System.out.printf("派生计划：%d 条（区域 %d / POI %d / 具名地点 %d / NPC %d），排除 Lore %d 条%n",
                plan.entries(), plan.regions(), plan.pois(), plan.places(), plan.npcs(), plan.skippedLore());
        plan.samples().forEach(s -> System.out.println("    " + s));
        assertThat(plan.entries()).isGreaterThan(50);
        assertThat(plan.regions()).isGreaterThanOrEqualTo(6);

        LocationCorpusBuilder.BuildReport built = builder.build(false);
        System.out.printf("已写入 %d 条；块总数 %d -> %d%n", built.written(), before, blocks.count());
        assertThat(built.written()).isEqualTo(built.entries());
        assertThat(blocks.count()).isGreaterThan(before);

        // ③ 真检索：地点类问题现在能不能拿到资料
        KbRetriever retriever = new KbRetriever(kb, index, new Glossary(terms), embedding, rerank);

        for (String q : List.of("春之原野在哪", "灵火祭坛在哪", "铁匠在哪", "空洞大厅有什么")) {
            List<KbRetriever.Hit> hits = retriever.retrieve(q).hits();
            System.out.printf("  问「%s」-> %s%n", q,
                    hits.isEmpty() ? "（空）" : hits.stream().map(h -> h.entry().title()).toList());
            assertThat(hits).as("地点问题「" + q + "」应当能拿到资料").isNotEmpty();
        }

        // ③b 诊断：聚合型提问的完整排名 —— 地点块到底排第几、与第一名差多少分。
        //     这决定了 D8 该从语料侧修（让地点块自己赢）还是从排序侧修（显式提权）。
        List<KbRetriever.Hit> deep = retriever.retrieve("空洞大厅有什么", true, 20, 0f, true).hits();
        System.out.println("  ---- 「空洞大厅有什么」top20 诊断 ----");
        for (int i = 0; i < deep.size(); i++) {
            KbRetriever.Hit h = deep.get(i);
            boolean loc = h.entry().docId() != null && h.entry().docId().startsWith("地图·地点");
            System.out.printf("    %2d. %.4f %s %s%n", i + 1, h.score(), loc ? "★地点" : "     ", h.entry().title());
        }

        // ③c ★ 聚合提问必须让**地点块**排第一 —— 用**默认参数**（top-k=5）断言。
        //     上一版只在 topK=20 下测通过，而生产用的是 top-k=5：
        //     重排在提权之前就截到 5 条，地点块（候选里第 7）已经没了 —— 假通过。
        List<KbRetriever.Hit> agg = retriever.retrieve("空洞大厅有什么").hits();
        System.out.printf("  聚合提问（默认 top-k）-> %s%n", agg.stream().map(h -> h.entry().title()).toList());
        assertThat(agg).isNotEmpty();
        assertThat(agg.get(0).entry().docId()).as("聚合提问第一条应当是地点块").startsWith("地图·地点");
        assertThat(agg.get(0).entry().title()).contains("空洞大厅");

        // ④ 可回滚：purge 之后块数回到原值
        int purged = builder.purge();
        System.out.printf("回滚：清除 %d 条，块总数回到 %d%n", purged, blocks.count());
        assertThat(purged).isEqualTo(built.entries());
        assertThat(blocks.count()).isEqualTo(before);
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
