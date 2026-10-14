package com.example.qqbot.kb;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.kb.block.KbBlockIndex;
import com.example.qqbot.kb.block.KbBlockStore;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.KbBlockRepository;
import com.example.qqbot.persistence.QaStoreRepository;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.qa.QaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.example.qqbot.kb.EntryId;

/**
 * 检索黄金集跑手 —— **回答质量的回归基线**。
 *
 * <h2>它解决什么问题</h2>
 * 现状 {@link KbRetrieverTest} 测的是"三路各自有没有工作"，不测"口语提问答得对不对"。
 * 没有度量，后面所有"口语问答变好了"的说法都只能靠感觉。
 *
 * <h2>怎么保证测的是真东西</h2>
 * <ul>
 *   <li>用**生产语料**（复制一份 {@code server/data/qa/qqbot.sqlite} 再读，不碰真库）；</li>
 *   <li>用**真实生产参数**（{@code top-k=5}、{@code min-score=0.45}、{@code title-gate=0.70}）；</li>
 *   <li>走**真实检索器** {@link KbRetriever}，不重新实现算法（否则就是两套必然漂移的算法）。</li>
 * </ul>
 *
 * <h2>四类分开统计</h2>
 * 混在一起算平均值会掩盖问题：现状 {@code entity} 类接近满分、{@code gap} 类全错，
 * 平均下来"看着还行"，但群友体感就是"一问位置就胡说"。
 *
 * <p>没有生产库时**跳过**（{@link Assumptions#assumeTrue}）—— 它是度量工具，
 * 不该让缺数据的机器构建失败。
 */
class RetrievalGoldenTest {

    /**
     * 门禁模式：{@code -Dqqbot.golden.gate=true}。
     *
     * <p><b>为什么需要这个开关</b>：本类默认是**度量工具** —— 缺语料、缺 key、向量路调不通，
     * 一律走 {@link Assumptions} 静默跳过，{@code mvn test} 照样绿。
     * 但"测不出结果时仍然显示绿色"的门禁，比没有门禁更糟：它给出的是"已经守住了"的假信号。
     * 打开本开关后语义反过来 —— <strong>测不了就是失败</strong>。
     *
     * <p>由 {@code scripts/golden-gate.sh} 打开；{@code scripts/preflight.sh} 跑的就是它。
     */
    private static final boolean GATE = Boolean.parseBoolean(System.getProperty("qqbot.golden.gate", "false"));

    @TempDir
    Path tmp;

    private QaStore qaStore;
    private SqliteDatabase sqlite;

    /**
     * 逐条明细的列定义 —— <b>表头与数据行由同一份定义渲染</b>。
     *
     * <p>为什么必须同源：这份 tsv 被 {@code scripts/golden-gate.sh} 复制成
     * {@code .toolchain/verify/golden-detail.tsv} 供人逐条分析，<b>列名就是它的契约</b>。
     * 曾经在行中间插了一列却没改表头，于是"按列名取值"读到的是隔壁列 —— 静默错。
     *
     * <p>顺序即列序：新列一律<b>追加到末尾</b>，插在中间会让所有按位置消费的人错位。
     */
    private static final List<String> DETAIL_COLUMNS = List.of(
            "category", "best_cosine_raw", "hits", "top1", "best_cosine", "question", "top1_score");

    /** 逐条明细，列序见 {@link #DETAIL_COLUMNS}，跑完写到 target/ 供分析 */
    private final List<String> detail = new ArrayList<>();

    /** 重排是否**真的生效**了（可用 + 确实产出了 +rerank 来源）。失败时会 fail-open 回 RRF，不能按重排后的门槛卡 */
    private boolean rerankEffective;

    /** 一条黄金样本 */
    private record Item(String category, String question, Set<String> expected, String note) {
        boolean expectsAnswer() {
            return !expected.isEmpty();
        }
    }

    @AfterEach
    void tearDown() {
        if (qaStore != null) {
            sqlite.close();
        }
    }

    /**
     * 前置条件不满足时的两种语义：普通模式跳过，门禁模式失败。
     *
     * <p>本类所有"缺东西"的判断都必须经过它 —— 任何一处直接写
     * {@link Assumptions#assumeTrue} 都会重新开出一个静默跳过的口子。
     */
    private static void requireMeasurable(boolean present, String what) {
        if (present) {
            return;
        }
        // ⚠️ 这两个分支的顺序与条件都必须是"不满足才进"：
        //    写成 if (GATE) fail(...) 会让门禁**永远红** —— 一个恒失败的门禁
        //    和恒通过的门禁一样没用，而且更难发现（它看起来"很严格"）。
        if (GATE) {
            fail("★ 门禁模式：测不了就是失败 —— " + what
                    + "。去掉 -Dqqbot.golden.gate=true 可退回「仅度量、不卡门槛」的模式");
        }
        Assumptions.assumeTrue(false, what + "，跳过");
    }

    @Test
    @DisplayName("★ 检索黄金集基线：分类统计 R@1 / R@5 / MRR / 干净率")
    void goldenBaseline() throws IOException {
        Path repo = findRepoRoot();
        // 失败时把判据一起说出来：门禁最常见的问题就是"它在哪个目录里找、找到了没有"，
        // 只报一句"找不到工程根"会让人无从下手。
        // 失败时把判据一起说出来：门禁最常见的问题就是"它在哪个目录里找、找到了没有"。
        requireMeasurable(repo != null, "找不到工程根（缺 tests/golden/retrieval-golden.tsv）"
                + "；user.dir=" + System.getProperty("user.dir")
                + "，-Dqqbot.repo.root=" + System.getProperty("qqbot.repo.root", "（未设置）"));
        Path prodDb = repo.resolve("server/data/qa/qqbot.sqlite");
        Path golden = repo.resolve("tests/golden/retrieval-golden.tsv");
        requireMeasurable(Files.isRegularFile(prodDb), "没有生产语料库：" + prodDb);
        requireMeasurable(Files.isRegularFile(golden), "没有黄金集：" + golden);

        // ---- 复制语料（绝不直接读生产库：WAL 共享内存 + 迁移会写库）----
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
        KbBlockIndex corpus = new KbBlockIndex(blocks);
        corpus.reload();

        KbProperties kb = new KbProperties();
        // 有 key 就开向量路（真实生产路径）；没有就退化成本地关键词路，仍然可跑
        String key = readEnv(repo.resolve(".env"), "SILICONFLOW_API_KEY");
        if (key != null && !key.isEmpty()) {
            kb.getEmbedding().setApiKey(key);
        }
        EmbeddingClient embedding = new EmbeddingClient(kb, mapper);
        // 重排也共用同一个 key；-Dqqbot.golden.rerank=off 可以关掉它做前后对比
        boolean rerankOn = !"off".equalsIgnoreCase(System.getProperty("qqbot.golden.rerank", "on"));
        if (rerankOn && key != null && !key.isEmpty()) {
            kb.getRerank().setApiKey(key);
        } else {
            kb.getRerank().setEnabled(false);
        }
        RerankClient rerankClient = new RerankClient(kb, mapper);
        // 词条就是分块：名字源直接是块存储（它实现核心的 TermSource）
        Glossary glossary = new Glossary(blocks);
        KbRetriever retriever = new KbRetriever(kb, corpus, glossary, embedding, rerankClient);

        // 向量路到底活着没有 —— 决定下面要不要设门槛。
        // 外面没网 / key 失效时 EmbeddingClient 会退化，A 路与 C 路一起静默跳过，
        // 此时测出来的是"只有本地关键词路"的另一种分布，拿它去卡门槛会变成假红。
        boolean vectorLive = embedding.isAvailable()
                && retriever.retrieve("向量路可用性自检").bestCosineRaw() > 0;

        System.out.println();
        System.out.println("================ 检索黄金集基线 ================");
        System.out.printf("语料：%d 块（维度 %d），标题向量 %d 条%n",
                blocks.count(), blocks.dimensions(), blocks.countTitleVectors());
        System.out.printf("术语表：%d 条%n", glossary.size());
        System.out.printf("重排：%s%n", rerankClient.isAvailable()
                ? "已启用（" + kb.getRerank().getModel() + "，候选 " + kb.getRerank().getCandidateLimit()
                        + "，阈值 " + kb.getRerank().getMinScore() + "）"
                : "关闭（-Dqqbot.golden.rerank=off 或无 key）");
        System.out.printf("向量路：%s（top-k=%d, min-score=%.2f, title-gate=%.2f）%n",
                vectorLive ? "已启用且可用（会产生 API 调用）"
                        : embedding.isAvailable() ? "★ 不可用（调用失败）—— 本次只跑本地关键词路，结果不可与基线比较"
                        : "未启用（无 SILICONFLOW_API_KEY，只跑本地关键词路）",
                kb.getTopK(), kb.getMinScore(), kb.getTitleGate());

        List<Item> items = load(golden);
        Map<String, List<Item>> byCat = new LinkedHashMap<>();
        for (Item it : items) {
            byCat.computeIfAbsent(it.category(), k -> new ArrayList<>()).add(it);
        }

        System.out.println();
        System.out.printf("%-12s %5s %8s %8s %8s %10s %12s%n",
                "类别", "条数", "R@1", "R@5", "MRR", "干净率", "注入块均值");
        Map<String, Stats> stats = new LinkedHashMap<>();
        for (Map.Entry<String, List<Item>> e : byCat.entrySet()) {
            // descriptive 尚未人工标注 —— 不参与打分，只在下面打印 Top-5 供判定
            if (e.getKey().equals("descriptive")) {
                System.out.printf("%-12s %5d %8s %8s %8s %10s %12s%n",
                        e.getKey(), e.getValue().size(), "-", "-", "-", "-", "-");
                continue;
            }
            stats.put(e.getKey(), report(e.getKey(), e.getValue(), retriever));
        }

        System.out.println();
        System.out.println("---- 最高余弦分布（bestCosineRaw，卡阈值【之前】的那一个）----");
        System.out.printf("%-12s %8s %8s %8s %8s%n", "类别", "min", "p50", "max", "n");
        for (Map.Entry<String, Stats> e : stats.entrySet()) {
            List<Double> cs = new ArrayList<>(e.getValue().cosines());
            if (cs.isEmpty()) {
                continue;
            }
            Collections.sort(cs);
            System.out.printf("%-12s %8.3f %8.3f %8.3f %8d%n", e.getKey(),
                    cs.get(0), cs.get(cs.size() / 2), cs.get(cs.size() - 1), cs.size());
        }

        Path detailFile = Path.of("target", "golden-detail.tsv");
        try {
            Files.createDirectories(detailFile.getParent());
            List<String> head = new ArrayList<>();
            head.add(String.join("\t", DETAIL_COLUMNS));
            head.addAll(detail);
            Files.write(detailFile, head, StandardCharsets.UTF_8);
            System.out.println("逐条明细已写出：" + detailFile.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("明细写出失败（不影响结果）：" + e.getMessage());
        }

        System.out.println();
        System.out.println("---- descriptive（尚未人工标注，打印 Top-5 供判定）----");
        for (Item it : byCat.getOrDefault("descriptive", List.of())) {
            List<KbRetriever.Hit> hits = retriever.retrieve(it.question()).hits();
            System.out.printf("  %s%n      -> %s%n", it.question(),
                    hits.isEmpty() ? "（空）" : preview(hits));
        }
        System.out.println("================ 基线结束 ================");

        // ---------------- 回归门槛 ----------------
        // 分两档，取决于**这一轮实际生效的是哪条路径**：
        //   重排生效   → 用重排后的高门槛（这是当前生产默认路径）
        //   只有向量路 → 用重排前的低门槛（离线/无 key 时的退化路径）
        //   两者都无   → 不设门槛，只打印
        // 说明：gap 类现在仍然是 0% 干净率 —— 那是**尚未解决**的问题（重排分不出"阳刺根本不存在"，
        // 它只会挑一件看起来像胸甲的），所以它是目标而不是门槛。
        System.out.println();
        if (!vectorLive) {
            System.out.println("★ 向量路不可用，本次不设回归门槛（只打印结果）");
            // 这里正是"假绿"最常发生的地方：网络不通或 key 失效时，
            // 测出来的是"只有本地关键词路"的另一种分布，再往下卡门槛毫无意义。
            // 门禁模式下必须当场失败，而不是打印一句"不设门槛"就过去。
            requireMeasurable(false, "向量路不可用，无法在生产路径上度量 —— 检查 .env 的 SILICONFLOW_API_KEY 与外网");
        } else if (!rerankEffective) {
            System.out.println("★ 重排未生效，按**重排前**的门槛检查");
            requireMeasurable(false, "重排未生效，门槛会退到重排前那一档，不代表生产路径");
        } else {
            System.out.println("★ 重排已生效，按**重排后**的门槛检查");
        }

        // ============ 门槛：两种口径，缺一不可 ============
        //
        // ① **绝对条数** —— 以"校准时实测的条数"为基准，允许少量退步。
        //
        //    ★ 2026-10-09 改，起因是百分比门槛把自己逼死了：这天加了 3 条**更难的**真实提问
        //    （力量/智力/瘴气，来自 wiki 词条缺中文名），entity R@1 从 70.0% 掉到 65.2%，
        //    **而答对的条数其实从 14 涨到 15**。百分比把这个"涨"报成了退步，
        //    而且余量被吃到 0 —— 再错一条就红，门槛从"防退化"变成了"随机报警"。
        //
        // ② **百分比** —— 防止样本大量增加后"绝对条数达标、正确率却崩了"。
        //    它设得比校准值松，只做粗筛，不承担精确判定的职责。
        //
        // 校准纪律：实测**真的变好**时把 baseline 调上去；变差时先查原因，别调门槛。
        //
        // 历史（保留以免重蹈）：
        //   · 2026-10-04 从 0.83/0.95 下调到 0.65/0.90 —— 原门槛是在**精选样本**上校准的
        //     （那批样本的注释里写着"实测本体不在 Top-3"等），是量过之后挑出来的成功案例，
        //     91.7% 被高估；
        //   · 2026-10-04 colloquial 从硬性"= 100%"改为"≥ 80%" —— 那是 n=2 时代定的，
        //     两条题错一条就是 50%，等于要求满分。
        Stats ent = vectorLive ? stats.get("entity") : null;
        if (ent != null && ent.n() > 0) {
            // 重排关掉时基线更低 —— 重排正是修掉"同族变体压过本体"的那一层。
            // 注：off 的那两个数是按原百分比门槛等比折算的**估计值**，不是实测值，
            //     那条路径只在排查时走。
            int r1Base = rerankEffective ? 15 : 13;
            int r5Base = rerankEffective ? 21 : 19;
            assertAtLeast("entity R@1", ent.r1(), ent.n(), r1Base, 2, rerankEffective ? 0.55 : 0.45);
            assertAtLeast("entity R@5", ent.r5(), ent.n(), r5Base, 1, rerankEffective ? 0.80 : 0.70);
        }
        Stats col = vectorLive ? stats.get("colloquial") : null;
        if (col != null && col.n() > 0) {
            // 5 条里错 1 条：不能召唤空荡独眼巨人 → 检回 hollow-cyclops-trophy。
            // 那是**真失败**：具体物品块（战利品）赢过通用页（Boss 本体），
            // 与 腐蚀之血↔腐血、城堡屋顶方块↔城堡方块 是同一种近名污染。
            assertAtLeast("colloquial R@5", col.r5(), col.n(), 4, 1, 0.70);
            if (rerankEffective) {
                assertAtLeast("colloquial R@1", col.r1(), col.n(), 4, 1, 0.60);
            }
        }
        // ★ 这一条是重排改造的**防退化**：闲聊的干净率从 6.7% 提到 93.3%，
        //   没有它，将来任何人放松阈值都会悄悄把这个改进还回去。
        Stats ch = rerankEffective ? stats.get("chitchat") : null;
        if (ch != null && ch.n() > 0) {
            assertAtLeast("chitchat 干净率", ch.clean(), ch.n(), 14, 2, 0.70);
        }
        int junkBlocks = (stats.containsKey("gap") ? stats.get("gap").injected() : 0)
                + (stats.containsKey("chitchat") ? stats.get("chitchat").injected() : 0);
        int junkItems = (stats.containsKey("gap") ? stats.get("gap").n() : 0)
                + (stats.containsKey("chitchat") ? stats.get("chitchat").n() : 0);
        System.out.printf("gap+chitchat：%d 条不需要知识库的问题，共注入 %d 块无关资料（重排前基线 95 块）%n",
                junkItems, junkBlocks);

        // 脚本按这一行判定结论：出现 PASS 才算过。上面的 assert 一旦失败就抛异常，走不到这里。
        System.out.println(GATE
                ? "GOLDEN-GATE: PASS（重排生效，按底线口径检查通过）"
                : "GOLDEN-GATE: OFF（本次仅度量，未启用门禁）");
        System.out.println();
    }

    /**
     * 门槛断言：**绝对条数 + 百分比两个口径都要过**。
     *
     * @param what          指标名，进失败信息
     * @param actual        本次实测答对条数
     * @param n             该类别样本总数
     * @param baseline      校准时实测的条数
     * @param allowedMisses 允许的绝对退步条数
     * @param pctFloor      粗筛用的百分比下限，设得比校准值松
     */
    private static void assertAtLeast(String what, int actual, int n,
                                      int baseline, int allowedMisses, double pctFloor) {
        assertTrue(actual >= baseline - allowedMisses,
                what + " 低于绝对门槛：答对 " + actual + "/" + n
                        + "，校准基线 " + baseline + " 条，允许退步 " + allowedMisses + " 条");
        assertTrue(actual >= Math.ceil(n * pctFloor),
                what + " 低于百分比门槛 " + pctFloor + "：答对 " + actual + "/" + n
                        + "（样本增加后正确率崩了也会在这里红）");
    }

    /** 一个类别的统计结果 */
    private record Stats(int n, int r1, int r5, int clean, int injected, double mrr,
                         List<Double> cosines) {
    }

    /** 分类统计并打印，同时列出逐条明细 */
    private Stats report(String category, List<Item> items, KbRetriever retriever) {
        int n = 0, r1 = 0, r5 = 0, clean = 0;
        double mrr = 0;
        int injected = 0;
        List<String> details = new ArrayList<>();
        List<Double> cosines = new ArrayList<>();

        for (Item it : items) {
            KbRetriever.Retrieval ret = retriever.retrieve(it.question());
            List<KbRetriever.Hit> hits = ret.hits();
            if (hits.stream().anyMatch(x -> x.source() != null && x.source().contains("rerank"))) {
                rerankEffective = true;
            }
            cosines.add(ret.bestCosineRaw());
            // 列序必须与 DETAIL_COLUMNS 一致；新列追加在末尾
            detail.add(String.join("\t", category,
                    String.format("%.4f", ret.bestCosineRaw()),
                    String.valueOf(hits.size()),
                    hits.isEmpty() ? "-" : hits.get(0).entry().id().value(),
                    String.valueOf(ret.bestCosine()),
                    it.question(),
                    // 末列 = top1 的分数。判定"这次到底有没有资料"用的就是它：
                    // 重排开着时是重排分（闲聊 ≤0.002、真实问题 ≥0.04），
                    // 关掉重排时是 RRF/双塔分 —— 所以列名刻意不叫 rerank
                    hits.isEmpty() ? "-" : String.format("%.4f", hits.get(0).score())));
            n++;
            injected += hits.size();
            if (it.expectsAnswer()) {
                int rank = rankOf(hits, it.expected());
                if (rank == 1) r1++;
                if (rank >= 1 && rank <= 5) r5++;
                if (rank >= 1) mrr += 1.0 / rank;
                details.add(String.format("    %s  rank=%s  %s%n        -> %s",
                        rank == 1 ? "R@1 " : rank > 0 ? "R@" + rank : "MISS",
                        rank == 0 ? "-" : String.valueOf(rank), it.question(),
                        hits.isEmpty() ? "（空）" : preview(hits)));
            } else {
                boolean isClean = hits.isEmpty();
                if (isClean) clean++;
                if (!isClean) {
                    details.add(String.format("    ✗ 不该注入却注入了 %d 块：%s%n        -> %s",
                            hits.size(), it.question(), preview(hits)));
                }
            }
        }

        System.out.printf("%-12s %5d %8s %8s %8s %10s %12.1f%n", category, n,
                pct(r1, n), pct(r5, n), n == 0 ? "-" : String.format("%.3f", mrr / n),
                pct(clean, n), (double) injected / Math.max(1, n));

        if (category.equals("entity") || category.equals("colloquial")) {
            System.out.println("  ---- " + category + " 逐条 ----");
            details.forEach(System.out::println);
        } else if (!details.isEmpty()) {
            System.out.println("  ---- " + category + " 不该注入的 ----");
            details.forEach(System.out::println);
        }
        return new Stats(n, r1, r5, clean, injected, mrr / Math.max(1, n), cosines);
    }

    /** 第一条命中期望块的排名（1 起）；没命中返回 0 */
    private static int rankOf(List<KbRetriever.Hit> hits, Set<String> expected) {
        for (int i = 0; i < hits.size(); i++) {
            if (expected.contains(hits.get(i).entry().id().value())) {
                return i + 1;
            }
        }
        return 0;
    }

    private static String preview(List<KbRetriever.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (KbRetriever.Hit h : hits) {
            if (i > 1) {
                sb.append(" | ");
            }
            sb.append(h.entry().id().value()).append('(').append(i++).append(')');
        }
        return sb.toString();
    }

    private static String pct(int num, int den) {
        return den == 0 ? "-" : String.format("%.1f%%", 100.0 * num / den);
    }

    private static List<Item> load(Path file) throws IOException {
        List<Item> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 3) {
                continue;
            }
            Set<String> exp = new LinkedHashSet<>();
            // '?' 表示尚未人工标注：不参与打分，但要在报告里列出来等人处理
            if (!f[2].isBlank() && !f[2].equals("-") && !f[2].equals("?")) {
                for (String id : f[2].split(";")) {
                    if (!id.isBlank()) {
                        exp.add(id.trim());
                    }
                }
            }
            if (f[2].equals("?")) {
                out.add(new Item("descriptive", f[1], Set.of(), f.length > 3 ? f[3] : ""));
            } else {
                out.add(new Item(f[0], f[1], exp, f.length > 3 ? f[3] : ""));
            }
        }
        return out;
    }

    /**
     * 找工程根。
     *
     * <p><b>为什么不用 {@code ProjectFiles.projectRoot()}</b>：它以
     * {@code system.md} / {@code server/pom.xml} 为判据向上找，而隔离构建
     * （{@code scripts/build-server.sh}）会把 {@code system.md} 复制到
     * {@code .toolchain/system.md} —— 于是从 {@code .toolchain/server-ci} 向上找时，
     * 先撞上 {@code .toolchain}，把工程根认成了 {@code .toolchain}，
     * 生产语料与黄金集就都找不到了（表现为整个测试被静默跳过）。
     *
     * <p>所以这里用**只在仓库根存在**的判据：黄金集文件本身，或生产语料库。
     */
    private static Path findRepoRoot() {
        String override = System.getProperty("qqbot.repo.root");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override).toAbsolutePath().normalize();
            return Files.isDirectory(p) ? p : null;
        }
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("tests/golden/retrieval-golden.tsv"))
                    || Files.isRegularFile(p.resolve("server/data/qa/qqbot.sqlite"))) {
                return p;
            }
        }
        return null;
    }

    /** 只读一个 key，**不打印值** */
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
            // 读不到就当没有 key
        }
        return null;
    }
}
